package com.pulse.modules.visits;

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
 * Customer visits (V320). Personal data: reads take a policy {@link Scope}, and the account owner of a
 * visit may read it too.
 *
 * <p>The account owner is a <em>user</em> ({@code owner_id} references users): account managers such as
 * Sana have no performance profile. The scope's built-in owner column compares with the viewer's
 * person id, so the "or I own it" part is added here against {@link Scope#viewerUserId()}.
 */
final class VisitRepository extends ScopedRepository
{
    private static final String VISIT = """
            SELECT v.id, v.person_id AS "personId", p.name AS "personName", p.user_id AS "personUserId",
                   v.customer, v.city, v.purpose, v.from_on AS "from", v.to_on AS "to",
                   v.requested_by AS "requestedBy", v.owner_id AS "ownerId", o.name AS "ownerName",
                   v.reference, v.notes,
                   v.report_done AS "reportDone", v.report_onsite_hours AS "onsiteHours",
                   v.report_travel_hours AS "travelHours", v.report_outcome AS "outcome",
                   v.report_follow_ups AS "followUps", v.report_contact AS "contact", v.reported_on AS "reportedOn",
                   v.feedback_csat AS "csat", v.feedback_again AS "askAgain", v.feedback_source AS "feedbackSource",
                   v.feedback_on AS "feedbackOn", v.feedback_quote AS "quote", fb.name AS "feedbackBy",
                   v.verification, vb.name AS "verifiedBy", v.verified_at AS "verifiedAt", v.verify_note AS "verifyNote",
                   v.version
            FROM visits v
            JOIN people p ON p.id = v.person_id
            JOIN users o ON o.id = v.owner_id
            LEFT JOIN users fb ON fb.id = v.feedback_by
            LEFT JOIN users vb ON vb.id = v.verified_by
            """;

    VisitRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** One visit the scope (or its account owner) may see, or null. */
    Future<JsonObject> byId(Scope scope, UUID id)
    {
        return scopedOne(scope, "v.person_id", VISIT + " WHERE v.id = $1 AND ({scope} OR v.owner_id = $2)",
                Tuple.of(id, UUID.fromString(scope.viewerUserId())));
    }

    /**
     * Visits starting in [from, to] that the scope may see, newest first. {@code personId} and
     * {@code ownerId} narrow the list (null: no filter).
     */
    Future<JsonArray> list(Scope scope, UUID personId, UUID ownerId, LocalDate from, LocalDate to)
    {
        return scopedList(scope, "v.person_id", VISIT + """
                 WHERE ($1::uuid IS NULL OR v.person_id = $1) AND ($2::uuid IS NULL OR v.owner_id = $2)
                   AND v.from_on BETWEEN $3 AND $4 AND ({scope} OR v.owner_id = $5)
                 ORDER BY v.from_on DESC, v.id
                """,
                Tuple.of(personId, ownerId, from, to, UUID.fromString(scope.viewerUserId())));
    }

    /**
     * Serializes plan, log and edit for one person until the transaction ends, so two overlapping
     * visits cannot both pass the overlap check.
     */
    Future<JsonObject> lockPerson(UUID personId)
    {
        return queryOne("SELECT pg_advisory_xact_lock(hashtext($1)) IS NULL AS locked", Tuple.of("pulse.visits." + personId));
    }

    /** The first other visit of the person that shares a day with [from, to], or null. */
    Future<JsonObject> overlapping(UUID personId, LocalDate from, LocalDate to, UUID exceptId)
    {
        return queryOne("""
                SELECT id, customer, from_on AS "from", to_on AS "to" FROM visits
                WHERE person_id = $1 AND from_on <= $3 AND to_on >= $2 AND ($4::uuid IS NULL OR id <> $4)
                ORDER BY from_on LIMIT 1
                """,
                Tuple.of(personId, from, to, exceptId));
    }

    /** True when the user exists and is not deactivated (an account owner must be able to sign in). */
    Future<Boolean> activeUser(UUID userId)
    {
        return queryOne("SELECT 1 AS ok FROM users WHERE id = $1 AND status <> 'Deactivated'", Tuple.of(userId))
                .map(row -> row != null);
    }

    Future<JsonObject> insert(JsonObject visit, UUID createdBy)
    {
        return queryOne("""
                INSERT INTO visits (person_id, customer, city, purpose, from_on, to_on, requested_by, owner_id,
                                    reference, notes, created_by)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11) RETURNING id
                """,
                Tuple.tuple()
                        .addValue(UUID.fromString(visit.getString("personId")))
                        .addValue(visit.getString("customer"))
                        .addValue(visit.getString("city"))
                        .addValue(visit.getString("purpose"))
                        .addValue(LocalDate.parse(visit.getString("from")))
                        .addValue(LocalDate.parse(visit.getString("to")))
                        .addValue(visit.getString("requestedBy"))
                        .addValue(UUID.fromString(visit.getString("ownerId")))
                        .addValue(visit.getString("reference"))
                        .addValue(visit.getString("notes", ""))
                        .addValue(createdBy));
    }

    Future<JsonObject> update(UUID id, JsonObject visit, int expectedVersion)
    {
        return updateVersioned("visits", """
                customer = $1, city = $2, purpose = $3, from_on = $4, to_on = $5, requested_by = $6, owner_id = $7,
                reference = $8, notes = $9
                """,
                Tuple.tuple()
                        .addValue(visit.getString("customer"))
                        .addValue(visit.getString("city"))
                        .addValue(visit.getString("purpose"))
                        .addValue(LocalDate.parse(visit.getString("from")))
                        .addValue(LocalDate.parse(visit.getString("to")))
                        .addValue(visit.getString("requestedBy"))
                        .addValue(UUID.fromString(visit.getString("ownerId")))
                        .addValue(visit.getString("reference"))
                        .addValue(visit.getString("notes", "")),
                id, expectedVersion);
    }

    /** Deletes an unreported visit at the expected version; resolves to the rows removed (0 or 1). */
    Future<Integer> delete(UUID id, int expectedVersion)
    {
        return execute("DELETE FROM visits WHERE id = $1 AND version = $2 AND reported_on IS NULL",
                Tuple.of(id, expectedVersion));
    }

    /** Files the report; the points now wait for verification. */
    Future<JsonObject> report(UUID id, JsonObject report, LocalDate today, int expectedVersion)
    {
        return updateVersioned("visits", """
                report_done = $1, report_onsite_hours = $2, report_travel_hours = $3, report_outcome = $4,
                report_follow_ups = $5, report_contact = $6, reported_on = $7, verification = 'pending'
                """,
                Tuple.tuple()
                        .addValue(report.getString("done"))
                        .addValue(report.getValue("onsiteHours") == null ? null : report.getNumber("onsiteHours").doubleValue())
                        .addValue(report.getValue("travelHours") == null ? null : report.getNumber("travelHours").doubleValue())
                        .addValue(report.getString("outcome"))
                        .addValue(report.getString("followUps"))
                        .addValue(report.getString("contact"))
                        .addValue(today),
                id, expectedVersion);
    }

    Future<JsonObject> feedback(UUID id, JsonObject feedback, UUID by, int expectedVersion)
    {
        return updateVersioned("visits", """
                feedback_csat = $1, feedback_again = $2, feedback_source = $3, feedback_on = $4, feedback_quote = $5,
                feedback_by = $6
                """,
                Tuple.tuple()
                        .addValue(feedback.getNumber("csat").doubleValue())
                        .addValue(feedback.getBoolean("askAgain"))
                        .addValue(feedback.getString("source"))
                        .addValue(LocalDate.parse(feedback.getString("feedbackOn")))
                        .addValue(feedback.getString("quote"))
                        .addValue(by),
                id, expectedVersion);
    }

    /** Records the manager's decision ({@code confirmed} or {@code rejected}). */
    Future<JsonObject> decide(UUID id, String decision, UUID by, String note, int expectedVersion)
    {
        return updateVersioned("visits", "verification = $1, verified_by = $2, verified_at = now(), verify_note = $3",
                Tuple.of(decision, by, note), id, expectedVersion);
    }

    // ------------------------------------------------------------------ action center

    /** The person's past visits without a report. */
    Future<JsonArray> reportDue(UUID personId, LocalDate today)
    {
        return queryList("""
                SELECT v.id, v.person_id AS "personId", p.name AS "personName", v.customer, v.from_on AS "from",
                       v.to_on AS "to"
                FROM visits v JOIN people p ON p.id = v.person_id
                WHERE v.person_id = $1 AND v.reported_on IS NULL AND v.to_on < $2
                ORDER BY v.to_on
                """,
                Tuple.of(personId, today));
    }

    /** Reported visits of this account owner still waiting for customer feedback. */
    Future<JsonArray> feedbackDue(UUID ownerId)
    {
        return queryList("""
                SELECT v.id, v.person_id AS "personId", p.name AS "personName", v.customer, v.from_on AS "from",
                       v.to_on AS "to"
                FROM visits v JOIN people p ON p.id = v.person_id
                WHERE v.owner_id = $1 AND v.reported_on IS NOT NULL AND v.feedback_csat IS NULL
                ORDER BY v.reported_on
                """,
                Tuple.of(ownerId));
    }

    /** Visits of visible people with report and feedback whose points wait for a decision. */
    Future<JsonArray> awaitingDecision(Scope scope)
    {
        return scopedList(scope, "v.person_id", """
                SELECT v.id, v.person_id AS "personId", p.name AS "personName", v.customer, v.from_on AS "from",
                       v.to_on AS "to"
                FROM visits v JOIN people p ON p.id = v.person_id
                WHERE v.verification = 'pending' AND v.feedback_csat IS NOT NULL AND {scope}
                ORDER BY v.from_on
                """,
                Tuple.tuple());
    }

    // ------------------------------------------------------------------ CSV import (task 16.3)

    /** Users by lower-cased email: {@code [{id, email, name, status, personId}]}. */
    Future<JsonArray> usersByEmail(String[] emails)
    {
        return queryList("""
                SELECT u.id, lower(u.email) AS email, u.name, u.status, p.id AS "personId"
                FROM users u LEFT JOIN people p ON p.user_id = u.id
                WHERE lower(u.email) = ANY($1)
                """,
                Tuple.of((Object) emails));
    }

    /** Visits of these people that share a day with [from, to]: {@code [{id, personId, customer, from, to}]}. */
    Future<JsonArray> overlappingAny(UUID[] personIds, LocalDate from, LocalDate to)
    {
        return queryList("""
                SELECT id, person_id AS "personId", customer, from_on AS "from", to_on AS "to" FROM visits
                WHERE person_id = ANY($1) AND from_on <= $3 AND to_on >= $2
                """,
                Tuple.of(personIds, from, to));
    }

    /** Planned visits added by an import ({@code source = 'csv'}); resolves to {@code {id, version}}. */
    Future<JsonObject> insertImported(JsonObject visit, UUID createdBy)
    {
        return queryOne("""
                INSERT INTO visits (person_id, customer, city, purpose, from_on, to_on, requested_by, owner_id,
                                    reference, created_by, source)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, 'csv') RETURNING id, version
                """,
                Tuple.tuple()
                        .addValue(UUID.fromString(visit.getString("personId")))
                        .addValue(visit.getString("customer"))
                        .addValue(visit.getString("city"))
                        .addValue(visit.getString("purpose"))
                        .addValue(LocalDate.parse(visit.getString("from")))
                        .addValue(LocalDate.parse(visit.getString("to")))
                        .addValue(visit.getString("requestedBy"))
                        .addValue(UUID.fromString(visit.getString("ownerId")))
                        .addValue(visit.getString("reference"))
                        .addValue(createdBy));
    }

    /** Visible visits starting in [from, to] in the import layout, oldest first. */
    Future<JsonArray> exportRows(Scope scope, LocalDate from, LocalDate to)
    {
        return scopedList(scope, "v.person_id", """
                SELECT e.email AS employee_email, v.customer, v.city, v.purpose, v.from_on AS "from", v.to_on AS "to",
                       v.requested_by, o.email AS owner_email, coalesce(v.reference, '') AS reference
                FROM visits v JOIN people p ON p.id = v.person_id JOIN users e ON e.id = p.user_id
                JOIN users o ON o.id = v.owner_id
                WHERE v.from_on BETWEEN $1 AND $2 AND {scope}
                ORDER BY v.from_on, e.email
                """,
                Tuple.of(from, to));
    }
}
