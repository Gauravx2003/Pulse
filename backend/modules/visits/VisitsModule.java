package com.pulse.modules.visits;

import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

import java.util.List;

/**
 * Customer visits (Track 3, task group 18). Migrations V320+, seed S320+. Verification uses the
 * built-in {@code visit.verify} audit event and separation-of-duties action.
 */
public class VisitsModule implements PulseModule
{
    @Override
    public String id()
    {
        return "visits";
    }

    @Override
    public List<String> auditEvents()
    {
        return List.of(VisitService.CREATED, VisitService.UPDATED, VisitService.DELETED, VisitService.REPORTED,
                VisitService.FEEDBACK, VisitService.REJECTED);
    }

    @Override
    public List<ActionProvider> actionProviders()
    {
        return VisitActionProvider.all();
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new VisitsRoutes(ctx).mount(router);
    }
}
