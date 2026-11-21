package com.pulse.modules.reviews;

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
 * 360° cycles and requests (V340). This repository is the only code that reads
 * {@code review_reviewer_keys} (pseudonym → user): every other read names reviewers by pseudonym or
 * not at all (spec "Pseudonymous reviewer storage").
 */
final class Review360Repository extends ScopedRepository
{
    private static final String CYCLE = """
            SELECT c.id, c.name, c.quarter, c.due_on AS "dueOn", c.department, c.internal_min AS "internalMin",
                   c.internal_max AS "internalMax", c.cross_min AS "crossMin", c.cross_max AS "crossMax",
                   c.anonymity_threshold AS "anonymityThreshold", c.weights, c.status, c.published_at AS "publishedAt",
                   c.version,
                   (SELECT count(*) FROM review_requests r WHERE r.cycle_id = c.id AND r.status <> 'declined')::int AS assigned,
                   (SELECT count(*) FROM review_requests r WHERE r.cycle_id = c.id AND r.status = 'submitted')::int AS submitted
            FROM review_cycles c
            """;

    Review360Repository(SqlExecutor sql)
    {
        super(sql);
    }

    // ------------------------------------------------------------------ cycles

    Future<JsonArray> cycles()
    {
        return queryList(CYCLE + " ORDER BY c.quarter DESC, c.created_at DESC", Tuple.tuple());
    }

    Future<JsonObject> cycle(UUID id)
    {
        return queryOne(CYCLE + " WHERE c.id = $1", Tuple.of(id));
    }

    Future<JsonObject> runningCycle()
    {
        return queryOne(CYCLE + " WHERE c.status = 'running'", Tuple.tuple());
    }

    Future<JsonObject> insertCycle(JsonObject body, String quarter, UUID createdBy)
    {
        return queryOne("""
                INSERT INTO review_cycles (name, quarter, due_on, department, internal_min, internal_max, cross_min,
                                           cross_max, anonymity_threshold, created_by)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING id
                """,
                Tuple.tuple()
                        .addValue(body.getString("name"))
                        .addValue(quarter)
                        .addValue(LocalDate.parse(body.getString("dueOn")))
                        .addValue(body.getString("department"))
                        .addValue(body.getInteger("internalMin", 3))
                        .addValue(body.getInteger("internalMax", 5))
                        .addValue(body.getInteger("crossMin", 2))
                        .addValue(body.getInteger("crossMax", 4))
                        .addValue(body.getInteger("anonymityThreshold", 3))
                        .addValue(createdBy));
    }

    // ------------------------------------------------------------------ people

    /**
     * Subjects: {@code [{id, name, department, userId, managerId}]} where managerId is the user of their
     * current manager (open reporting line).
     */
    Future<JsonArray> subjects(UUID[] personIds)
    {
        return queryList("""
                SELECT p.id, p.name, p.department, p.user_id AS "userId",
                       (SELECT rl.manager_id FROM reporting_lines rl
                        WHERE rl.user_id = p.user_id AND rl.valid_to IS NULL LIMIT 1) AS "managerId"
                FROM people p WHERE p.id = ANY($1)
                """,
                Tuple.of((Object) personIds));
    }

    /** Reviewers: {@code [{id, name, title, department, status}]}; department from the profile, else the user. */
    Future<JsonArray> reviewers(UUID[] userIds)
    {
        return queryList("""
                SELECT u.id, u.name, coalesce(p.title, u.title) AS title,
                       coalesce(p.department, u.department) AS department, u.status
                FROM users u LEFT JOIN people p ON p.user_id = u.id WHERE u.id = ANY($1)
                """,
                Tuple.of((Object) userIds));
    }

    // ------------------------------------------------------------------ pseudonyms

    /** The reviewer's pseudonym in this cycle, created on first use. */
    Future<String> keyFor(UUID cycleId, UUID userId)
    {
        return queryOne("""
                INSERT INTO review_reviewer_keys (cycle_id, user_id) VALUES ($1, $2)
                ON CONFLICT (cycle_id, user_id) DO UPDATE SET user_id = EXCLUDED.user_id
                RETURNING pseudonym
                """,
                Tuple.of(cycleId, userId))
                .map(row -> row.getString("pseudonym"));
    }

    /** Every pseudonym the user holds (one per cycle they review in). */
    Future<UUID[]> keysOf(UUID userId)
    {
        return queryList("SELECT pseudonym FROM review_reviewer_keys WHERE user_id = $1", Tuple.of(userId))
                .map(rows ->
                {
                    UUID[] keys = new UUID[rows.size()];

                    for (int i = 0; i < rows.size(); i++)
                    {
                        keys[i] = UUID.fromString(rows.getJsonObject(i).getString("pseudonym"));
                    }

                    return keys;
                });
    }

    // ------------------------------------------------------------------ requests

    /** Adds a request; resolves to its id, or null when it already exists (duplicates are skipped). */
    Future<String> insertRequest(UUID cycleId, UUID subjectId, String reviewerKey, String group, UUID assignedBy)
    {
        return queryOne("""
                INSERT INTO review_requests (cycle_id, subject_id, reviewer_key, rater_group, assigned_by)
                VALUES ($1, $2, $3, $4, $5)
                ON CONFLICT (cycle_id, subject_id, reviewer_key) DO NOTHING RETURNING id
                """,
                Tuple.of(cycleId, subjectId, UUID.fromString(reviewerKey), group, assignedBy))
                .map(row -> row == null ? null : row.getString("id"));
    }

    /** Requests per group for one subject in one cycle (declined excluded): {@code {group: count}}. */
    Future<JsonObject> groupCounts(UUID cycleId, UUID subjectId)
    {
        return queryList("""
                SELECT rater_group AS "group", count(*)::int AS assigned,
                       count(*) FILTER (WHERE status = 'submitted')::int AS submitted
                FROM review_requests WHERE cycle_id = $1 AND subject_id = $2 AND status <> 'declined'
                GROUP BY rater_group
                """,
                Tuple.of(cycleId, subjectId))
                .map(rows ->
                {
                    JsonObject counts = new JsonObject();

                    rows.forEach(r -> counts.put(((JsonObject) r).getString("group"), new JsonObject()
                            .put("assigned", ((JsonObject) r).getInteger("assigned"))
                            .put("submitted", ((JsonObject) r).getInteger("submitted"))));

                    return counts;
                });
    }

    /** The reviewer's own requests (by their pseudonyms), declined ones left out, open ones first. */
    Future<JsonArray> toGive(UUID[] keys)
    {
        return queryList("""
                SELECT r.id, r.rater_group AS "group", r.status, r.submitted_at AS "submittedAt", r.version,
                       c.id AS "cycleId", c.name AS "cycleName", c.due_on AS "dueOn", c.status AS "cycleStatus",
                       p.id AS "subjectId", p.name AS "subjectName"
                FROM review_requests r JOIN review_cycles c ON c.id = r.cycle_id JOIN people p ON p.id = r.subject_id
                WHERE r.reviewer_key = ANY($1) AND r.status <> 'declined'
                ORDER BY (r.status = 'submitted'), c.due_on, p.name
                """,
                Tuple.of((Object) keys));
    }

    /** One request with its answers, only when it belongs to one of the reviewer's pseudonyms. */
    Future<JsonObject> ownRequest(UUID id, UUID[] keys)
    {
        return queryOne("""
                SELECT r.id, r.rater_group AS "group", r.status, r.scores, r.examples, r.keep_doing AS "keepDoing",
                       r.start_doing AS "startDoing", r.submitted_at AS "submittedAt", r.version,
                       c.id AS "cycleId", c.name AS "cycleName", c.due_on AS "dueOn", c.status AS "cycleStatus",
                       p.id AS "subjectId", p.name AS "subjectName"
                FROM review_requests r JOIN review_cycles c ON c.id = r.cycle_id JOIN people p ON p.id = r.subject_id
                WHERE r.id = $1 AND r.reviewer_key = ANY($2)
                """,
                Tuple.of(id, (Object) keys));
    }

    Future<JsonObject> saveAnswers(UUID id, JsonObject scores, JsonObject examples, String keep, String start, boolean submit,
                                   int expectedVersion)
    {
        String status = submit ? "'submitted', submitted_at = now()" : "'in_progress'";

        return updateVersioned("review_requests", "scores = $1, examples = $2, keep_doing = $3, start_doing = $4, status = " + status,
                Tuple.of(scores, examples, keep, start), id, expectedVersion);
    }

    Future<JsonObject> decline(UUID id, int expectedVersion)
    {
        return updateVersioned("review_requests", "status = 'declined'", Tuple.tuple(), id, expectedVersion);
    }

    /**
     * The manager's assignment view: every request of a cycle for subjects the scope may see, with the
     * reviewer's name and state (the spec lets the manager role see who has submitted here) but never
     * the answers.
     */
    Future<JsonArray> assignments(Scope scope, UUID cycleId)
    {
        return scopedList(scope, "r.subject_id", """
                SELECT r.id, r.rater_group AS "group", r.status, r.version, p.id AS "subjectId", p.name AS "subjectName",
                       u.id AS "reviewerId", u.name AS "reviewerName", coalesce(rp.title, u.title) AS "reviewerTitle",
                       coalesce(rp.department, u.department) AS "reviewerDepartment"
                FROM review_requests r
                JOIN people p ON p.id = r.subject_id
                JOIN review_reviewer_keys k ON k.pseudonym = r.reviewer_key
                JOIN users u ON u.id = k.user_id
                LEFT JOIN people rp ON rp.user_id = u.id
                WHERE r.cycle_id = $1 AND {scope}
                ORDER BY p.name, r.rater_group, u.name
                """,
                Tuple.of(cycleId));
    }

    /** A request of a visible subject (for remove), or null. */
    Future<JsonObject> visibleRequest(Scope scope, UUID id)
    {
        return scopedOne(scope, "r.subject_id", """
                SELECT r.id, r.cycle_id AS "cycleId", r.subject_id AS "subjectId", r.rater_group AS "group", r.status
                FROM review_requests r WHERE r.id = $1 AND {scope}
                """,
                Tuple.of(id));
    }

    Future<Integer> deleteUnsubmitted(UUID id)
    {
        return execute("DELETE FROM review_requests WHERE id = $1 AND status <> 'submitted'", Tuple.of(id));
    }
}
