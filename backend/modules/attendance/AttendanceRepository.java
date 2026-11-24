package com.pulse.modules.attendance;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Attendance records and monthly uploads (V310). Scores are not read here: they come from calc/people
 * ({@code people.attendance}).
 */
final class AttendanceRepository extends ScopedRepository
{
    private static final String RECORD = """
            SELECT r.person_id AS "personId", r.day, r.status, r.leave_type AS "leaveType", r.applied_on AS "appliedOn",
                   to_char(r.check_in, 'HH24:MI') AS "checkIn", to_char(r.check_out, 'HH24:MI') AS "checkOut"
            FROM attendance_records r
            """;

    AttendanceRepository(SqlExecutor sql)
    {
        super(sql);
    }

    // ------------------------------------------------------------------ views

    /** People in scope: {@code [{id, name, userId, joinedOn, department}]}, by name. */
    Future<JsonArray> people(Scope scope)
    {
        return scopedList(scope, "p.id", """
                SELECT p.id, p.name, p.user_id AS "userId", p.joined_on AS "joinedOn", p.department
                FROM people p WHERE {scope} ORDER BY p.name
                """,
                Tuple.tuple());
    }

    /** One person, or null: {@code {id, name, userId, joinedOn, department}}. */
    Future<JsonObject> person(UUID id)
    {
        return queryOne("""
                SELECT p.id, p.name, p.user_id AS "userId", p.joined_on AS "joinedOn", p.department FROM people p WHERE p.id = $1
                """,
                Tuple.of(id));
    }

    /** Records of these people between two days, oldest first. */
    Future<JsonArray> records(UUID[] people, LocalDate from, LocalDate to)
    {
        return queryList(RECORD + " WHERE r.person_id = ANY($1) AND r.day BETWEEN $2 AND $3 ORDER BY r.day",
                Tuple.of(people, from, to));
    }

    /** Customer visits of these people that touch the days ({@code [{personId, from, to}]}), when visits exist. */
    Future<JsonArray> visits(UUID[] people, LocalDate from, LocalDate to)
    {
        return queryOne("SELECT to_regclass('visits') IS NOT NULL AS installed", Tuple.tuple()).compose(row -> !row.getBoolean("installed")
                ? Future.succeededFuture(new JsonArray())
                : queryList("""
                        SELECT person_id AS "personId", from_on AS "from", to_on AS "to" FROM visits
                        WHERE person_id = ANY($1) AND to_on >= $2 AND from_on <= $3
                        """,
                        Tuple.of(people, from, to)));
    }

    /** Organisation-wide holidays: {@code [{date, name, location}]}. */
    Future<JsonArray> holidays()
    {
        return queryList("SELECT holiday_on AS date, name, location FROM holidays", Tuple.tuple());
    }

    /** Every monthly upload, newest month first. */
    Future<JsonArray> uploads()
    {
        return queryList("""
                SELECT id, month, uploaded_by_name AS "uploadedBy", uploaded_at AS "uploadedAt", file_name AS "fileName",
                       row_count AS rows, source
                FROM attendance_uploads ORDER BY month DESC
                """,
                Tuple.tuple());
    }

    // ------------------------------------------------------------------ the upload

    /**
     * Users with these e-mails (lower case) and their person: {@code [{email, userId, status, personId, name,
     * joinedOn}]}; {@code personId} is null for a user without a performance profile.
     */
    Future<JsonArray> usersByEmail(String[] emails)
    {
        return queryList("""
                SELECT lower(u.email) AS email, u.id AS "userId", u.status, p.id AS "personId", coalesce(p.name, u.name) AS name,
                       p.joined_on AS "joinedOn"
                FROM users u LEFT JOIN people p ON p.user_id = u.id
                WHERE lower(u.email) = ANY($1) AND u.deleted_at IS NULL
                """,
                Tuple.of((Object) emails));
    }

    /** Stored records of these people between two days with their ids ({@code [{id, personId, day, ...}]}). */
    Future<JsonArray> stored(UUID[] people, LocalDate from, LocalDate to)
    {
        return queryList(RECORD.replace("SELECT ", "SELECT r.id, r.version, ")
                        + " WHERE r.person_id = ANY($1) AND r.day BETWEEN $2 AND $3",
                Tuple.of(people, from, to));
    }

    /** The full rows of one person in one month, locked: the before-images of a Replace. */
    Future<JsonArray> lockMonth(UUID person, LocalDate first, LocalDate last)
    {
        return queryList("SELECT * FROM attendance_records WHERE person_id = $1 AND day BETWEEN $2 AND $3 ORDER BY day FOR UPDATE",
                Tuple.of(person, first, last));
    }

    /** The full row of one person on one day, locked, or null. */
    Future<JsonObject> lockDay(UUID person, LocalDate day)
    {
        return queryOne("SELECT * FROM attendance_records WHERE person_id = $1 AND day = $2 FOR UPDATE", Tuple.of(person, day));
    }

    Future<Integer> delete(UUID id)
    {
        return execute("DELETE FROM attendance_records WHERE id = $1", Tuple.of(id));
    }

    /** Adds a record from an upload; resolves to {@code {id, version}}. */
    Future<JsonObject> insert(UUID person, JsonObject r)
    {
        return queryOne("""
                INSERT INTO attendance_records (person_id, day, status, leave_type, applied_on, check_in, check_out, source, external_id)
                VALUES ($1, $2, $3, $4, $5, $6, $7, 'csv', $8)
                RETURNING id, version
                """,
                values(person, r));
    }

    /** Overwrites a record from an upload (Merge days); resolves to {@code {version}}. */
    Future<JsonObject> update(UUID id, UUID person, JsonObject r)
    {
        return queryOne("""
                UPDATE attendance_records SET status = $3, leave_type = $4, applied_on = $5, check_in = $6, check_out = $7,
                       source = 'csv', external_id = $8, updated_at = now(), version = version + 1
                WHERE id = $9 AND person_id = $1 AND day = $2 RETURNING version
                """,
                values(person, r).addValue(id));
    }

    private static Tuple values(UUID person, JsonObject r)
    {
        LocalDate day = LocalDate.parse(r.getString("day"));

        return Tuple.tuple()
                .addValue(person)
                .addValue(day)
                .addValue(r.getString("status"))
                .addValue(r.getString("leaveType"))
                .addValue(r.getString("appliedOn") == null ? null : LocalDate.parse(r.getString("appliedOn")))
                .addValue(r.getString("checkIn") == null ? null : LocalTime.parse(r.getString("checkIn")))
                .addValue(r.getString("checkOut") == null ? null : LocalTime.parse(r.getString("checkOut")))
                .addValue(person + ":" + day);
    }

    /** The upload row of a month, locked, or null. */
    Future<JsonObject> lockUpload(String month)
    {
        return queryOne("SELECT * FROM attendance_uploads WHERE month = $1 FOR UPDATE", Tuple.of(month));
    }

    Future<JsonObject> insertUpload(String month, UUID by, String byName, String file, int rows)
    {
        return queryOne("""
                INSERT INTO attendance_uploads (month, uploaded_by, uploaded_by_name, file_name, row_count, source, external_id)
                VALUES ($1, $2, $3, $4, $5, 'csv', $1) RETURNING id, version
                """,
                Tuple.of(month, by, byName, file, rows));
    }

    Future<JsonObject> replaceUpload(UUID id, UUID by, String byName, String file, int rows)
    {
        return queryOne("""
                UPDATE attendance_uploads SET uploaded_by = $2, uploaded_by_name = $3, file_name = $4, row_count = $5, uploaded_at = now(),
                       updated_at = now(), version = version + 1
                WHERE id = $1 RETURNING version
                """,
                Tuple.of(id, by, byName, file, rows));
    }

    /** Current managers (open reporting line) of these people's users: {@code [{managerId, personId}]}. */
    Future<JsonArray> managersOf(UUID[] people)
    {
        return queryList("""
                SELECT DISTINCT rl.manager_id AS "managerId" FROM people p JOIN reporting_lines rl ON rl.user_id = p.user_id
                WHERE p.id = ANY($1) AND rl.valid_to IS NULL
                """,
                Tuple.of((Object) people));
    }

    /** Records of the people in scope in the template layout, oldest first. */
    Future<JsonArray> export(Scope scope, LocalDate from, LocalDate to)
    {
        return scopedList(scope, "r.person_id", """
                SELECT u.email AS employee_email, r.day AS date, r.status, r.leave_type, r.applied_on,
                       to_char(r.check_in, 'HH24:MI') AS check_in, to_char(r.check_out, 'HH24:MI') AS check_out
                FROM attendance_records r JOIN people p ON p.id = r.person_id JOIN users u ON u.id = p.user_id
                WHERE r.day BETWEEN $1 AND $2 AND {scope}
                ORDER BY u.email, r.day
                """,
                Tuple.of(from, to));
    }

    /** Live, active accounts whose role holds attendance.upload: {@code [{id}]}. */
    Future<JsonArray> uploaders()
    {
        return queryList("""
                SELECT DISTINCT u.id FROM users u JOIN role_permissions rp ON rp.role_id = u.role_id AND rp.permission = 'attendance.upload'
                WHERE u.status = 'Active' AND u.deleted_at IS NULL
                """,
                Tuple.tuple());
    }

    /** Whether the user already has this notice (same kind and link), so a re-run sends nothing twice. */
    Future<Boolean> noticeSent(UUID userId, String kind, String link)
    {
        return queryOne("SELECT 1 AS x FROM notifications WHERE user_id = $1 AND kind = $2 AND link = $3 LIMIT 1", Tuple.of(userId, kind, link))
                .map(row -> row != null);
    }
}
