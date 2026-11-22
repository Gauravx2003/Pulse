package com.pulse.platform.ingest;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.module.PulseModule;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Start-up load of the seed through {@link SeedConnector} and {@link IngestService} (spec
 * admin/integrations-connectors "Seed and live data coexist"). Runs after the SQL seed files on every
 * start while {@code PULSE_DEMO_SEED=true}; it is idempotent, so a restart only refreshes
 * {@code synced_at}. The run is recorded in {@code sync_log} as tool {@value SeedConnector#ALL_TOOLS}.
 */
public final class SeedLoader
{
    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private SeedLoader()
    {
    }

    /** Platform mappers first, then each module's {@link PulseModule#seedMappers()}, sorted by order. */
    public static List<SeedMapper> mappers(Collection<PulseModule> modules)
    {
        List<SeedMapper> all = new ArrayList<>(PlatformSeedMappers.all());

        modules.forEach(m -> all.addAll(m.seedMappers()));

        return all;
    }

    /** The seed connector over the platform and module mappers. */
    public static SeedConnector connector(Vertx vertx, ClassLoader loader, Collection<PulseModule> modules)
    {
        return new SeedConnector(vertx, new SeedFiles(loader), mappers(modules));
    }

    /** Loads the seed; resolves to the closed sync_log row, and fails when the load failed. */
    public static Future<JsonObject> run(Vertx vertx, SqlClient pool, ClassLoader loader,
                                         Collection<PulseModule> modules)
    {
        SeedConnector seed = connector(vertx, loader, modules);

        return new SyncService(vertx, new SqlExecutor(pool)).run(seed, SeedConnector.ALL_TOOLS, null, null)
                .compose(entry ->
                {
                    if (!"success".equals(entry.getString("outcome")))
                    {
                        return Future.failedFuture(new IllegalStateException("Seed load failed: "
                                + entry.getString("message")));
                    }

                    log.info("Seed connector loaded {} record(s)", entry.getJsonObject("counts").getInteger("total"));

                    return Future.succeededFuture(entry);
                });
    }
}
