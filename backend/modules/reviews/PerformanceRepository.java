package com.pulse.modules.reviews;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.util.UUID;

/** Performance review submissions (V346) and the people a calibration table lists. */
final class PerformanceRepository extends ScopedRepository
{
    PerformanceRepository(SqlExecutor sql)
    {
        super(sql);
    }

    Future<JsonObject> person(UUID id)
    {
        return queryOne("SELECT p.id, p.name, p.title, p.level, p.department FROM people p WHERE p.id = $1", Tuple.of(id));
    }

    /** People in scope, by name: {@code [{id, name, title, level}]}. */
    Future<JsonArray> people(Scope scope)
    {
        return scopedList(scope, "p.id", "SELECT p.id, p.name, p.title, p.level FROM people p WHERE {scope} ORDER BY p.name", Tuple.tuple());
    }

    /** The submission of a person for a quarter, or null: {@code {index, pillars, proposed, submittedAt, submittedBy}}. */
    Future<JsonObject> submission(UUID person, String quarter)
    {
        return queryOne("""
                SELECT r.index_score::float8 AS index, r.pillars, r.proposed, r.submitted_at AS "submittedAt", u.name AS "submittedBy"
                FROM performance_reviews r JOIN users u ON u.id = r.submitted_by
                WHERE r.person_id = $1 AND r.quarter = $2
                """,
                Tuple.of(person, quarter));
    }

    /** Submissions of a quarter by person id. */
    Future<JsonObject> submissions(String quarter)
    {
        return queryList("""
                SELECT r.person_id AS "personId", r.submitted_at AS "submittedAt", u.name AS "submittedBy"
                FROM performance_reviews r JOIN users u ON u.id = r.submitted_by WHERE r.quarter = $1
                """,
                Tuple.of(quarter)).map(rows ->
        {
            JsonObject out = new JsonObject();

            rows.forEach(o -> out.put(((JsonObject) o).getString("personId"), o));

            return out;
        });
    }

    /** Records a submission; resolves to false when the person was already submitted for the quarter. */
    Future<Boolean> submit(UUID person, String quarter, BigDecimal index, JsonObject pillars, String proposed, UUID by)
    {
        return execute("""
                INSERT INTO performance_reviews (person_id, quarter, index_score, pillars, proposed, submitted_by)
                VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT (person_id, quarter) DO NOTHING
                """,
                Tuple.of(person, quarter, index, pillars, proposed, by)).map(n -> n > 0);
    }
}
