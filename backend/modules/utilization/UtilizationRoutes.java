package com.pulse.modules.utilization;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Team utilization under {@code /api/v1/utilization} (task 9.1), written to {@code docs/backend-guide.md}:
 * a manager view, so {@code team.view} or {@code all.view}; an Employee gets 403 (the no-access card).
 */
public final class UtilizationRoutes
{

    private final PolicyService policy;

    private final UtilizationService utilization;

    UtilizationRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.utilization = new UtilizationService(ctx);
    }

    void mount(Router router)
    {
        // GET /api/v1/utilization/team?period=s:S43
        router.get("/team").handler(policy.requiresAny("team.view", "all.view")).handler(this::team);
    }

    private void team(RoutingContext rc)
    {
        utilization.team(PolicyService.user(rc), rc.queryParams().get("period"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }
}
