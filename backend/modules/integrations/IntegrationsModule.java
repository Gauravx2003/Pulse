package com.pulse.modules.integrations;

import com.pulse.platform.accounts.ExternalAccountRepository;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.JobSpec;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import java.util.List;
import java.util.UUID;

/**
 * Integrations (Track 1, task 5.4, spec admin/integrations-connectors): the connector tiles, settings and
 * credential forms, Sync now, and the sync and credential activity logs. Phase 1 is a seeded simulation
 * over the platform's connector seam ({@code com.pulse.platform.ingest}); the live GitHub and Jira
 * connectors of group 24 register on the same {@code ConnectorRegistry}. Tables: V140
 * ({@code integration_settings}, {@code credential_log}); {@code sync_log} is a platform table.
 */
public class IntegrationsModule implements PulseModule
{
    /** Sync mode or Jira configuration changed. Credential changes use the platform's credential.changed. */
    static final String SETTINGS_CHANGED = "integration.settings_changed";

    @Override
    public String id()
    {
        return "integrations";
    }

    @Override
    public List<String> auditEvents()
    {
        return List.of(SETTINGS_CHANGED);
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new IntegrationsRoutes(ctx).mount(router);
    }

    /** The expiring-token check (task 5.5). */
    @Override
    public List<JobSpec> jobs()
    {
        return List.of(TokenExpiryJob.spec());
    }

    @Override
    public List<ActionProvider> actionProviders()
    {
        return List.of(new IntegrationHealthProvider());
    }

    /**
     * A deleted person's live tool accounts (spec privacy-retention, task 24.8): unlinked and
     * pseudonymized, with the raw account copies in live rows moved to the opaque id. The live rows
     * stay with no person, so team totals are unchanged.
     */
    @Override
    public Future<Void> erasePersonalData(SqlExecutor tx, JsonObject subject)
    {
        String personId = subject.getString("personId");

        if (personId == null)
        {
            return Future.succeededFuture();
        }

        return new ExternalAccountRepository(tx).eraseForPerson(UUID.fromString(personId)).mapEmpty();
    }
}
