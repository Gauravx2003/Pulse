package com.pulse.modules.training;

import com.pulse.platform.audit.AuditService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The training plan library (spec growth/training-mentoring "Plan library", task 21.1): holders of
 * training.manage create, edit, duplicate and delete plans. An edit saves a new version and existing
 * assignments keep theirs; a delete hides the plan and leaves its assignments running.
 */
final class PlanService
{
    static final String CREATED = "training.plan_created";

    static final String UPDATED = "training.plan_updated";

    static final String DUPLICATED = "training.plan_duplicated";

    static final String DELETED = "training.plan_deleted";

    static final List<String> MODULE_TYPES = List.of("Course", "Reading", "Hands-on task", "Shadowing", "Meeting", "Assessment");

    /** The usual checkpoint days (spec); short plans use earlier days, so any day within the plan is accepted. */
    static final Set<Integer> USUAL_CHECKPOINT_DAYS = Set.of(14, 30, 60, 90);

    /** The 11 culture values a plan may build (reference spec section 12). */
    static final List<String> CULTURE_VALUES = List.of("Outcome Ownership", "Moral & Ethics", "Decision Making",
            "Problem Solving", "Risk Appetite", "Adaptability", "Agility in Execution", "Collaboration", "Resilience",
            "Resourcefulness", "Discipline");

    private final ModuleContext ctx;

    PlanService(ModuleContext ctx)
    {
        this.ctx = ctx;
    }

    Future<JsonArray> library()
    {
        return new PlanRepository(ctx.sql()).library();
    }

    /** A plan with its version history. */
    Future<JsonObject> get(UUID id)
    {
        PlanRepository repo = new PlanRepository(ctx.sql());

        return repo.plan(id).compose(plan -> plan == null
                ? Future.failedFuture(PolicyService.notFound())
                : repo.versions(id).map(versions -> plan.put("versions", versions)));
    }

    Future<JsonObject> create(JsonObject user, JsonObject body)
    {
        JsonObject plan;

        try
        {
            plan = normalize(body);
        }
        catch (ApiException e)
        {
            return Future.failedFuture(e);
        }

        UUID by = UUID.fromString(user.getString("id"));

        return ctx.sql().inTransaction(tx ->
        {
            PlanRepository repo = new PlanRepository(tx);

            return repo.insertPlan(by)
                    .compose(id -> repo.insertVersion(UUID.fromString(id), 1, plan, by)
                            .compose(n -> AuditService.record(tx, AuditService.entry(user, CREATED, "training_plan", id,
                                    new JsonObject().put("weeks", plan.getInteger("weeks")))))
                            .map(id));
        }).compose(id -> get(UUID.fromString(id)));
    }

    /** Saves the edit as the plan's next version; assignments stay on the version they started with. */
    Future<JsonObject> update(JsonObject user, UUID id, JsonObject body, int expectedVersion)
    {
        JsonObject plan;

        try
        {
            plan = normalize(body);
        }
        catch (ApiException e)
        {
            return Future.failedFuture(e);
        }

        UUID by = UUID.fromString(user.getString("id"));

        return get(id).compose(current -> ctx.sql().inTransaction(tx ->
        {
            PlanRepository repo = new PlanRepository(tx);

            return repo.bumpVersion(id, expectedVersion)
                    .compose(row -> repo.insertVersion(id, row.getInteger("current_version"), plan, by)
                            .compose(n -> AuditService.record(tx, AuditService.entry(user, UPDATED, "training_plan", id.toString(),
                                    new JsonObject().put("planVersion", row.getInteger("current_version"))))));
        })).compose(v -> get(id));
    }

    Future<JsonObject> duplicate(JsonObject user, UUID id)
    {
        return get(id).compose(source ->
        {
            String name = "Copy of " + source.getString("name");

            JsonObject copy = source.copy().put("name", name.length() > 120 ? name.substring(0, 120) : name);

            JsonArray checkpoints = new JsonArray();

            source.getJsonArray("checkpoints").forEach(day -> checkpoints.add(String.valueOf(day)));

            copy.put("checkpoints", checkpoints);

            return create(user, copy).compose(created -> AuditService.record(ctx.sql(), AuditService.entry(user, DUPLICATED,
                    "training_plan", created.getString("id"), new JsonObject().put("from", id.toString()))).map(created));
        });
    }

    /** Hides the plan from the library; its assignments keep running on their version. */
    Future<Void> delete(JsonObject user, UUID id, int expectedVersion)
    {
        return get(id).compose(plan -> ctx.sql().inTransaction(tx -> new PlanRepository(tx).softDelete(id, expectedVersion)
                .compose(row -> AuditService.record(tx, AuditService.entry(user, DELETED, "training_plan", id.toString(),
                        new JsonObject().put("activeAssignments", plan.getInteger("active")))))));
    }

    /**
     * Checks what the body schema cannot (values, checkpoint days, module weeks) and returns the plan
     * with checkpoints as numbers and every module carrying an id.
     */
    static JsonObject normalize(JsonObject body)
    {
        int weeks = body.getInteger("weeks");

        for (Object value : body.getJsonArray("values", new JsonArray()))
        {
            if (!CULTURE_VALUES.contains(value))
            {
                throw ApiException.badRequest("invalid_field", "“" + value + "” is not one of the 11 culture values.").field("values");
            }
        }

        JsonArray checkpoints = new JsonArray();

        for (Object day : body.getJsonArray("checkpoints", new JsonArray()))
        {
            Integer parsed = parseInt(String.valueOf(day));

            if (parsed == null || parsed < 1 || parsed > weeks * 7)
            {
                throw ApiException.badRequest("invalid_field", "A checkpoint is a day within the plan (1 to " + weeks * 7
                        + "), usually 14, 30, 60 or 90.").field("checkpoints");
            }

            if (!checkpoints.contains(parsed))
            {
                checkpoints.add(parsed);
            }
        }

        JsonArray modules = new JsonArray();

        Set<String> ids = new HashSet<>();

        for (Object item : body.getJsonArray("modules", new JsonArray()))
        {
            JsonObject module = ((JsonObject) item).copy();

            if (module.getInteger("week") > weeks)
            {
                throw ApiException.badRequest("invalid_field", "“" + module.getString("title") + "” is in week " + module.getInteger("week")
                        + ", but the plan has " + weeks + " weeks.").field("modules");
            }

            String moduleId = module.getString("id");

            if (moduleId == null || !ids.add(moduleId))
            {
                moduleId = "m" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

                ids.add(moduleId);
            }

            modules.add(module.put("id", moduleId).put("link", module.getString("link", "")));
        }

        return body.copy().put("checkpoints", checkpoints).put("modules", modules);
    }

    private static Integer parseInt(String value)
    {
        try
        {
            return Integer.valueOf(value.strip());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
