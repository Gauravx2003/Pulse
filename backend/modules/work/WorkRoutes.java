package com.pulse.modules.work;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Overview, Work items and the issue drawer under {@code /api/v1/work} (task 8.2), written to
 * {@code docs/backend-guide.md}: permission first, visibility from the policy service, failures through
 * {@code rc.fail}. Query parameters: {@code period} (a period key, default the active sprint), and for
 * work items {@code filter}, {@code q} and {@code sort}.
 */
public final class WorkRoutes
{

    private static final String[] VIEW = {"self.view", "team.view", "all.view"};

    private final PolicyService policy;

    private final WorkService work;

    WorkRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.work = new WorkService(ctx);
    }

    void mount(Router router)
    {
        // GET /api/v1/work/people/:personId/overview?period=s:S43
        router.get("/people/:personId/overview").handler(policy.requiresAny(VIEW)).handler(this::overview);

        // GET /api/v1/work/people/:personId/work-items?period=&filter=stale&q=snmp&sort=-signals
        router.get("/people/:personId/work-items").handler(policy.requiresAny(VIEW)).handler(this::workItems);

        // GET /api/v1/work/issues/:key
        router.get("/issues/:key").handler(policy.requiresAny(VIEW)).handler(this::issue);
    }

    private void overview(RoutingContext rc)
    {
        work.overview(PolicyService.user(rc), rc.pathParam("personId"), rc.queryParams().get("period"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void workItems(RoutingContext rc)
    {
        work.workItems(PolicyService.user(rc), rc.pathParam("personId"), rc.queryParams().get("period"),
                        rc.queryParams().get("filter"), rc.queryParams().get("q"), rc.queryParams().get("sort"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void issue(RoutingContext rc)
    {
        work.issue(PolicyService.user(rc), rc.pathParam("key"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }
}
