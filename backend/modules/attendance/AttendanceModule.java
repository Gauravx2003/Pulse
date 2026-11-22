package com.pulse.modules.attendance;

import com.pulse.platform.ingest.SeedMapper;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

import java.util.List;

/**
 * Attendance (Track 3, task group 17). Migration V310 (records and monthly uploads); the seed comes
 * through {@code IngestService} ({@link AttendanceSeedMappers}). Uploads use the shared import pipeline
 * ({@link AttendanceImportType}, permission {@code attendance.upload}); scores come from calc/people
 * ({@code people.attendance}, the rule h and Collaboration ports).
 */
public class AttendanceModule implements PulseModule
{
    @Override
    public String id()
    {
        return "attendance";
    }

    @Override
    public List<SeedMapper> seedMappers()
    {
        return AttendanceSeedMappers.all();
    }

    @Override
    public List<ActionProvider> actionProviders()
    {
        return AttendanceActionProvider.all();
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new AttendanceRoutes(ctx).mount(router);
    }
}
