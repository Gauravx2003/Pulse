package com.pulse.platform.db;

import com.pulse.platform.config.RuntimeProfile;
import com.pulse.platform.ingest.SeedLoader;
import com.pulse.platform.module.ModuleRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Tuple;

/**
 * Startup step run once, before any HTTP verticle is deployed: migrations, the production guard,
 * then seed files (demo seeds only with PULSE_DEMO_SEED on), then, with the demo seed on, the seed
 * connector through IngestService (task 5.4).
 */
public final class DatabaseBootstrap {

    private DatabaseBootstrap() {
    }

    public static Future<Void> run(Vertx vertx, PgPool pool, ModuleRegistry registry, ClassLoader loader,
                                   RuntimeProfile profile) {
        return vertx.executeBlocking(() -> ScriptCatalog.load(loader, ScriptCatalog.sourcesFor(registry.modules())))
                .compose(catalog -> MigrationRunner.migrate(pool, catalog)
                        .compose(migrated -> refuseDemoAccountsInProduction(pool, profile))
                        .compose(checked -> MigrationRunner.seed(pool, catalog, profile.demoSeed()))
                        .compose(seeded -> profile.demoSeed()
                                ? SeedLoader.run(vertx, pool, loader, registry.modules()).mapEmpty()
                                : Future.<Void>succeededFuture()))
                .mapEmpty();
    }

    /** Production must not start while a demo account (demo123) exists (spec platform/auth-session). */
    static Future<Void> refuseDemoAccountsInProduction(PgPool pool, RuntimeProfile profile) {
        if (!profile.production()) {
            return Future.succeededFuture();
        }
        return new SqlExecutor(pool)
                .queryOne("SELECT count(*) AS n FROM users WHERE lower(email) LIKE $1",
                        Tuple.of("%" + RuntimeProfile.DEMO_EMAIL_DOMAIN))
                .compose(row -> {
                    long n = row.getLong("n");
                    return n == 0 ? Future.<Void>succeededFuture() : Future.failedFuture(new IllegalStateException(
                            "Refusing to start: " + n + " demo account(s) (" + RuntimeProfile.DEMO_EMAIL_DOMAIN
                                    + ") exist under the production profile. Remove them; the demo seed "
                                    + "(PULSE_DEMO_SEED) is for non-production databases only."));
                });
    }
}
