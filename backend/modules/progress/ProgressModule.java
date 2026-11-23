package com.pulse.modules.progress;

import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.JobSpec;
import com.pulse.platform.module.PulseModule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * Track 2 Progress and weekly pulse module (task group 13). Its table {@code weekly_pulse} is part of the
 * Track 2 data layer ({@code work} module, V200); it seeds the prototype's pulse answers in
 * {@code seed/S230__demo_weekly_pulse.sql} (task 7.2). Progress and the pulse API arrive with task 13.3.
 */
public class ProgressModule implements PulseModule
{

    @Override
    public String id()
    {
        return "progress";
    }

    /**
     * Weekly pulse prompt and digest (task 5.5 registers the jobs; the bodies land with task 13.3).
     * TODO(13.3): Friday — prompt every active Employee with no pulse answer for the week; Monday — send
     * the weekly digest. Return counts such as {@code {prompted}} and {@code {sent}}.
     */
    @Override
    public List<JobSpec> jobs()
    {
        return List.of(
                JobSpec.of("progress.friday-pulse", "0 16 * * 5", "Asks everyone without an answer for the weekly pulse",
                        ctx -> Future.succeededFuture(new JsonObject().put("pending", "task 13.3"))),
                JobSpec.of("progress.monday-digest", "0 9 * * 1", "Sends the weekly digest",
                        ctx -> Future.succeededFuture(new JsonObject().put("pending", "task 13.3"))));
    }

    @Override
    public List<ActionProvider> actionProviders()
    {
        return List.of(new PulseActionProvider());
    }
}
