package com.pulse.modules.attendance;

import com.pulse.platform.ingest.SeedMapper;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.JobSpec;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
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

    /**
     * Month-end attendance reminder (task 5.5 registers the job; spec time/attendance). Runs on the last day
     * of the month at 10:00 and records a run.
     * TODO(Track 3): notify the attendance.upload holders that the month's HR export is due by the 5th
     * (the "upload due" rule of AttendanceActionProvider) and return {@code {notified}}.
     */
    @Override
    public List<JobSpec> jobs()
    {
        return List.of(JobSpec.of("attendance.month-end-reminder", "0 10 L * *",
                "Reminds attendance uploaders that the month's HR export is due",
                ctx -> Future.succeededFuture(new JsonObject().put("pending", "body owned by Track 3"))));
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
