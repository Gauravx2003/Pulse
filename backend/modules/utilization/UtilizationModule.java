package com.pulse.modules.utilization;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

/**
 * Track 2 Team utilization module (task group 9): the manager's team workload view, KPIs, heatmap,
 * people table and rebalance suggestion ({@link UtilizationRoutes}). It reads the Track 2 data layer
 * through the {@code work.team*} formulas and has no tables of its own.
 */
public class UtilizationModule implements PulseModule
{

    @Override
    public String id()
    {
        return "utilization";
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new UtilizationRoutes(ctx).mount(router);
    }
}
