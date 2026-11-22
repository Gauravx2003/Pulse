package com.pulse.modules.progress;

import com.pulse.platform.module.PulseModule;

/**
 * Track 2 Progress and weekly pulse module (task group 13). Its table {@code weekly_pulse} is part of the
 * Track 2 data layer ({@code work} module, V200); it seeds the prototype's pulse answers in
 * {@code seed/S230__demo_weekly_pulse.sql} (task 7.2). Progress and the pulse API arrive with task 13.3.
 */
public class ProgressModule implements PulseModule
{

    @Override
    public String id()
    {
        return "progress";
    }
}
