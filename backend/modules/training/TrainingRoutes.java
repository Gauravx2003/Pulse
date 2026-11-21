package com.pulse.modules.training;

import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.ExpectedVersion;
import com.pulse.platform.http.Ids;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Training and mentoring under {@code /api/v1/training} (task 21.1), following docs/backend-guide.md.
 *
 * <pre>
 * GET    /plans                  the plan library (training.manage)
 * POST   /plans                  create
 * GET    /plans/:id              one plan with its versions
 * PUT    /plans/:id              edit: saves a new version
 * POST   /plans/:id/duplicate    copy as a new plan
 * DELETE /plans/:id              hide from the library (If-Match: version); assignments keep running
 * </pre>
 *
 * <p>Checkpoint days travel as text ({@code ["14", "30"]}) because the body schema has no list of
 * whole numbers; responses carry them as numbers.
 */
public final class TrainingRoutes
{
    static final BodySchema MODULE = BodySchema.object()
            .string("id", 40)
            .string("title", 120)
            .oneOf("type", PlanService.MODULE_TYPES.toArray(new String[0]))
            .integer("week", 1, 52)
            .number("hours", 0, 200)
            .string("link", 300)
            .required("title", "type", "week", "hours");

    static final BodySchema PLAN = BodySchema.object()
            .string("name", 120)
            .string("audience", 120)
            .integer("weeks", 1, 52)
            .string("description", 1000)
            .stringList("values", 11, 40)
            .stringList("checkpoints", 8, 3)
            .oneOf("cadence", "Daily", "Weekly", "Monthly")
            .string("assessmentType", 60)
            .integer("passMark", 0, 100)
            .objectList("modules", 40, MODULE)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("name", "weeks");

    private final PolicyService policy;

    private final PlanService plans;

    TrainingRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.plans = new PlanService(ctx);
    }

    void mount(Router router)
    {
        router.get("/plans").handler(policy.requires("training.manage"))
                .handler(rc -> plans.library().onSuccess(items -> rc.json(new JsonObject().put("items", items))).onFailure(rc::fail));

        router.post("/plans").handler(policy.requires("training.manage")).handler(PLAN.handler())
                .handler(rc -> plans.create(PolicyService.user(rc), BodySchema.body(rc)).onSuccess(plan -> created(rc, plan)).onFailure(rc::fail));

        router.get("/plans/:id").handler(policy.requires("training.manage"))
                .handler(rc -> plans.get(Ids.path(rc, "id")).onSuccess(rc::json).onFailure(rc::fail));

        router.put("/plans/:id").handler(policy.requires("training.manage")).handler(PLAN.handler())
                .handler(this::update);

        router.post("/plans/:id/duplicate").handler(policy.requires("training.manage"))
                .handler(rc -> plans.duplicate(PolicyService.user(rc), Ids.path(rc, "id")).onSuccess(plan -> created(rc, plan)).onFailure(rc::fail));

        router.delete("/plans/:id").handler(policy.requires("training.manage"))
                .handler(rc -> plans.delete(PolicyService.user(rc), Ids.path(rc, "id"), ExpectedVersion.of(rc, null))
                        .onSuccess(v -> rc.response().setStatusCode(204).end()).onFailure(rc::fail));
    }

    private void update(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        plans.update(PolicyService.user(rc), Ids.path(rc, "id"), body, ExpectedVersion.of(rc, body))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private static void created(RoutingContext rc, JsonObject plan)
    {
        rc.response().setStatusCode(201);

        rc.json(plan);
    }
}
