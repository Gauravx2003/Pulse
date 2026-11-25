package com.pulse.platform.live;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;

/**
 * The live connectors registered by modules (task 24.3), one per tool. Reach it as
 * {@code ModuleContext.connectors()}; modules register from
 * {@link com.pulse.platform.module.PulseModule#start}. Tools without a live connector keep the Phase 1
 * seeded path.
 */
public final class LiveConnectorRegistry
{
    private static final Pattern TOOL = Pattern.compile("[a-z0-9_-]{1,40}");

    private final Map<String, LiveConnector> byTool = new ConcurrentSkipListMap<>();

    /**
     * Registers {@code connector} for its tool. Fails when the tool id is malformed or another
     * connector already holds the tool, so two modules can never silently replace each other.
     */
    public LiveConnectorRegistry register(LiveConnector connector)
    {
        String tool = connector == null ? null : connector.tool();

        if (tool == null || !TOOL.matcher(tool).matches())
        {
            throw new IllegalArgumentException("Live connector tool id must match " + TOOL + ": " + tool);
        }

        if (connector.source() == null || connector.source().isBlank() || "seed".equals(connector.source()))
        {
            throw new IllegalArgumentException("Live connector '" + tool + "' needs its own source, not seed");
        }

        LiveConnector earlier = byTool.putIfAbsent(tool, connector);

        if (earlier != null && earlier != connector)
        {
            throw new IllegalStateException("A live connector is already registered for '" + tool + "': "
                    + earlier.getClass().getName());
        }

        return this;
    }

    /** The live connector of {@code tool}, or null when the tool is seeded. */
    public LiveConnector get(String tool)
    {
        return tool == null ? null : byTool.get(tool);
    }

    public boolean has(String tool)
    {
        return get(tool) != null;
    }

    /** Tool ids with a live connector, sorted. */
    public List<String> tools()
    {
        return List.copyOf(byTool.keySet());
    }

    /** {@code [{tool, source, configSchema, secretSpec, requiredScopes}]}, sorted by tool. */
    public JsonArray list()
    {
        JsonArray out = new JsonArray();

        byTool.values().forEach(c -> out.add(new JsonObject()
                .put("tool", c.tool())
                .put("source", c.source())
                .put("configSchema", c.configSchema())
                .put("secretSpec", c.secretSpec())
                .put("requiredScopes", c.requiredScopes())));

        return out;
    }
}
