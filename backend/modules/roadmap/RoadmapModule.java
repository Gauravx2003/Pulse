package com.pulse.modules.roadmap;

import com.pulse.platform.module.PulseModule;

/**
 * Track 2 road map module. For now it only registers {@code migrations/V220__roadmap.sql} and the
 * demo road map seed {@code seed/S220__demo_roadmap.sql} (task 15.1). Milestones, promotion readiness and
 * the skills matrix behind the policy service arrive with tasks 15.2 and 15.3.
 */
public class RoadmapModule implements PulseModule
{

    @Override
    public String id()
    {
        return "roadmap";
    }
}
