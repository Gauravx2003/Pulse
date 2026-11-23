package com.pulse.platform.module;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A scheduled job declared by a module ({@link PulseModule#jobs()}) and run in-process by the
 * scheduler (task 5.5, spec platform/jobs-scheduler). Every run, scheduled or triggered by an Admin
 * from {@code POST /api/v1/jobs/{name}/run}, writes a {@code job_runs} row with its outcome and the
 * counts the task returns. A failed run is retried with backoff; after the last retry it shows on the
 * Integrations screen and as an Admin item in the Action center. A run never starts while the previous
 * run of the same job is unfinished.
 *
 * <pre>{@code
 * public List<JobSpec> jobs()
 * {
 *     return List.of(JobSpec.of("attendance.month-end-reminder", "0 10 L * *",
 *             "Reminds uploaders on the last day of the month", ctx -> remind(ctx)));
 * }
 * }</pre>
 *
 * @param name        unique {@code area.job-name}, e.g. {@code "work.stale-signal-refresh"}
 * @param schedule    five-field cron in the company time zone (minute hour day-of-month month
 *                    day-of-week; {@code L} = last day of the month), see
 *                    {@link com.pulse.platform.jobs.CronSchedule}; config {@code jobs.schedules.<name>}
 *                    overrides it
 * @param description one line shown on the Integrations screen
 * @param task        the job body; must not block the event loop (use {@code executeBlocking}). Resolves
 *                    to the run's counts (e.g. {@code {"notified": 4}}), or null for none. A failed future
 *                    fails the attempt. Make it safe to run twice: a retry runs it again from the start.
 */
public record JobSpec(String name, String schedule, String description, Function<ModuleContext, Future<JsonObject>> task)
{
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_-]+)+");

    public JobSpec
    {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(task, "task");

        if (!NAME.matcher(name).matches())
        {
            throw new IllegalArgumentException("Job name '" + name + "' must look like 'area.job-name'");
        }

        description = description == null ? "" : description;
    }

    public static JobSpec of(String name, String schedule, String description, Function<ModuleContext, Future<JsonObject>> task)
    {
        return new JobSpec(name, schedule, description, task);
    }
}
