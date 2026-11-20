package com.pulse.modules.okrs;

import com.pulse.platform.module.PulseModule;

import java.util.List;

/**
 * Track 2 OKRs module: schema {@code migrations/V210__okrs.sql}, demo seed
 * {@code seed/S210__demo_okrs.sql} (task 14.1) and {@link OkrService} (task 14.2). The HTTP routes are
 * mounted once the session user, the policy service and the central FailureHandler exist (tasks 3.x);
 * progress and the OKR pillar come from CalcService (task 4.10).
 */
public class OkrsModule implements PulseModule
{

    @Override
    public String id()
    {
        return "okrs";
    }

    @Override
    public List<String> permissions()
    {
        return List.of(OkrService.OKR_OWN, OkrService.OKR_TEAM);
    }
}
