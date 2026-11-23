package com.pulse.modules.okrs;

import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

import java.util.List;

/**
 * Track 2 OKRs module (task group 14): schema {@code migrations/V210__okrs.sql}, demo seed
 * {@code seed/S210__demo_okrs.sql}, the API in {@link OkrsRoutes} over {@link OkrService}, the import and
 * export port for Track 3 ({@link OkrImportPort}) and the off-track next step. okr.own and okr.team are
 * built-in permissions, so the module declares none.
 */
public class OkrsModule implements PulseModule
{

    @Override
    public String id()
    {
        return "okrs";
    }

    @Override
    public List<String> auditEvents()
    {
        return OkrService.AUDIT_EVENTS;
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new OkrsRoutes(ctx).mount(router);
    }

    @Override
    public List<ActionProvider> actionProviders()
    {
        return List.of(new OkrOffTrackProvider());
    }
}
