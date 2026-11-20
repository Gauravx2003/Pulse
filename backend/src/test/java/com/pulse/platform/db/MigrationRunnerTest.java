package com.pulse.platform.db;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.pulse.platform.db.ScriptCatalogTest.PEOPLE;
import static com.pulse.platform.db.ScriptCatalogTest.PLATFORM;
import static com.pulse.platform.db.ScriptCatalogTest.WORK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the fixture migrations and seeds of three "tracks" against a real PostgreSQL, each test in
 * its own throw-away schema. Connection settings are the usual {@code DB_*} variables (see
 * {@link Db}); the test is skipped when no database is reachable, unless {@code PULSE_REQUIRE_DB=true}
 * (set in CI), in which case it fails.
 */
class MigrationRunnerTest {

    private Vertx vertx;
    private PgPool admin;
    private PgPool pool;
    private String schema;
    private boolean schemaCreated;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        PgConnectOptions options = Db.connectOptions(new JsonObject());
        admin = PgPool.pool(vertx, options, new PoolOptions().setMaxSize(1));
        schema = "mig_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            await(admin.query("CREATE SCHEMA " + schema).execute());
            schemaCreated = true;
        } catch (Exception e) {
            String reason = "No PostgreSQL reachable with DB_* settings: " + e.getMessage();
            if ("true".equals(System.getenv("PULSE_REQUIRE_DB"))) {
                throw new IllegalStateException(reason, e); // CI must not skip these tests
            }
            Assumptions.assumeTrue(false, reason);
        }
        pool = PgPool.pool(vertx, new PgConnectOptions(options).addProperty("search_path", schema),
                new PoolOptions().setMaxSize(2));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (pool != null) {
                await(pool.close());
            }
            if (schemaCreated) {
                await(admin.query("DROP SCHEMA IF EXISTS " + schema + " CASCADE").execute());
            }
        } finally {
            await(admin.close());
            await(vertx.close());
        }
    }

    @Test
    void loadsAllRangesInVersionOrderThenSeeds() throws Exception {
        ScriptCatalog catalog = ScriptCatalogTest.load(PEOPLE, WORK, PLATFORM);

        List<SqlScript> migrated = await(MigrationRunner.migrate(pool, catalog));
        List<SqlScript> seeded = await(MigrationRunner.seed(pool, catalog));

        assertEquals(5, migrated.size());
        assertEquals(3, seeded.size());
        assertEquals(List.of("V100", "V150", "V200", "V210", "V300", "S100", "S205", "S300"), appliedOrder());
        assertEquals(8L, count("SELECT count(*) FROM " + MigrationRunner.HISTORY_TABLE));
    }

    @Test
    void secondRunIsANoOp() throws Exception {
        ScriptCatalog catalog = ScriptCatalogTest.load(PEOPLE, WORK, PLATFORM);
        await(MigrationRunner.migrate(pool, catalog));
        await(MigrationRunner.seed(pool, catalog));

        assertTrue(await(MigrationRunner.migrate(pool, catalog)).isEmpty());
        assertTrue(await(MigrationRunner.seed(pool, catalog)).isEmpty());
        assertEquals(8, appliedOrder().size());
    }

    @Test
    void laterLowerVersionIsAppliedOutOfOrder() throws Exception {
        await(MigrationRunner.migrate(pool, ScriptCatalogTest.load(PLATFORM)));
        await(MigrationRunner.migrate(pool, ScriptCatalogTest.load(WORK, PLATFORM)));
        // Track 1 merges V150 after Track 2's V200 is already applied: it must still run.
        List<SqlScript> applied = await(MigrationRunner.migrate(pool, ScriptCatalog.of(List.of(
                SqlScript.parse(SqlScript.Kind.MIGRATION, "platform", "x/V120__late.sql",
                        "INSERT INTO apply_order (kind, version) VALUES ('V', 120);")))));
        assertEquals(1, applied.size());
        assertEquals("V120", appliedOrder().get(appliedOrder().size() - 1));
    }

    @Test
    void editedAppliedScriptFailsStartup() throws Exception {
        await(MigrationRunner.migrate(pool, ScriptCatalogTest.load(PLATFORM)));
        ScriptCatalog edited = ScriptCatalog.of(List.of(SqlScript.parse(SqlScript.Kind.MIGRATION, "platform",
                "x/V100__base.sql", "SELECT 'edited';")));
        Exception e = assertThrows(Exception.class, () -> await(MigrationRunner.migrate(pool, edited)));
        assertTrue(e.getMessage().contains("changed after it was applied"), e.getMessage());
    }

    @Test
    void failingScriptRollsBackTheWholeRun() throws Exception {
        ScriptCatalog broken = ScriptCatalog.of(List.of(
                SqlScript.parse(SqlScript.Kind.MIGRATION, "platform", "x/V100__ok.sql", "CREATE TABLE t_ok (id int);"),
                SqlScript.parse(SqlScript.Kind.MIGRATION, "work", "x/V200__broken.sql", "SELEC nonsense;")));
        Exception e = assertThrows(Exception.class, () -> await(MigrationRunner.migrate(pool, broken)));
        assertTrue(e.getMessage().contains("V200"), e.getMessage());
        assertEquals(0L, count("SELECT count(*) FROM pg_tables WHERE schemaname = '" + schema + "'"));
    }

    private List<String> appliedOrder() throws Exception {
        List<String> order = new ArrayList<>();
        for (Row row : await(pool.query("SELECT kind, version FROM apply_order ORDER BY seq").execute())) {
            order.add(row.getString("kind") + row.getInteger("version"));
        }
        return order;
    }

    private long count(String sql) throws Exception {
        return await(pool.query(sql).execute()).iterator().next().getLong(0);
    }

    private static <T> T await(io.vertx.core.Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
