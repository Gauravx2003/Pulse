package com.pulse.modules.pull_requests;

import com.pulse.platform.http.Ids;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Pull requests under {@code /api/v1/pull_requests} (task 10.2), written to {@code docs/backend-guide.md}:
 * permission first, visibility from the policy service. The Team tab is for {@code team.view} and
 * {@code all.view}; every user may read the standard (Admins change it in Rules &amp; thresholds).
 */
public final class PullRequestsRoutes
{

    private static final String[] VIEW = {"self.view", "team.view", "all.view"};

    private final PolicyService policy;

    private final PullRequestService prs;

    PullRequestsRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.prs = new PullRequestService(ctx);
    }

    void mount(Router router)
    {
        // GET /api/v1/pull_requests/people/:personId?period=m:2026-09 (or all, or a sprint)
        router.get("/people/:personId").handler(policy.requiresAny(VIEW)).handler(this::person);

        // GET /api/v1/pull_requests/team?period=s:S43&team=Platform
        router.get("/team").handler(policy.requiresAny("team.view", "all.view")).handler(this::team);

        // GET /api/v1/pull_requests/prs/:id
        router.get("/prs/:id").handler(policy.requiresAny(VIEW)).handler(this::detail);

        // GET /api/v1/pull_requests/standard
        router.get("/standard").handler(policy.requiresAny(VIEW)).handler(rc -> prs.standard().onSuccess(rc::json).onFailure(rc::fail));
    }

    private void person(RoutingContext rc)
    {
        prs.person(PolicyService.user(rc), Ids.path(rc, "personId").toString(), rc.queryParams().get("period"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void team(RoutingContext rc)
    {
        prs.team(PolicyService.user(rc), rc.queryParams().get("period"), rc.queryParams().get("team"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void detail(RoutingContext rc)
    {
        prs.detail(PolicyService.user(rc), Ids.path(rc, "id"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }
}
