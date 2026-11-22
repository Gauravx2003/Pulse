package com.pulse.modules.pull_requests;

import com.pulse.platform.ingest.SeedMapper;
import com.pulse.platform.module.PulseModule;

import java.util.List;

/**
 * Track 2 pull requests module (task group 10). Its tables are part of the Track 2 data layer
 * ({@code work} module, V200); it seeds them from the prototype's PRs (task 7.2). The PR standard is in
 * {@code calc/work} (task 10.1); the API arrives with task 10.2.
 */
public class PullRequestsModule implements PulseModule
{

    @Override
    public String id()
    {
        return "pull_requests";
    }

    @Override
    public List<SeedMapper> seedMappers()
    {
        return PullRequestSeedMappers.all();
    }
}
