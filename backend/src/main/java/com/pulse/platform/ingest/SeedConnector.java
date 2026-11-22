package com.pulse.platform.ingest;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The first connector (design.md decision 6): reads the extracted seed JSON through the registered
 * {@link SeedMapper}s and returns it as one normalized batch with {@code source = 'seed'}. It runs at
 * start-up while {@code PULSE_DEMO_SEED=true} ({@link SeedLoader}) and is what Sync now runs for every
 * tool that has no live connector, so a seeded Sync now re-reads the seed and makes no network call.
 *
 * <p>The seed has no change history: {@code since} is ignored and every call returns the full data
 * set, which {@link IngestService} applies idempotently.
 */
public final class SeedConnector implements ConnectorPort
{
    public static final String SOURCE = "seed";

    /** {@link #tool()} of the connector over every mapper (start-up load). */
    public static final String ALL_TOOLS = "seed";

    private final Vertx vertx;

    private final SeedFiles files;

    private final List<SeedMapper> mappers;

    private final String tool;

    /**
     * @param mappers every mapper; they run ordered by {@link SeedMapper#order()}, ties in list order
     */
    public SeedConnector(Vertx vertx, SeedFiles files, List<SeedMapper> mappers)
    {
        this(vertx, files, sorted(mappers), ALL_TOOLS);
    }

    private SeedConnector(Vertx vertx, SeedFiles files, List<SeedMapper> mappers, String tool)
    {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.files = Objects.requireNonNull(files, "files");
        this.mappers = mappers;
        this.tool = tool;
    }

    /** The same seed restricted to the mappers of {@code tool} (Sync now of a seeded tile). */
    public SeedConnector forTool(String tool)
    {
        return new SeedConnector(vertx, files, mappers.stream().filter(m -> m.tool().equals(tool)).toList(), tool);
    }

    /** True when some mapper feeds {@code tool}. */
    public boolean feeds(String tool)
    {
        return mappers.stream().anyMatch(m -> m.tool().equals(tool));
    }

    /** Tools fed by at least one mapper, in load order. */
    public List<String> tools()
    {
        return mappers.stream().map(SeedMapper::tool).distinct().toList();
    }

    @Override
    public String tool()
    {
        return tool;
    }

    @Override
    public String source()
    {
        return SOURCE;
    }

    @Override
    public boolean live()
    {
        return false;
    }

    @Override
    public Future<JsonObject> fetchChanges(String since)
    {
        return vertx.executeBlocking(() ->
        {
            JsonArray entities = new JsonArray();

            for (SeedMapper mapper : mappers)
            {
                entities.add(new JsonObject()
                        .put("entity", mapper.entity())
                        .put("tool", mapper.tool())
                        .put("records", mapper.map(files)));
            }

            return new JsonObject()
                    .put("source", SOURCE)
                    .put("cursor", null)
                    .put("entities", entities);
        }, false);
    }

    private static List<SeedMapper> sorted(List<SeedMapper> mappers)
    {
        List<SeedMapper> out = new ArrayList<>(mappers);

        out.sort(Comparator.comparingInt(SeedMapper::order));

        return List.copyOf(out);
    }
}
