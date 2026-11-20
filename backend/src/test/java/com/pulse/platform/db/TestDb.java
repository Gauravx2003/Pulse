package com.pulse.platform.db;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.Assumptions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A throw-away schema on the PostgreSQL named by the usual {@code DB_*} variables ({@link Db}).
 * Skips the calling test when no database is reachable, unless {@code PULSE_REQUIRE_DB=true}
 * (set in CI), in which case it fails.
 */
final class TestDb implements AutoCloseable {

    final Vertx vertx = Vertx.vertx();
    final String schema = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final PgConnectOptions options = Db.connectOptions(new JsonObject());
    private final PgPool admin = PgPool.pool(vertx, options, new PoolOptions().setMaxSize(1));
    private final List<PgPool> pools = new ArrayList<>();
    private boolean created;

    static TestDb open() throws Exception {
        TestDb db = new TestDb();
        try {
            await(db.admin.query("CREATE SCHEMA " + db.schema).execute());
            db.created = true;
        } catch (Exception e) {
            db.close();
            String reason = "No PostgreSQL reachable with DB_* settings: " + e.getMessage();
            if ("true".equals(System.getenv("PULSE_REQUIRE_DB"))) {
                throw new IllegalStateException(reason, e);
            }
            Assumptions.assumeTrue(false, reason);
        }
        return db;
    }

    /** Pool in the test schema as the owner (migration) account. */
    PgPool ownerPool() {
        return pool(null);
    }

    /** Pool in the test schema switched to {@code role}, as the application pool does. */
    PgPool pool(String role) {
        PgConnectOptions o = new PgConnectOptions(options).addProperty("search_path", schema);
        if (role != null) {
            o.addProperty("role", role);
        }
        PgPool pool = PgPool.pool(vertx, o, new PoolOptions().setMaxSize(2));
        pools.add(pool);
        return pool;
    }

    @Override
    public void close() throws Exception {
        try {
            for (PgPool p : pools) {
                await(p.close());
            }
            if (created) {
                await(admin.query("DROP SCHEMA IF EXISTS " + schema + " CASCADE").execute());
            }
            await(admin.close());
        } finally {
            await(vertx.close());
        }
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
