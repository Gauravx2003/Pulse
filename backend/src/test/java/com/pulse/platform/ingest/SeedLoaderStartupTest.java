package com.pulse.platform.ingest;

import com.pulse.platform.config.RuntimeProfile;
import com.pulse.platform.db.DatabaseBootstrap;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import com.pulse.platform.module.ModuleRegistry;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import org.junit.jupiter.api.Test;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The start-up path of Main: every module's migrations and seeds, then the seed connector through
 * IngestService while PULSE_DEMO_SEED is on, and not at all while it is off. A restart is idempotent.
 */
class SeedLoaderStartupTest
{
    private static long count(SqlExecutor sql, String statement) throws Exception
    {
        return await(sql.queryOne(statement)).getLong("n");
    }

    @Test
    void demoSeedOnLoadsThroughTheSeedConnectorAndRestartsCleanly() throws Exception
    {
        try (TestDb db = TestDb.open())
        {
            PgPool pool = db.ownerPool();
            SqlExecutor sql = new SqlExecutor(pool);
            ClassLoader loader = getClass().getClassLoader();
            ModuleRegistry registry = ModuleRegistry.discover(loader);
            RuntimeProfile demo = RuntimeProfile.from(new JsonObject().put("demoSeed", true));

            await(DatabaseBootstrap.run(db.vertx(), pool, registry, loader, demo));

            long sprints = count(sql, "SELECT count(*) AS n FROM sprints");

            assertEquals(1, count(sql, "SELECT count(*) AS n FROM sync_log WHERE tool = 'seed' AND outcome = 'success'"));
            assertEquals(0, count(sql, "SELECT count(*) AS n FROM sprints WHERE source = 'seed' AND synced_at IS NULL"));
            assertEquals(7, count(sql, "SELECT count(*) AS n FROM integration_settings WHERE method IS NOT NULL"));

            // restart: same rows, one more load entry
            await(DatabaseBootstrap.run(db.vertx(), pool, registry, loader, demo));

            assertEquals(sprints, count(sql, "SELECT count(*) AS n FROM sprints"));
            assertEquals(2, count(sql, "SELECT count(*) AS n FROM sync_log WHERE tool = 'seed' AND outcome = 'success'"));
        }
    }

    @Test
    void demoSeedOffSkipsTheSeedConnector() throws Exception
    {
        try (TestDb db = TestDb.open())
        {
            PgPool pool = db.ownerPool();
            ClassLoader loader = getClass().getClassLoader();

            await(DatabaseBootstrap.run(db.vertx(), pool, ModuleRegistry.discover(loader), loader,
                    RuntimeProfile.from(new JsonObject().put("demoSeed", false))));

            assertEquals(0, count(new SqlExecutor(pool), "SELECT count(*) AS n FROM sync_log"));
        }
    }
}
