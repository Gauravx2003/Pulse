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
 * {@code PULSE_DB_POOL_SIZE}).
 */
public final class Db {

    public static final String POOL_NAME = "pulse-pool";

    private Db() {
    }

    public static PgPool pool(Vertx vertx, JsonObject config) {
        return PgPool.pool(vertx, connectOptions(config), poolOptions(config));
    }

    public static PgConnectOptions connectOptions(JsonObject config) {
        return new PgConnectOptions()
                .setHost(setting(config, "db.host", "DB_HOST", "localhost"))
                .setPort(Integer.parseInt(setting(config, "db.port", "DB_PORT", "5432")))
                .setDatabase(setting(config, "db.name", "DB_NAME", "pulse"))
                .setUser(setting(config, "db.user", "DB_USER", "pulse"))
                .setPassword(setting(config, "db.password", "DB_PASSWORD", ""));
    }

    public static PoolOptions poolOptions(JsonObject config) {
        return new PoolOptions()
                .setMaxSize(Integer.parseInt(setting(config, "db.poolSize", "PULSE_DB_POOL_SIZE", "10")))
                .setShared(true)
                .setName(POOL_NAME);
    }

    private static String setting(JsonObject config, String key, String env, String fallback) {
        Object fromConfig = config == null ? null : config.getValue(key);
        if (fromConfig != null) {
            return fromConfig.toString();
        }
        String fromEnv = System.getenv(env);
        return fromEnv != null && !fromEnv.isBlank() ? fromEnv : fallback;
    }
}
