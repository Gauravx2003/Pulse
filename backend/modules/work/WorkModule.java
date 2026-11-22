package com.pulse.modules.work;

import com.pulse.platform.ingest.SeedMapper;
import com.pulse.platform.module.PulseModule;

import java.util.List;

/**
 * Track 2 work module (Overview and Work items). It registers the Track 2 data layer:
 * {@code migrations/V200__work_data_layer.sql} creates the tables shared by every Track 2 module
 * (task 7.1), and {@link WorkSeedMappers} seeds the Jira and GitHub work data (task 7.2). Routes,
 * permissions and menu entries arrive with tasks 8.2 to 8.4.
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
}
