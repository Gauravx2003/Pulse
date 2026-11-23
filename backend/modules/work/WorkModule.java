package com.pulse.modules.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.platform.ingest.SeedMapper;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.JobSpec;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import java.util.List;

/**
 * Track 2 work module (Overview and Work items). It registers the Track 2 data layer:
 * {@code migrations/V200__work_data_layer.sql} creates the tables shared by every Track 2 module
 * (task 7.1), {@link WorkSeedMappers} seeds the Jira and GitHub work data (task 7.2), and
 * {@link WorkRoutes} serves Overview, Work items and the issue drawer (task 8.2) with its Nudge action
 * (task 8.3, {@code migrations/V230__nudges.sql}).
 */
public class WorkModule implements PulseModule
{

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<String> auditEvents()
    {
        return NudgeService.AUDIT_EVENTS;
    }

    @Override
    public List<SeedMapper> seedMappers()
    {
        return WorkSeedMappers.all();
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new WorkRoutes(ctx).mount(router);
    }

    /**
     * Daily stale-signal refresh (task 5.5): "stale" and "not started" depend on today, so the cached work
     * results are dropped each morning and recomputed on the next read. Counts: {@code {invalidated}}.
     * TODO(Track 2): extend if the stale signals need more than a cache refresh (owner: Track 2).
     */
    @Override
    public List<JobSpec> jobs()
    {
        return List.of(JobSpec.of("work.stale-signal-refresh", "0 6 * * *",
                "Recomputes the stale and not-started work signals for the new day", ctx ->
                {
                    CalcService.publish(ctx.vertx(), WorkloadFormulas.ISSUES);

                    return Future.succeededFuture(new JsonObject().put("invalidated", WorkloadFormulas.ISSUES));
                }));
    }

    @Override
    public List<ActionProvider> actionProviders()
    {
        return WorkloadActionProvider.all();
    }
}
