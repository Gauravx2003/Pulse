package com.pulse.modules.bugs;

import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.ExpectedVersion;
import com.pulse.platform.http.Ids;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Bugs & support under {@code /api/v1/bugs} (task 12.1), written to {@code docs/backend-guide.md}:
 * permission first, then the body schema; visibility, ownership and separation of duties in
 * {@link BugService}. The Team tab is for {@code team.view} and {@code all.view} only, so an Employee is
 * offered the Person tab alone.
 */
public final class BugsRoutes
{

    private static final String[] VIEW = {"self.view", "team.view", "all.view"};

    private static final String[] MANAGE = {"team.view", "all.view"};

    static final BodySchema DISPUTE = BodySchema.object()
            .string("reason", 1000)
            .required("reason");

    static final BodySchema DECIDE = BodySchema.object()
            .oneOf("decision", "keep", "remove")
            .integer("version", 1, Integer.MAX_VALUE)
            .required("decision");

    static final BodySchema RESOLVE_BUG = BodySchema.object()
            .number("hours", 0, 200);

    static final BodySchema RESOLVE_TICKET = BodySchema.object()
            .number("hours", 0, 200)
            .string("note", 2000)
            .required("hours", "note");

    static final BodySchema EMPTY = BodySchema.object();

    private final PolicyService policy;

    private final BugService bugs;

    BugsRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.bugs = new BugService(ctx);
    }

    void mount(Router router)
    {
        // GET /api/v1/bugs/people/:personId?period=m:2026-09
        router.get("/people/:personId").handler(policy.requiresAny(VIEW)).handler(this::person);

        // GET /api/v1/bugs/team?period=m:2026-09
        router.get("/team").handler(policy.requiresAny(MANAGE)).handler(this::team);

        // POST /api/v1/bugs/items/:key/dispute  {"reason": "…"}
        router.post("/items/:key/dispute").handler(policy.requiresAny(VIEW)).handler(DISPUTE.handler()).handler(this::dispute);

        // POST /api/v1/bugs/disputes/:id/decide  {"decision": "remove", "version": 1}
        router.post("/disputes/:id/decide").handler(policy.requiresAny(MANAGE)).handler(DECIDE.handler()).handler(this::decide);

        // POST /api/v1/bugs/items/:key/take | pick-up | resolve
        router.post("/items/:key/take").handler(policy.requiresAny(VIEW)).handler(EMPTY.handler())
                .handler(rc -> bugs.take(PolicyService.user(rc), rc.pathParam("key")).onSuccess(rc::json).onFailure(rc::fail));

        router.post("/items/:key/pick-up").handler(policy.requiresAny(VIEW)).handler(EMPTY.handler())
                .handler(rc -> bugs.pickUp(PolicyService.user(rc), rc.pathParam("key")).onSuccess(rc::json).onFailure(rc::fail));

        router.post("/items/:key/resolve").handler(policy.requiresAny(VIEW)).handler(RESOLVE_BUG.handler()).handler(this::resolve);

        // POST /api/v1/bugs/tickets/:key/take | resolve  {"hours": 2, "note": "…"}
        router.post("/tickets/:key/take").handler(policy.requiresAny(VIEW)).handler(EMPTY.handler())
                .handler(rc -> bugs.takeTicket(PolicyService.user(rc), rc.pathParam("key")).onSuccess(rc::json).onFailure(rc::fail));

        router.post("/tickets/:key/resolve").handler(policy.requiresAny(VIEW)).handler(RESOLVE_TICKET.handler())
                .handler(this::resolveTicket);
    }

    private void person(RoutingContext rc)
    {
        bugs.person(PolicyService.user(rc), Ids.path(rc, "personId").toString(), rc.queryParams().get("period"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void team(RoutingContext rc)
    {
        bugs.team(PolicyService.user(rc), rc.queryParams().get("period"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void dispute(RoutingContext rc)
    {
        bugs.dispute(PolicyService.user(rc), rc.pathParam("key"), BodySchema.body(rc).getString("reason"))
                .onSuccess(saved ->
                {
                    rc.response().setStatusCode(201);

                    rc.json(saved);
                })
                .onFailure(rc::fail);
    }

    private void decide(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        bugs.decide(PolicyService.user(rc), Ids.path(rc, "id"), body.getString("decision"), ExpectedVersion.of(rc, body),
                        policy.request(rc))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void resolve(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        Double hours = body.getValue("hours") instanceof Number n ? n.doubleValue() : null;

        bugs.resolve(PolicyService.user(rc), rc.pathParam("key"), hours)
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void resolveTicket(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        bugs.resolveTicket(PolicyService.user(rc), rc.pathParam("key"), body.getDouble("hours"), body.getString("note"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }
}
