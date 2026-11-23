package com.pulse.modules.effort;

import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.ExpectedVersion;
import com.pulse.platform.http.Ids;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.List;
import java.util.UUID;

/**
 * Confirm and reject of extra-mile items under {@code /api/v1/effort} (task 11.2), written to
 * {@code docs/backend-guide.md}: {@code effort.confirm} first, then the body schema, visibility and
 * separation of duties in {@link EffortService}. A single decision carries the item's {@code version}
 * (body or {@code If-Match}).
 */
public final class EffortRoutes
{

    static final int MAX_CONFIRM_ALL = 500;

    static final BodySchema DECIDE = BodySchema.object()
            .integer("version", 1, Integer.MAX_VALUE);

    static final BodySchema CONFIRM_ALL = BodySchema.object()
            .uuidList("ids", MAX_CONFIRM_ALL)
            .required("ids");

    private final PolicyService policy;

    private final EffortService effort;

    EffortRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.effort = new EffortService(ctx);
    }

    void mount(Router router)
    {
        // POST /api/v1/effort/items/:id/confirm  {"version": 1}
        router.post("/items/:id/confirm").handler(policy.requires("effort.confirm")).handler(DECIDE.handler())
                .handler(rc -> decide(rc, EffortService.CONFIRMED));

        // POST /api/v1/effort/items/:id/reject  {"version": 1}
        router.post("/items/:id/reject").handler(policy.requires("effort.confirm")).handler(DECIDE.handler())
                .handler(rc -> decide(rc, EffortService.REJECTED));

        // POST /api/v1/effort/items/confirm-all  {"ids": ["…", "…"]}
        router.post("/items/confirm-all").handler(policy.requires("effort.confirm")).handler(CONFIRM_ALL.handler())
                .handler(this::confirmAll);
    }

    private void decide(RoutingContext rc, String decision)
    {
        JsonObject body = BodySchema.body(rc);

        effort.decide(PolicyService.user(rc), Ids.path(rc, "id"), decision, ExpectedVersion.of(rc, body), policy.request(rc))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void confirmAll(RoutingContext rc)
    {
        List<UUID> ids = BodySchema.body(rc).getJsonArray("ids").stream().map(id -> UUID.fromString((String) id)).toList();

        effort.confirmAll(PolicyService.user(rc), ids)
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }
}
