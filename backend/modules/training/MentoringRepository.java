package com.pulse.modules.training;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Mentoring sessions, session requests, the mentor's open work, checkpoint requests and the managers'
 * views (V350, V340/V345 for checkpoint reviews). Training checkpoint cycles live in the reviews tables
 * and use the same pseudonymous requests as 360° feedback.
 */
final class MentoringRepository extends ScopedRepository
{
    MentoringRepository(SqlExecutor sql)
    {
        super(sql);
    }

    // ------------------------------------------------------------------ sessions and requests

    Future<JsonObject> insertSession(UUID assignmentId, JsonObject s, UUID loggedBy)
    {
        return queryOne("""
                INSERT INTO mentoring_sessions (assignment_id, held_on, duration_min, mode, topics, next_steps, blocker,
                                                blocker_note, logged_by)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING id
                """,
                Tuple.tuple()
                        .addValue(assignmentId)
                        .addValue(LocalDate.parse(s.getString("heldOn")))
                        .addValue(s.getInteger("durationMin"))
                        .addValue(s.getString("mode"))
                        .addValue(s.getString("topics").strip())
                        .addValue(s.getString("nextSteps", ""))
                        .addValue(s.getBoolean("blocker", false))
                        .addValue(s.getString("blockerNote", ""))
                        .addValue(loggedBy));
    }

    Future<JsonObject> insertRequest(UUID assignmentId, String note, LocalDate today)
    {
        return queryOne("INSERT INTO mentoring_requests (assignment_id, note, requested_on) VALUES ($1, $2, $3) RETURNING id",
                Tuple.of(assignmentId, note.strip(), today));
    }

    /** Marks an open request of this assignment handled; resolves to the rows changed (0 or 1). */
    Future<Integer> handleRequest(UUID requestId, UUID assignmentId, LocalDate today)
    {
        return execute("UPDATE mentoring_requests SET handled_on = $3 WHERE id = $1 AND assignment_id = $2 AND handled_on IS NULL",
                Tuple.of(requestId, assignmentId, today));
    }

    // ------------------------------------------------------------------ the mentor's open work

    /**
     * Assignments the user mentors that are not finished, with what the Pending list needs:
     * {@code [{id, personId, personName, planName, startOn, endOn, weeks, assessment, finalReviews,
     * acknowledgements, outcome, version}]}.
     */
    Future<JsonArray> mentored(UUID mentorId)
    {
        return queryList("""
                SELECT a.id, a.person_id AS "personId", p.name AS "personName", v.name AS "planName", a.start_on AS "startOn",
                       a.end_on AS "endOn", v.weeks, a.assessment, a.final_reviews AS "finalReviews",
                       a.acknowledgements, a.outcome, a.sign_off AS "signOff", a.version
                FROM training_assignments a
                JOIN people p ON p.id = a.person_id
                JOIN training_plan_versions v ON v.plan_id = a.plan_id AND v.plan_version = a.plan_version
                WHERE a.mentor_id = $1
                ORDER BY a.start_on
                """,
                Tuple.of(mentorId));
    }

    /** Submitted (not yet reviewed) check-ins of the user's mentees. */
    Future<JsonArray> checkInsToReview(UUID mentorId)
    {
        return queryList("""
                SELECT c.id, c.assignment_id AS "assignmentId", c.period_start AS "periodStart", c.submitted_on AS "submittedOn",
                       p.name AS "personName"
                FROM training_checkins c JOIN training_assignments a ON a.id = c.assignment_id JOIN people p ON p.id = a.person_id
                WHERE a.mentor_id = $1 AND c.status = 'Submitted'
                """,
                Tuple.of(mentorId));
    }

    /** Open session requests of the user's mentees. */
    Future<JsonArray> openRequests(UUID mentorId)
    {
        return queryList("""
                SELECT r.id, r.assignment_id AS "assignmentId", r.note, r.requested_on AS "requestedOn", p.name AS "personName"
                FROM mentoring_requests r JOIN training_assignments a ON a.id = r.assignment_id JOIN people p ON p.id = a.person_id
                WHERE a.mentor_id = $1 AND r.handled_on IS NULL
                """,
                Tuple.of(mentorId));
    }

    /** Checkpoint reviews the user still has to give (training cycles, not submitted or declined). */
    Future<JsonArray> checkpointsToGive(UUID userId)
    {
        return queryList("""
                SELECT r.id, c.assignment_id AS "assignmentId", c.checkpoint_day AS "checkpointDay", c.due_on AS "dueOn",
                       p.name AS "personName"
                FROM review_requests r
                JOIN review_cycles c ON c.id = r.cycle_id
                JOIN review_reviewer_keys k ON k.pseudonym = r.reviewer_key
                JOIN people p ON p.id = r.subject_id
                WHERE c.kind = 'training' AND k.user_id = $1 AND r.status IN ('not_started', 'in_progress')
                """,
                Tuple.of(userId));
    }

    /** Last session date and session count per assignment of these ids. */
    Future<JsonArray> sessionStats(UUID[] assignmentIds)
    {
        return queryList("""
                SELECT assignment_id AS "assignmentId", max(held_on) AS "lastOn", count(*)::int AS sessions
                FROM mentoring_sessions WHERE assignment_id = ANY($1) GROUP BY assignment_id
                """,
                Tuple.of((Object) assignmentIds));
    }

    // ------------------------------------------------------------------ checkpoint requests

    /** The person's own user id, or null. */
    Future<String> userOf(UUID personId)
    {
        return queryOne("SELECT user_id AS \"userId\" FROM people WHERE id = $1", Tuple.of(personId))
                .map(row -> row == null ? null : row.getString("userId"));
    }

    /** The open reporting line's manager (a user) of the person, or null. */
    Future<String> managerOf(UUID personId)
    {
        return queryOne("""
                SELECT rl.manager_id AS "managerId" FROM people p JOIN reporting_lines rl ON rl.user_id = p.user_id
                WHERE p.id = $1 AND rl.valid_to IS NULL LIMIT 1
                """,
                Tuple.of(personId))
                .map(row -> row == null ? null : row.getString("managerId"));
    }

    /** Creates the checkpoint cycle; resolves to its id, or null when this checkpoint was already requested. */
    Future<String> insertCheckpointCycle(String name, String quarter, LocalDate due, UUID assignmentId, int day, UUID createdBy)
    {
        return queryOne("""
                INSERT INTO review_cycles (name, quarter, due_on, internal_min, internal_max, cross_min, cross_max, anonymity_threshold,
                                           weights, status, created_by, kind, assignment_id, checkpoint_day)
                VALUES ($1, $2, $3, 1, 3, 0, 2, 1, '{"manager": 50, "internal": 30, "cross": 0, "self": 20}'::jsonb, 'running', $4,
                        'training', $5, $6)
                ON CONFLICT (assignment_id, checkpoint_day) WHERE kind = 'training' DO NOTHING
                RETURNING id
                """,
                Tuple.tuple().addValue(name).addValue(quarter).addValue(due).addValue(createdBy).addValue(assignmentId).addValue(day))
                .map(row -> row == null ? null : row.getString("id"));
    }

    /** A request in a checkpoint cycle (the reviewer's pseudonym is created for the cycle); resolves to its id or null. */
    Future<String> insertCheckpointRequest(UUID cycleId, UUID subjectId, UUID reviewerUserId, String group, UUID assignedBy)
    {
        return queryOne("""
                INSERT INTO review_reviewer_keys (cycle_id, user_id) VALUES ($1, $2)
                ON CONFLICT (cycle_id, user_id) DO UPDATE SET user_id = EXCLUDED.user_id RETURNING pseudonym
                """,
                Tuple.of(cycleId, reviewerUserId))
                .compose(key -> queryOne("""
                        INSERT INTO review_requests (cycle_id, subject_id, reviewer_key, rater_group, assigned_by)
                        VALUES ($1, $2, $3, $4, $5) ON CONFLICT (cycle_id, subject_id, reviewer_key) DO NOTHING RETURNING id
                        """,
                        Tuple.of(cycleId, subjectId, UUID.fromString(key.getString("pseudonym")), group, assignedBy)))
                .map(row -> row == null ? null : row.getString("id"));
    }

    // ------------------------------------------------------------------ managers' views

    /**
     * Mentors of the assignments in scope: {@code [{mentorId, mentorName, active, completed, notCompleted,
     * averageMentorRating, lastSessionOn}]}.
     */
    Future<JsonArray> mentors(Scope scope)
    {
        return scopedList(scope, "a.person_id", """
                SELECT a.mentor_id AS "mentorId", m.name AS "mentorName",
                       count(*) FILTER (WHERE a.outcome IS NULL)::int AS active,
                       count(*) FILTER (WHERE a.outcome = 'completed')::int AS completed,
                       count(*) FILTER (WHERE a.outcome = 'not_completed')::int AS "notCompleted",
                       avg((a.sign_off ->> 'mentorRating')::numeric) AS "averageMentorRating",
                       (SELECT max(s.held_on) FROM mentoring_sessions s JOIN training_assignments x ON x.id = s.assignment_id
                        WHERE x.mentor_id = a.mentor_id) AS "lastSessionOn"
                FROM training_assignments a JOIN users m ON m.id = a.mentor_id
                WHERE a.mentor_id IS NOT NULL AND {scope}
                GROUP BY a.mentor_id, m.name
                ORDER BY m.name
                """,
                Tuple.tuple());
    }

    /** People in scope who joined after {@code since} and have no training assignment at all. */
    Future<JsonArray> joinersWithoutPlan(Scope scope, LocalDate since)
    {
        return scopedList(scope, "p.id", """
                SELECT p.id, p.name, p.joined_on AS "joinedOn" FROM people p
                WHERE p.joined_on > $1 AND {scope}
                  AND NOT EXISTS (SELECT 1 FROM training_assignments a WHERE a.person_id = p.id)
                ORDER BY p.joined_on DESC
                """,
                Tuple.of(since));
    }

    /** Library plans meant for onboarding (by name or audience), oldest first. */
    Future<JsonArray> onboardingPlans()
    {
        return queryList("""
                SELECT p.id, v.name, v.weeks FROM training_plans p
                JOIN training_plan_versions v ON v.plan_id = p.id AND v.plan_version = p.current_version
                WHERE p.deleted_at IS NULL AND (v.name ILIKE '%onboarding%' OR v.audience ILIKE '%new %' OR v.audience ILIKE '%trainee%')
                ORDER BY p.created_at
                """,
                Tuple.tuple());
    }
}
