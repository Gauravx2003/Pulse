package com.pulse.modules.integrations;

import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

import java.util.List;

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

    @Override
    public List<ActionProvider> actionProviders()
    {
        return List.of(new IntegrationHealthProvider());
    }
}
