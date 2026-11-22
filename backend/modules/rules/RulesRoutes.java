package com.pulse.modules.rules;

import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.ExpectedVersion;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Rules &amp; thresholds under {@code /api/v1/rules} (task 5.2), following docs/backend-guide.md.
 *
 * <pre>
 * GET  /groups                     every card the caller may edit, values, defaults, ranges, who changed it last
 * GET  /groups/:group              one card
 * PUT  /groups/:group              save values of one card: {values: {path: value}, version}
 * GET  /groups/:group/history      versions that changed the card: actor, time, changed values
 * GET  /reset                      what Reset defaults would change (the count shown in the confirmation)
 * POST /reset                      restore defaults: {version}
 * </pre>
 *
 * Holders of rules.manage see every card except appraisal standards; holders of appraisal.approve see
 * appraisal standards.
 */
final class RulesRoutes
{
    /** Values are checked per field by {@link RulesService} against the rule catalogue. */
    static final BodySchema SAVE = BodySchema.object()
            .anyObject("values", 200)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("values", "version");

    static final BodySchema RESET = BodySchema.object()
            .integer("version", 1, Integer.MAX_VALUE)
            .required("version");

    private final PolicyService policy;

    private final RulesService rules;

    RulesRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();
        this.rules = new RulesService(ctx);
    }

    void mount(Router router)
    {
        String[] editors = {"rules.manage", "appraisal.approve"};

        router.get("/groups").handler(policy.requiresAny(editors)).handler(this::overview);

        router.get("/groups/:group").handler(policy.requiresAny(editors)).handler(this::group);

        router.put("/groups/:group").handler(policy.requiresAny(editors)).handler(SAVE.handler()).handler(this::save);

        router.get("/groups/:group/history").handler(policy.requiresAny(editors)).handler(this::history);

        router.get("/reset").handler(policy.requiresAny(editors)).handler(this::resetPreview);

        router.post("/reset").handler(policy.requiresAny(editors)).handler(RESET.handler()).handler(this::reset);
    }

    private void overview(RoutingContext rc)
    {
        rules.overview(PolicyService.user(rc)).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void group(RoutingContext rc)
    {
        rules.group(PolicyService.user(rc), rc.pathParam("group")).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void save(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        rules.save(PolicyService.user(rc), rc.pathParam("group"), body.getJsonObject("values"),
                        ExpectedVersion.of(rc, body))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void history(RoutingContext rc)
    {
        rules.history(PolicyService.user(rc), rc.pathParam("group")).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void resetPreview(RoutingContext rc)
    {
        rules.resetPreview(PolicyService.user(rc)).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void reset(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        rules.reset(PolicyService.user(rc), ExpectedVersion.of(rc, body)).onSuccess(rc::json).onFailure(rc::fail);
    }
}
