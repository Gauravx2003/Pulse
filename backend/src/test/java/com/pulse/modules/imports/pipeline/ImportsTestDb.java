package com.pulse.modules.imports.pipeline;

import com.pulse.modules.imports.type.NotesImportType;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
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
 * A throw-away schema with the real V100 and V300 migrations plus the {@code test_notes} table, on
 * the PostgreSQL named by the usual {@code DB_*} variables. Skips the calling test when no database
 * is reachable, unless {@code PULSE_REQUIRE_DB=true} (CI), in which case it fails.
 */
final class ImportsTestDb implements AutoCloseable
{
    private static final ScriptCatalog.Source IMPORTS = new ScriptCatalog.Source(
            "imports", "modules/imports/migrations", "modules/imports/seed", 100, 399);

    final Vertx vertx = Vertx.vertx();

    final String schema = "test_imp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    private final PgConnectOptions options = Db.connectOptions(new JsonObject());

    private final PgPool admin = PgPool.pool(vertx, options, new PoolOptions().setMaxSize(1));

    private final List<PgPool> pools = new ArrayList<>();

    private boolean created;

    /** Owner (migration) account in the test schema: DDL and test set-up. */
    PgPool owner;

    /** Application role in the test schema, as the running app uses. */
    PgPool app;

    static ImportsTestDb open() throws Exception
    {
        ImportsTestDb db = new ImportsTestDb();

        try
        {
            await(db.admin.query("CREATE SCHEMA " + db.schema).execute());

            db.created = true;
        }
        catch (Exception e)
        {
            db.close();

            String reason = "No PostgreSQL reachable with DB_* settings: " + e.getMessage();

            if ("true".equals(System.getenv("PULSE_REQUIRE_DB")))
            {
                throw new IllegalStateException(reason, e);
            }

            Assumptions.assumeTrue(false, reason);
        }

        db.owner = db.pool(null);

        ScriptCatalog catalog = ScriptCatalog.load(ImportsTestDb.class.getClassLoader(), List.of(ScriptCatalog.PLATFORM, IMPORTS));

        await(MigrationRunner.migrate(db.owner, catalog));

        await(db.owner.query(NotesImportType.CREATE_TABLE).execute());

        db.app = db.pool(Db.DEFAULT_APP_ROLE);

        return db;
    }

    private PgPool pool(String role)
    {
        PgConnectOptions o = new PgConnectOptions(options).addProperty("search_path", schema);

        if (role != null)
        {
            o.addProperty("role", role);
        }

        PgPool pool = PgPool.pool(vertx, o, new PoolOptions().setMaxSize(4));

        pools.add(pool);

        return pool;
    }

    /** Creates a user and returns its id. */
    String newUser(String email) throws Exception
    {
        return await(owner.preparedQuery("INSERT INTO users (email, name, status) VALUES ($1, $1, 'Active') RETURNING id")
                .execute(io.vertx.sqlclient.Tuple.of(email))).iterator().next().getUUID(0).toString();
    }

    long count(String sql) throws Exception
    {
        return await(owner.query(sql).execute()).iterator().next().getLong(0);
    }

    void run(String sql) throws Exception
    {
        await(owner.query(sql).execute());
    }

    @Override
    public void close() throws Exception
    {
        try
        {
            for (PgPool p : pools)
            {
                await(p.close());
            }

            if (created)
            {
                await(admin.query("DROP SCHEMA IF EXISTS " + schema + " CASCADE").execute());
            }

            await(admin.close());
        }
        finally
        {
            await(vertx.close());
        }
    }

    static <T> T await(Future<T> future) throws Exception
    {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
