package com.pulse.platform.db;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;

/**
 * The single shared {@link PgPool} (design.md decision 1). Every caller gets the same underlying
 * pool because it is created with {@code setShared(true)} and a fixed name, so N HTTP verticle
 * instances reuse one set of connections.
 *
 * <p>Settings come from the verticle config ({@code db.host}, {@code db.port}, {@code db.name},
 * {@code db.user}, {@code db.password}, {@code db.poolSize}) and fall back to the environment
 * ({@code DB_HOST}, {@code DB_PORT}, {@code DB_NAME}, {@code DB_USER}, {@code DB_PASSWORD},
 * {@code PULSE_DB_POOL_SIZE}). {@code db.searchPath} (config only) points the pools at another
 * schema; integration tests use it for their throw-away schemas.
 *
 * <p>Two accounts (design.md decisions 4 and 7): migrations run as the connecting owner account
 * through {@link #migrationPool}; the application pool switches to the role {@code pulse_app}
 * ({@code db.appRole} / {@code PULSE_DB_APP_ROLE}), which has DML only, cannot run DDL and cannot
 * update or delete {@code audit_log}. The value {@code none} keeps the owner account (not for production).
 */
public final class Db
{
    public static final String POOL_NAME = "pulse-pool";

    public static final String DEFAULT_APP_ROLE = "pulse_app";

    private Db()
    {
    }

    /** The shared application pool, running as the app role. */
    public static PgPool pool(Vertx vertx, JsonObject config)
    {
        return PgPool.pool(vertx, appConnectOptions(config), poolOptions(config));
    }

    /** A small, unshared pool as the owner account, for the migration runner only; close it after use. */
    public static PgPool migrationPool(Vertx vertx, JsonObject config)
    {
        return PgPool.pool(vertx, connectOptions(config), new PoolOptions().setMaxSize(1));
    }

    /** Owner connection options with the app role applied at session start. */
    public static PgConnectOptions appConnectOptions(JsonObject config)
    {
        PgConnectOptions options = connectOptions(config);

        String role = appRole(config);

        if (!role.equalsIgnoreCase("none"))
        {
            options.addProperty("role", role);
        }

        return options;
    }

    public static String appRole(JsonObject config)
    {
        return setting(config, "db.appRole", "PULSE_DB_APP_ROLE", DEFAULT_APP_ROLE).trim();
    }

    public static PgConnectOptions connectOptions(JsonObject config)
    {
        PgConnectOptions options = new PgConnectOptions()
                .setHost(setting(config, "db.host", "DB_HOST", "localhost"))
                .setPort(Integer.parseInt(setting(config, "db.port", "DB_PORT", "5432")))
                .setDatabase(setting(config, "db.name", "DB_NAME", "pulse"))
                .setUser(setting(config, "db.user", "DB_USER", "pulse"))
                .setPassword(setting(config, "db.password", "DB_PASSWORD", ""));

        String searchPath = config == null ? null : config.getString("db.searchPath");

        if (searchPath != null && !searchPath.isBlank())
        {
            options.addProperty("search_path", searchPath);
        }

        return options;
    }

    public static PoolOptions poolOptions(JsonObject config)
    {
        return new PoolOptions()
                .setMaxSize(Integer.parseInt(setting(config, "db.poolSize", "PULSE_DB_POOL_SIZE", "10")))
                .setShared(true)
                .setName(POOL_NAME);
    }

    private static String setting(JsonObject config, String key, String env, String fallback)
    {
        Object fromConfig = config == null ? null : config.getValue(key);

        if (fromConfig != null)
        {
            return fromConfig.toString();
        }

        String fromEnv = System.getenv(env);

        return fromEnv != null && !fromEnv.isBlank() ? fromEnv : fallback;
    }
}
