package com.pulse.modules.roadmap;

import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.ExpectedVersion;
import com.pulse.platform.http.Ids;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * The road map under {@code /api/v1/roadmap} (task 15.2), written to {@code docs/backend-guide.md}:
 * permission first, then the body schema; visibility and separation of duties in {@link RoadmapService}.
 * Confirming milestones and rating skills are for managers ({@code team.view} or {@code all.view}).
 */
public final class RoadmapRoutes
{

    private static final String[] VIEW = {"self.view", "team.view", "all.view"};

    private static final String[] MANAGE = {"team.view", "all.view"};

    static final BodySchema PROPOSE = BodySchema.object()
            .string("title", 200)
            .string("when", 40)
            .string("value", 60)
            .required("title", "when");

    static final BodySchema CONFIRM = BodySchema.object()
            .integer("version", 1, Integer.MAX_VALUE);

    static final BodySchema RATE = BodySchema.object()
            .string("skill", 80)
            .integer("current", 0, 5)
            .integer("needed", 1, 5)
            .required("skill", "current", "needed");

    private final PolicyService policy;

    private final RoadmapService roadmap;

    RoadmapRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.roadmap = new RoadmapService(ctx);
    }

    void mount(Router router)
    {
        // GET /api/v1/roadmap/people/:personId
        router.get("/people/:personId").handler(policy.requiresAny(VIEW)).handler(this::get);

        // POST /api/v1/roadmap/people/:personId/milestones  {"title": "…", "when": "Q1 2027", "value": "Collaboration"}
        router.post("/people/:personId/milestones").handler(policy.requiresAny(VIEW)).handler(PROPOSE.handler()).handler(this::propose);

        // POST /api/v1/roadmap/milestones/:id/confirm  {"version": 1}
        router.post("/milestones/:id/confirm").handler(policy.requiresAny(MANAGE)).handler(CONFIRM.handler()).handler(this::confirm);

        // PUT /api/v1/roadmap/people/:personId/skills  {"skill": "Mentoring", "current": 2, "needed": 4}
        router.put("/people/:personId/skills").handler(policy.requiresAny(MANAGE)).handler(RATE.handler()).handler(this::rate);

        // DELETE /api/v1/roadmap/people/:personId/skills/:id
        router.delete("/people/:personId/skills/:id").handler(policy.requiresAny(MANAGE)).handler(this::removeSkill);
    }

    private void get(RoutingContext rc)
    {
        roadmap.roadmap(PolicyService.user(rc), Ids.path(rc, "personId").toString())
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void propose(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        roadmap.propose(PolicyService.user(rc), Ids.path(rc, "personId").toString(), body.getString("title"), body.getString("when"),
                        body.getString("value"))
                .onSuccess(saved ->
                {
                    rc.response().setStatusCode(201);

                    rc.json(saved);
                })
                .onFailure(rc::fail);
    }

    private void confirm(RoutingContext rc)
    {
        roadmap.confirm(PolicyService.user(rc), Ids.path(rc, "id"), ExpectedVersion.of(rc, BodySchema.body(rc)), policy.request(rc))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void rate(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        roadmap.rate(PolicyService.user(rc), Ids.path(rc, "personId").toString(), body.getString("skill"), body.getInteger("current"),
                        body.getInteger("needed"), policy.request(rc))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void removeSkill(RoutingContext rc)
    {
        roadmap.removeSkill(PolicyService.user(rc), Ids.path(rc, "personId").toString(), Ids.path(rc, "id"), policy.request(rc))
                .onSuccess(v -> rc.response().setStatusCode(204).end())
                .onFailure(rc::fail);
    }
}
