package com.pulse.modules.work;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Overview, Work items, the issue drawer (task 8.2) and its Nudge action (task 8.3) under
 * {@code /api/v1/work}, written to {@code docs/backend-guide.md}: permission first, visibility from the
 * policy service, failures through {@code rc.fail}. Query parameters: {@code period} (a period key, default the active sprint), and for
 * work items {@code filter}, {@code source}, {@code q} and {@code sort}.
 */
public final class WorkRoutes
{

    private static final String[] VIEW = {"self.view", "team.view", "all.view"};

    /**
     * Nudges are for managers ("Let managers DM a nudge for stale issues"); the issue must also be visible.
     */
    private static final String[] NUDGE = {"team.view", "all.view"};

    private final PolicyService policy;

    private final WorkService work;

    private final NudgeService nudges;

    WorkRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.nudges = new NudgeService(ctx);

        this.work = new WorkService(ctx, nudges);
    }

    void mount(Router router)
    {
        // GET /api/v1/work/people/:personId/overview?period=s:S43
        router.get("/people/:personId/overview").handler(policy.requiresAny(VIEW)).handler(this::overview);

        // GET /api/v1/work/people/:personId/work-items?period=&filter=stale&source=jira&q=snmp&sort=-signals
        router.get("/people/:personId/work-items").handler(policy.requiresAny(VIEW)).handler(this::workItems);

        // GET /api/v1/work/issues/:key
        router.get("/issues/:key").handler(policy.requiresAny(VIEW)).handler(this::issue);

        // POST /api/v1/work/issues/:key/nudge
        router.post("/issues/:key/nudge").handler(policy.requiresAny(NUDGE)).handler(this::nudge);
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
                        rc.queryParams().get("filter"), rc.queryParams().get("source"), rc.queryParams().get("q"),
                        rc.queryParams().get("sort"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void issue(RoutingContext rc)
    {
        work.issue(PolicyService.user(rc), rc.pathParam("key"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void nudge(RoutingContext rc)
    {
        nudges.nudge(PolicyService.user(rc), rc.pathParam("key"))
                .onSuccess(sent ->
                {
                    rc.response().setStatusCode(201);

                    rc.json(sent);
                })
                .onFailure(rc::fail);
    }
}
