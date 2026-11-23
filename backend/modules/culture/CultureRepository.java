package com.pulse.modules.culture;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Culture value definitions (V371/S370), Discipline overrides (V370) and the evidence the views list. */
final class CultureRepository extends ScopedRepository
{
    CultureRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** The 11 values in order: {@code [{name, ord, description, signals, anchors}]}. */
    Future<JsonArray> values()
    {
        return queryList("SELECT name, ord, description, signals, anchors FROM culture_values ORDER BY ord", Tuple.tuple());
    }

    /** People in scope (for the team average), by name. */
    Future<JsonArray> people(Scope scope)
    {
        return scopedList(scope, "p.id", "SELECT p.id, p.name FROM people p WHERE {scope} ORDER BY p.name", Tuple.tuple());
    }

    Future<JsonObject> person(UUID id)
    {
        return queryOne("SELECT p.id, p.name, p.title, p.user_id AS \"userId\" FROM people p WHERE p.id = $1", Tuple.of(id));
    }

    /** Kudos the person received between two days, newest first, when the reviews tables are installed. */
    Future<JsonArray> kudos(UUID person, LocalDate from, LocalDate to)
    {
        return queryOne("SELECT to_regclass('kudos') IS NOT NULL AS installed", Tuple.tuple()).compose(t -> !t.getBoolean("installed")
                ? Future.succeededFuture(new JsonArray())
                : queryList("""
                        SELECT k.id, k.culture_value AS value, k.message, k.given_at AS "givenAt", coalesce(u.name, 'Slack') AS "fromName", k.source
                        FROM kudos k LEFT JOIN users u ON u.id = k.from_user_id
                        WHERE k.person_id = $1 AND (k.given_at AT TIME ZONE 'Asia/Kolkata')::date BETWEEN $2 AND $3
                        ORDER BY k.given_at DESC
                        """,
                        Tuple.of(person, from, to)));
    }

    /** Confirmed extra-mile items of the person between two days that credit a value, when the work tables are installed. */
    Future<JsonArray> extraItems(UUID person, LocalDate from, LocalDate to)
    {
        return queryOne("SELECT to_regclass('extra_items') IS NOT NULL AS installed", Tuple.tuple()).compose(t -> !t.getBoolean("installed")
                ? Future.succeededFuture(new JsonArray())
                : queryList("""
                        SELECT x.id, x.title, x.occurred_on AS "occurredOn", to_jsonb(x.values_credited) AS values, x.evidence_source AS source
                        FROM extra_items x
                        WHERE x.person_id = $1 AND x.state = 'confirmed' AND x.occurred_on BETWEEN $2 AND $3
                          AND cardinality(x.values_credited) > 0
                        ORDER BY x.occurred_on DESC, x.id
                        """,
                        Tuple.of(person, from, to)));
    }

    /** The current (uncleared) override of the person, or null: {@code {id, score, reason, setAt, setBy}}. */
    Future<JsonObject> override(UUID person)
    {
        return queryOne("""
                SELECT o.id, o.score::float8 AS score, o.reason, o.set_at AS "setAt", u.name AS "setBy"
                FROM discipline_overrides o JOIN users u ON u.id = o.set_by
                WHERE o.person_id = $1 AND o.cleared_at IS NULL ORDER BY o.set_at DESC LIMIT 1
                """,
                Tuple.of(person));
    }

    /** Clears the current override (if any) and records a new one. */
    Future<JsonObject> setOverride(UUID person, BigDecimal score, String reason, UUID by)
    {
        return clearOverride(person, by).compose(n -> queryOne("""
                INSERT INTO discipline_overrides (person_id, score, reason, set_by) VALUES ($1, $2, $3, $4) RETURNING id
                """,
                Tuple.of(person, score, reason, by)));
    }

    /** Returns the person to the automatic score; resolves to the rows cleared (0 or 1). */
    Future<Integer> clearOverride(UUID person, UUID by)
    {
        return execute("""
                UPDATE discipline_overrides SET cleared_at = now(), cleared_by = $2, version = version + 1
                WHERE person_id = $1 AND cleared_at IS NULL
                """,
                Tuple.of(person, by));
    }
}
