package com.pulse.platform.module;

import com.pulse.calc.core.CalcService;
import com.pulse.platform.accounts.ExternalAccountService;
import com.pulse.platform.accounts.SqlExternalAccountService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.live.ConnectionStore;
import com.pulse.platform.live.LiveConnectorRegistry;
import com.pulse.platform.notify.NotificationService;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.secrets.SecretBox;
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
 * {@code connectors} holds the live connectors that modules register from {@link PulseModule#start}
 * (task 24.3, change pulse-v2-live-connectors); one registry per context, filled before routes mount.
 */
public record ModuleContext(Vertx vertx, JsonObject config, PgPool pool, PolicyService policy, CalcService calc,
                            ModuleRegistry registry, LiveConnectorRegistry connectors)
{
    /** A context with an empty live connector registry (the Phase 1 constructor). */
    public ModuleContext(Vertx vertx, JsonObject config, PgPool pool, PolicyService policy, CalcService calc,
                         ModuleRegistry registry)
    {
        this(vertx, config, pool, policy, calc, registry, new LiveConnectorRegistry());
    }

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

    /** Live tool accounts and their person links (task 24.1, change pulse-v2-live-connectors). */
    public ExternalAccountService accounts()
    {
        return new SqlExternalAccountService(sql());
    }

    /** Encryption of live-tool secrets under {@code PULSE_SECRET_KEY} (task 24.2). */
    public SecretBox secrets()
    {
        return SecretBox.from(vertx, config);
    }

    /**
     * Stored live connections: config and encrypted secret (task 24.2). Pass a transaction's executor to
     * save in the same transaction as the credential log and the audit entry.
     */
    public ConnectionStore connections(SqlExecutor sql)
    {
        return new ConnectionStore(sql, secrets(), connectors);
    }
}
