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
 * Training assignments, check-ins and mentoring (V350). Personal data: reads take a policy
 * {@link Scope} (domain training), and a mentor also reads the assignments they mentor.
 *
 * <p>The mentor rule is added here ({@code OR a.mentor_id = viewer}) rather than as a module
 * {@code VisibilityRelation}: the platform tests run the training domain on a schema without these
 * tables, so a registered relation would fail there.
 */
final class AssignmentRepository extends ScopedRepository
{
    private static final String ASSIGNMENT = """
            SELECT a.id, a.person_id AS "personId", p.name AS "personName", p.user_id AS "personUserId",
                   a.plan_id AS "planId", a.plan_version AS "planVersion", v.name AS "planName", v.weeks,
                   v.pass_mark AS "passMark", v.assessment_type AS "assessmentType", v.modules, v.checkpoints,
                   v.values_built AS "values", a.mentor_id AS "mentorId", m.name AS "mentorName",
                   a.meeting_frequency AS "meetingFrequency", a.cadence, a.start_on AS "startOn", a.end_on AS "endOn",
                   a.assessment_window_days AS "assessmentWindowDays", a.attempt, a.outcome, a.assessment,
                   a.final_reviews AS "finalReviews", a.sign_off AS "signOff", a.acknowledgements,
                   a.module_progress AS "moduleProgress", a.history, a.assigned_on AS "assignedOn", a.version
            FROM training_assignments a
            JOIN people p ON p.id = a.person_id
            JOIN training_plan_versions v ON v.plan_id = a.plan_id AND v.plan_version = a.plan_version
            LEFT JOIN users m ON m.id = a.mentor_id
            """;

    AssignmentRepository(SqlExecutor sql)
    {
        super(sql);
    }

    // ------------------------------------------------------------------ assignments

    Future<JsonObject> byId(Scope scope, UUID id)
    {
        return scopedOne(scope, "a.person_id", ASSIGNMENT + " WHERE a.id = $1 AND ({scope} OR a.mentor_id = $2)",
                Tuple.of(id, UUID.fromString(scope.viewerUserId())));
    }

    /** Assignments the viewer may see (optionally one person's), newest start first. */
    Future<JsonArray> list(Scope scope, UUID personId)
    {
        return scopedList(scope, "a.person_id", ASSIGNMENT + """
                 WHERE ($1::uuid IS NULL OR a.person_id = $1) AND ({scope} OR a.mentor_id = $2)
                 ORDER BY a.start_on DESC, p.name
                """,
                Tuple.of(personId, UUID.fromString(scope.viewerUserId())));
    }

    /** Open assignments (no outcome) of these people with their plan's weeks and modules. */
    Future<JsonArray> openFor(UUID[] personIds)
    {
        return queryList("""
                SELECT a.person_id AS "personId", a.plan_id AS "planId", v.weeks, v.modules
                FROM training_assignments a
                JOIN training_plan_versions v ON v.plan_id = a.plan_id AND v.plan_version = a.plan_version
                WHERE a.person_id = ANY($1) AND a.outcome IS NULL
                """,
                Tuple.of((Object) personIds));
    }

    /** The current version of plans still in the library. */
    Future<JsonArray> plans(UUID[] planIds)
    {
        return queryList("""
                SELECT p.id, p.current_version AS "planVersion", v.name, v.weeks, v.cadence, v.modules
                FROM training_plans p
                JOIN training_plan_versions v ON v.plan_id = p.id AND v.plan_version = p.current_version
                WHERE p.id = ANY($1) AND p.deleted_at IS NULL
                """,
                Tuple.of((Object) planIds));
    }

    /** People by id with their user: {@code [{id, name, userId}]}. */
    Future<JsonArray> people(UUID[] personIds)
    {
        return queryList("SELECT id, name, user_id AS \"userId\" FROM people WHERE id = ANY($1)", Tuple.of((Object) personIds));
    }

    /** A mentor candidate: an active user with a performance profile, or null. */
    Future<JsonObject> mentor(UUID userId)
    {
        return queryOne("""
                SELECT u.id, u.name, p.id AS "personId" FROM users u JOIN people p ON p.user_id = u.id
                WHERE u.id = $1 AND u.status = 'Active'
                """,
                Tuple.of(userId));
    }

    Future<String> insert(JsonObject a, UUID assignedBy, LocalDate today)
    {
        return queryOne("""
                INSERT INTO training_assignments (person_id, plan_id, plan_version, mentor_id, meeting_frequency, cadence,
                                                  start_on, end_on, assessment_window_days, assigned_by, assigned_on)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11) RETURNING id
                """,
                Tuple.tuple()
                        .addValue(UUID.fromString(a.getString("personId")))
                        .addValue(UUID.fromString(a.getString("planId")))
                        .addValue(a.getInteger("planVersion"))
                        .addValue(UUID.fromString(a.getString("mentorId")))
                        .addValue(a.getString("meetingFrequency"))
                        .addValue(a.getString("cadence"))
                        .addValue(LocalDate.parse(a.getString("startOn")))
                        .addValue(a.getString("endOn") == null ? null : LocalDate.parse(a.getString("endOn")))
                        .addValue(a.getInteger("assessmentWindowDays"))
                        .addValue(assignedBy)
                        .addValue(today))
                .map(row -> row.getString("id"));
    }

    Future<JsonObject> updateDetails(UUID id, JsonObject a, int expectedVersion)
    {
        return updateVersioned("training_assignments", """
                mentor_id = $1, meeting_frequency = $2, cadence = $3, start_on = $4, end_on = $5, assessment_window_days = $6
                """,
                Tuple.tuple()
                        .addValue(UUID.fromString(a.getString("mentorId")))
                        .addValue(a.getString("meetingFrequency"))
                        .addValue(a.getString("cadence"))
                        .addValue(LocalDate.parse(a.getString("startOn")))
                        .addValue(a.getString("endOn") == null ? null : LocalDate.parse(a.getString("endOn")))
                        .addValue(a.getInteger("assessmentWindowDays")),
                id, expectedVersion);
    }

    /** Sets one jsonb column (assessment, final_reviews, sign_off, acknowledgements, module_progress). */
    Future<JsonObject> setJson(UUID id, String column, Object value, int expectedVersion)
    {
        if (!column.matches("assessment|final_reviews|sign_off|acknowledgements|module_progress"))
        {
            throw new IllegalArgumentException("Not a training json column: " + column);
        }

        return updateVersioned("training_assignments", column + " = $1::jsonb", Tuple.of(value), id, expectedVersion);
    }

    /** Extend: keeps the attempt in history, starts the next attempt with a new end date. */
    Future<JsonObject> extend(UUID id, JsonArray history, LocalDate newEnd, int expectedVersion)
    {
        return updateVersioned("training_assignments", """
                history = $1::jsonb, attempt = attempt + 1, end_on = $2, assessment = NULL, final_reviews = '{}'::jsonb
                """,
                Tuple.of(history, newEnd), id, expectedVersion);
    }

    /** The trainee's sign-off: outcome, sign-off and acknowledgements together. */
    Future<JsonObject> signOff(UUID id, String outcome, JsonObject signOff, JsonObject acknowledgements, int expectedVersion)
    {
        return updateVersioned("training_assignments", "outcome = $1, sign_off = $2::jsonb, acknowledgements = $3::jsonb",
                Tuple.of(outcome, signOff, acknowledgements), id, expectedVersion);
    }

    Future<Integer> delete(UUID id)
    {
        return execute("DELETE FROM training_assignments WHERE id = $1", Tuple.of(id));
    }

    // ------------------------------------------------------------------ check-ins

    Future<JsonArray> checkIns(UUID assignmentId)
    {
        return queryList("""
                SELECT c.id, c.period_start AS "periodStart", c.done, c.hours, c.confidence, c.blockers,
                       c.next_plan AS "nextPlan", c.submitted_on AS "submittedOn", c.status, r.name AS "reviewer",
                       c.reviewer_role AS "reviewerRole", c.comment, c.reviewed_on AS "reviewedOn", c.version
                FROM training_checkins c LEFT JOIN users r ON r.id = c.reviewer_id
                WHERE c.assignment_id = $1 ORDER BY c.period_start
                """,
                Tuple.of(assignmentId));
    }

    /** One check-in with its assignment id, or null. */
    Future<JsonObject> checkIn(UUID id)
    {
        return queryOne("SELECT id, assignment_id AS \"assignmentId\", status, version FROM training_checkins WHERE id = $1", Tuple.of(id));
    }

    /**
     * Submits (or resends) the check-in of a period; resolves to null when that period is already
     * verified (a verified check-in is final).
     */
    Future<JsonObject> submitCheckIn(UUID assignmentId, JsonObject c, LocalDate today)
    {
        return queryOne("""
                INSERT INTO training_checkins (assignment_id, period_start, done, hours, confidence, blockers, next_plan, submitted_on)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                ON CONFLICT (assignment_id, period_start) DO UPDATE
                SET done = EXCLUDED.done, hours = EXCLUDED.hours, confidence = EXCLUDED.confidence,
                    blockers = EXCLUDED.blockers, next_plan = EXCLUDED.next_plan, submitted_on = EXCLUDED.submitted_on,
                    status = 'Submitted', reviewer_id = NULL, reviewer_role = NULL, comment = '', reviewed_on = NULL,
                    version = training_checkins.version + 1, updated_at = now()
                WHERE training_checkins.status <> 'Verified'
                RETURNING id
                """,
                Tuple.tuple()
                        .addValue(assignmentId)
                        .addValue(LocalDate.parse(c.getString("periodStart")))
                        .addValue(c.getString("done"))
                        .addValue(c.getNumber("hours").doubleValue())
                        .addValue(c.getInteger("confidence"))
                        .addValue(c.getString("blockers", ""))
                        .addValue(c.getString("nextPlan", ""))
                        .addValue(today));
    }

    Future<JsonObject> reviewCheckIn(UUID id, String status, UUID reviewer, String role, String comment, LocalDate today,
                                     int expectedVersion)
    {
        return updateVersioned("training_checkins", "status = $1, reviewer_id = $2, reviewer_role = $3, comment = $4, reviewed_on = $5",
                Tuple.of(status, reviewer, role, comment, today), id, expectedVersion);
    }

    // ------------------------------------------------------------------ mentoring and calendar

    Future<JsonArray> sessions(UUID assignmentId)
    {
        return queryList("""
                SELECT s.id, s.held_on AS "heldOn", s.duration_min AS "durationMin", s.mode, s.topics,
                       s.next_steps AS "nextSteps", s.blocker, s.blocker_note AS "blockerNote", u.name AS "loggedBy"
                FROM mentoring_sessions s LEFT JOIN users u ON u.id = s.logged_by
                WHERE s.assignment_id = $1 ORDER BY s.held_on DESC
                """,
                Tuple.of(assignmentId));
    }

    Future<JsonArray> requests(UUID assignmentId)
    {
        return queryList("""
                SELECT id, note, requested_on AS "requestedOn", handled_on AS "handledOn"
                FROM mentoring_requests WHERE assignment_id = $1 ORDER BY requested_on DESC
                """,
                Tuple.of(assignmentId));
    }

    Future<JsonArray> holidays()
    {
        return queryList("SELECT holiday_on AS date, name, location FROM holidays", Tuple.tuple());
    }
}
