package com.pulse;

import com.pulse.platform.config.RuntimeProfile;
import com.pulse.platform.db.DatabaseBootstrap;
import com.pulse.platform.db.Db;
import com.pulse.platform.jobs.SchedulerVerticle;
import com.pulse.platform.live.LoopbackFlag;
import com.pulse.platform.module.ModuleRegistry;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process entry point: checks the runtime profile, discovers modules, runs migrations and seed
 * files once, then deploys the HTTP verticle and the single scheduler verticle (task 5.5). Migrations
 * run here, not in a verticle, so they run exactly once per process, as the owner account; the HTTP
 * verticle then uses the shared app-role pool ({@link Db#pool}).
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Starting Pulse Vert.x application...");
        Vertx vertx = Vertx.vertx();
        start(vertx, new JsonObject())
                .onSuccess(id -> log.info("Pulse HttpServerVerticle deployed successfully! Deployment ID: {}", id))
                .onFailure(err -> {
                    log.error("Pulse failed to start: {}", err.getMessage(), err);
                    System.exit(1);
                });
    }

    /** Starts Pulse with {@code config} (falls back to the environment); resolves to the deployment id. */
    public static Future<String> start(Vertx vertx, JsonObject config) {
        RuntimeProfile profile;
        try {
            profile = RuntimeProfile.from(config).guard();
            // the mock server's loopback switch is test-only (task 24.4)
            LoopbackFlag.guard(config, profile);
        } catch (IllegalStateException e) {
            return Future.failedFuture(e);
        }
        log.info("Profile {}, demo seed {}", profile.profile(), profile.demoSeed() ? "on" : "off");

        PgPool migrationPool = Db.migrationPool(vertx, config);
        ClassLoader loader = Main.class.getClassLoader();
        return vertx.executeBlocking(() -> ModuleRegistry.discover(loader))
                .compose(registry -> DatabaseBootstrap.run(vertx, migrationPool, registry, loader, profile))
                .eventually(v -> migrationPool.close())
                .compose(migrated -> vertx.deployVerticle(new HttpServerVerticle(),
                        new DeploymentOptions().setConfig(config)))
                // exactly one scheduler instance (design decision 1): more would fire every job more than once
                .compose(httpId -> vertx.deployVerticle(new SchedulerVerticle(),
                        new DeploymentOptions().setConfig(config).setInstances(1)).map(httpId));
    }
}
