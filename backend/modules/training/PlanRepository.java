package com.pulse.modules.training;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/** The training plan library (V350): plans and their versions. Plans are not personal data. */
final class PlanRepository extends BaseRepository
{
    private static final String PLAN = """
            SELECT p.id, p.current_version AS "currentVersion", p.version, v.name, v.audience, v.weeks, v.description,
                   v.values_built AS "values", v.checkpoints, v.cadence, v.assessment_type AS "assessmentType",
                   v.pass_mark AS "passMark", v.modules,
                   (SELECT count(*) FROM training_assignments a WHERE a.plan_id = p.id)::int AS assignments,
                   (SELECT count(*) FROM training_assignments a WHERE a.plan_id = p.id AND a.outcome IS NULL)::int AS active
            FROM training_plans p
            JOIN training_plan_versions v ON v.plan_id = p.id AND v.plan_version = p.current_version
            """;

    PlanRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** The library: every plan not deleted, by name. */
    Future<JsonArray> library()
    {
        return queryList(PLAN + " WHERE p.deleted_at IS NULL ORDER BY v.name", Tuple.tuple());
    }

    /** One plan (its current version), or null when missing or deleted. */
    Future<JsonObject> plan(UUID id)
    {
        return queryOne(PLAN + " WHERE p.id = $1 AND p.deleted_at IS NULL", Tuple.of(id));
    }

    /** Every version of a plan, newest first: {@code [{planVersion, name, weeks, createdAt}]}. */
    Future<JsonArray> versions(UUID id)
    {
        return queryList("""
                SELECT plan_version AS "planVersion", name, weeks, created_at AS "createdAt"
                FROM training_plan_versions WHERE plan_id = $1 ORDER BY plan_version DESC
                """,
                Tuple.of(id));
    }

    Future<String> insertPlan(UUID createdBy)
    {
        return queryOne("INSERT INTO training_plans (created_by) VALUES ($1) RETURNING id", Tuple.of(createdBy))
                .map(row -> row.getString("id"));
    }

    Future<Integer> insertVersion(UUID planId, int planVersion, JsonObject plan, UUID createdBy)
    {
        return execute("""
                INSERT INTO training_plan_versions (plan_id, plan_version, name, audience, weeks, description, values_built,
                                                    checkpoints, cadence, assessment_type, pass_mark, modules, created_by)
                VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8::jsonb, $9, $10, $11, $12::jsonb, $13)
                """,
                Tuple.tuple()
                        .addValue(planId)
                        .addValue(planVersion)
                        .addValue(plan.getString("name"))
                        .addValue(plan.getString("audience", ""))
                        .addValue(plan.getInteger("weeks"))
                        .addValue(plan.getString("description", ""))
                        .addValue(plan.getJsonArray("values", new JsonArray()))
                        .addValue(plan.getJsonArray("checkpoints", new JsonArray()))
                        .addValue(plan.getString("cadence", "Weekly"))
                        .addValue(plan.getString("assessmentType", "Practical task"))
                        .addValue(plan.getInteger("passMark", 70))
                        .addValue(plan.getJsonArray("modules", new JsonArray()))
                        .addValue(createdBy));
    }

    /** Moves the plan to its next version under optimistic locking; resolves to the updated plan row. */
    Future<JsonObject> bumpVersion(UUID planId, int expectedVersion)
    {
        return updateVersioned("training_plans", "current_version = current_version + 1", Tuple.tuple(), planId, expectedVersion);
    }

    Future<JsonObject> softDelete(UUID planId, int expectedVersion)
    {
        return updateVersioned("training_plans", "deleted_at = now()", Tuple.tuple(), planId, expectedVersion);
    }
}
