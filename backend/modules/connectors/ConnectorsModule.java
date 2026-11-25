package com.pulse.modules.connectors;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;

/**
 * Live, read-only connectors (Track 3, change pulse-v2-live-connectors design.md decision 1): one
 * package per tool, {@code jira/} first, then {@code github/}. Each connector implements
 * {@link com.pulse.platform.live.LiveConnector}, reaches the network only through
 * {@code LiveContext.http()} and hands normalized batches to the platform, which writes them through
 * {@code IngestService}. This descriptor is the only place they are registered; platform code never
 * imports them. No routes, migrations or tables of its own ({@code V390–V399} stays reserved).
 */
public class ConnectorsModule implements PulseModule
{
    @Override
    public String id()
    {
        return "connectors";
    }

    /** Registers the live connectors, e.g. {@code ctx.connectors().register(new JiraConnector());}. */
    @Override
    public void start(ModuleContext ctx)
    {
        // task 26.2 registers the Jira connector here, task 26.5 the GitHub connector
    }
}
