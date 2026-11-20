package com.pulse.platform.db;

import com.pulse.platform.module.ModuleRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgPool;

/** Startup step run once, before any HTTP verticle is deployed: migrations, then seed files. */
public final class DatabaseBootstrap {

    private DatabaseBootstrap() {
    }

    public static Future<Void> run(Vertx vertx, PgPool pool, ModuleRegistry registry, ClassLoader loader) {
        return vertx.executeBlocking(() -> ScriptCatalog.load(loader, ScriptCatalog.sourcesFor(registry.modules())))
                .compose(catalog -> MigrationRunner.migrate(pool, catalog)
                        .compose(migrated -> MigrationRunner.seed(pool, catalog)))
                .mapEmpty();
    }
}
