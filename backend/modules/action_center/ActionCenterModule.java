package com.pulse.modules.action_center;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import java.util.function.Function;

/**
 * Track 2 Action center module (task 13.1, spec insight/action-center): the manager's landing page.
 * {@code GET /api/v1/action_center/items} is the Do today list of every module's
 * {@link com.pulse.platform.module.ActionProvider}; a module adds items by listing a provider in its
 * descriptor, with no change here. The insight sections (task 13.2) are {@code GET /signals},
 * {@code /forecast}, {@code /capacity}, {@code /coverage}, {@code /pulse}, {@code /wins} and
 * {@code /digest} ({@link ActionCenterInsights}). A manager view: {@code team.view} or {@code all.view},
 * so an Employee gets 403 (the "You don't have access" card).
 */
public class ActionCenterModule implements PulseModule
{

    private static final String[] MANAGER = {"team.view", "all.view"};

    @Override
    public String id()
    {
        return "action_center";
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        PolicyService policy = ctx.policy();

        ActionCenterInsights insights = new ActionCenterInsights(ctx);

        // GET /api/v1/action_center/items
        router.get("/items").handler(policy.requiresAny(MANAGER)).handler(rc ->
                items(ctx, PolicyService.user(rc))
                        .onSuccess(rc::json)
                        .onFailure(rc::fail));

        // GET /api/v1/action_center/signals
        section(router, policy, "/signals", insights::signals);

        // GET /api/v1/action_center/forecast
        section(router, policy, "/forecast", insights::forecast);

        // GET /api/v1/action_center/capacity
        section(router, policy, "/capacity", insights::capacity);

        // GET /api/v1/action_center/coverage
        section(router, policy, "/coverage", insights::coverage);

        // GET /api/v1/action_center/pulse
        section(router, policy, "/pulse", insights::pulse);

        // GET /api/v1/action_center/wins
        section(router, policy, "/wins", insights::wins);

        // GET /api/v1/action_center/digest
        section(router, policy, "/digest", user -> insights.digest(user, items(ctx, user)));
    }

    private static Future<JsonObject> items(ModuleContext ctx, JsonObject user)
    {
        return new ActionCenterService(ctx, ctx.registry().actionProviders()).items(user);
    }

    private static void section(Router router, PolicyService policy, String path, Function<JsonObject, Future<JsonObject>> read)
    {
        router.get(path).handler(policy.requiresAny(MANAGER)).handler(rc ->
                read.apply(PolicyService.user(rc))
                        .onSuccess(rc::json)
                        .onFailure(rc::fail));
    }
}
