package com.pulse.modules.action_center;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import com.pulse.platform.policy.PolicyService;
import io.vertx.ext.web.Router;

/**
 * Track 2 Action center module (task 13.1, spec insight/action-center): the manager's landing page.
 * {@code GET /api/v1/action_center/items} is the Do today list of every module's
 * {@link com.pulse.platform.module.ActionProvider}; a module adds items by listing a provider in its
 * descriptor, with no change here. A manager view: {@code team.view} or {@code all.view}, so an
 * Employee gets 403 (the "You don't have access" card).
 */
public class ActionCenterModule implements PulseModule
{

    @Override
    public String id()
    {
        return "action_center";
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        PolicyService policy = ctx.policy();

        // GET /api/v1/action_center/items
        router.get("/items").handler(policy.requiresAny("team.view", "all.view")).handler(rc ->
                new ActionCenterService(ctx, ctx.registry().actionProviders())
                        .items(PolicyService.user(rc))
                        .onSuccess(rc::json)
                        .onFailure(rc::fail));
    }
}
