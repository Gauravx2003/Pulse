package com.pulse;

import com.pulse.platform.db.DatabaseBootstrap;
import com.pulse.platform.db.Db;
import com.pulse.platform.module.ModuleRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process entry point: discovers modules, runs migrations and seed files once, then deploys the
 * HTTP verticle. Migrations run here, not in a verticle, so they run exactly once per process, as
 * the owner account; the HTTP verticle then uses the shared app-role pool ({@link Db#pool}).
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Starting Pulse Vert.x application...");

        Vertx vertx = Vertx.vertx();
        JsonObject config = new JsonObject();
        PgPool migrationPool = Db.migrationPool(vertx, config);
        ClassLoader loader = Main.class.getClassLoader();

        vertx.executeBlocking(() -> ModuleRegistry.discover(loader))
                .compose(registry -> DatabaseBootstrap.run(vertx, migrationPool, registry, loader))
                .eventually(v -> migrationPool.close())
                .compose(migrated -> vertx.deployVerticle(new HttpServerVerticle()))
                .onSuccess(id -> log.info("Pulse HttpServerVerticle deployed successfully! Deployment ID: {}", id))
                .onFailure(err -> {
                    log.error("Pulse failed to start", err);
                    System.exit(1);
                });
    }
}
