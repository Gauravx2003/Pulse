package com.pulse.modules.work;

import com.pulse.platform.ingest.SeedMapper;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

import java.util.List;

/**
 * Track 2 work module (Overview and Work items). It registers the Track 2 data layer:
 * {@code migrations/V200__work_data_layer.sql} creates the tables shared by every Track 2 module
 * (task 7.1), {@link WorkSeedMappers} seeds the Jira and GitHub work data (task 7.2), and
 * {@link WorkRoutes} serves Overview, Work items and the issue drawer (task 8.2).
 */
public class WorkModule implements PulseModule
{

    @Override
    public String id()
    {
        return "work";
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
}
