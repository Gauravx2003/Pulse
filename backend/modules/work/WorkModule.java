package com.pulse.modules.work;

import com.pulse.platform.module.PulseModule;

/**
 * Track 2 work module (Overview and Work items). For now it only registers the Track 2 data layer:
 * {@code migrations/V200__work_data_layer.sql} creates the tables shared by every Track 2 module
 * (task 7.1). Routes, permissions and menu entries arrive with tasks 8.2 to 8.4.
 */
public class WorkModule implements PulseModule
{

    @Override
    public String id()
    {
        return "work";
    }
}
