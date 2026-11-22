package com.pulse.platform.ingest;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which connector Sync now runs for a tool: a registered live connector when there is one (group 24
 * registers GitHub and Jira here), otherwise the {@link SeedConnector} restricted to that tool.
 */
public final class ConnectorRegistry
{
    private final SeedConnector seed;

    private final Map<String, ConnectorPort> registered = new ConcurrentHashMap<>();

    public ConnectorRegistry(SeedConnector seed)
    {
        this.seed = Objects.requireNonNull(seed, "seed");
    }

    /** Registers {@code connector} for its {@link ConnectorPort#tool()}; replaces an earlier one. */
    public ConnectorRegistry register(ConnectorPort connector)
    {
        registered.put(connector.tool(), connector);

        return this;
    }

    /** The connector Sync now runs for {@code tool}. */
    public ConnectorPort forTool(String tool)
    {
        ConnectorPort connector = registered.get(tool);

        return connector != null ? connector : seed.forTool(tool);
    }

    /** True when a connector other than the seed is registered for {@code tool}. */
    public boolean registered(String tool)
    {
        return registered.containsKey(tool);
    }

    public SeedConnector seed()
    {
        return seed;
    }
}
