package com.pulse.platform.module;

import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;

/**
 * Shared services handed to every module. {@code pool} is the one shared {@link PgPool}
 * ({@link com.pulse.platform.db.Db}); modules never create their own and never use it directly:
 * repositories extend {@link com.pulse.platform.db.BaseRepository} and are built from {@link #sql()}.
 * {@code CalcService} is added here once it exists (task 4.10).
 */
public record ModuleContext(Vertx vertx, JsonObject config, PgPool pool) {

    /** The shared data access entry point (task 1.6). */
    public SqlExecutor sql() {
        return new SqlExecutor(pool);
    }
}
