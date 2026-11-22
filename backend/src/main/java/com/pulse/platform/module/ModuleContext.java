package com.pulse.platform.module;

import com.pulse.calc.core.CalcService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.notify.NotificationService;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;

/**
 * Shared services handed to every module. {@code pool} is the one shared {@link PgPool}
 * ({@link com.pulse.platform.db.Db}); modules never create their own and never use it directly:
 * repositories extend {@link com.pulse.platform.db.BaseRepository} and are built from {@link #sql()}.
 * {@code policy} is the single authorization entry point (route permissions, visibility scopes,
 * separation of duties). {@code calc} is the only source of calculated numbers (task 4.10): screens and
 * endpoints read every metric, score and rating from it and never compute a formula themselves.
 * {@code registry} lists the discovered modules; the shared admin modules (task 5.1) read the
 * permission catalogue, the menu and the personal-data erasure hooks from it. {@link #notifications()}
 * sends in-app notifications (task 5.3) with the privacy and visibility rules applied.
 */
public record ModuleContext(Vertx vertx, JsonObject config, PgPool pool, PolicyService policy, CalcService calc,
                            ModuleRegistry registry)
{
    /** The shared data access entry point (task 1.6). */
    public SqlExecutor sql()
    {
        return new SqlExecutor(pool);
    }

    /** In-app notifications (task 5.3): build a notice with {@link com.pulse.platform.notify.NotificationTriggers}. */
    public NotificationService notifications()
    {
        return new NotificationService(sql(), policy);
    }
}
