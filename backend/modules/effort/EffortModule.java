package com.pulse.modules.effort;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

/**
 * Track 2 effort and extra-mile module (task group 11). Its table {@code extra_items} is part of the
 * Track 2 data layer ({@code work} module, V200); it seeds the prototype's extra-mile items in
 * {@code seed/S240__demo_extra_items.sql} (task 7.2). The formulas are in {@code calc/work} (task 11.1);
 * {@link EffortRoutes} confirms and rejects items (task 11.2).
 */
public class EffortModule implements PulseModule
{

    @Override
    public String id()
    {
        return "effort";
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new EffortRoutes(ctx).mount(router);
    }
}
