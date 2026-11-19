package com.pulse.platform.module;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Shared services handed to every module. The shared {@code PgPool} and {@code CalcService}
 * are added here once they exist (tasks 1.3 and 4.x); modules never create their own.
 */
public record ModuleContext(Vertx vertx, JsonObject config) {
}
