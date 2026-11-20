package com.pulse.platform.module;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;

/**
 * Shared services handed to every module. {@code pool} is the one shared {@link PgPool}
 * ({@link com.pulse.platform.db.Db}); modules never create their own. {@code CalcService} is
 * added here once it exists (task 4.10).
 */
public record ModuleContext(Vertx vertx, JsonObject config, PgPool pool) {
}
