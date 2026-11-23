package com.pulse.modules.roadmap;

import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

import java.util.List;
import java.util.Map;

/**
 * Track 2 road map module (spec growth/roadmap). It registers {@code migrations/V220__roadmap.sql} and the
 * demo road map seed {@code seed/S220__demo_roadmap.sql} (task 15.1); {@link RoadmapRoutes} serves the
 * road map, milestones, promotion readiness and the skills matrix (task 15.2).
 */
public class RoadmapModule implements PulseModule
{

    @Override
    public String id()
    {
        return "roadmap";
    }

    @Override
    public List<String> auditEvents()
    {
        return RoadmapService.AUDIT_EVENTS;
    }

    @Override
    public Map<String, String> selfRefusedActions()
    {
        return Map.of(
                RoadmapService.CONFIRM_ACTION, "You can't confirm your own road map milestone.",
                RoadmapService.RATE_ACTION, "You can't rate your own skills.");
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new RoadmapRoutes(ctx).mount(router);
    }

    @Override
    public List<ActionProvider> actionProviders()
    {
        return List.of(new MilestoneActionProvider());
    }
}
