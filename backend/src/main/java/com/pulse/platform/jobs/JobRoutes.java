package com.pulse.platform.jobs;

import com.pulse.calc.core.PulseClock;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Scheduled jobs for Admins (task 5.5, spec platform/jobs-scheduler "Manual trigger" and "Run records
 * and failure visibility"), behind {@link #PERMISSION}, which only the Admin role holds by default.
 *
 * <ul>
 *   <li>{@code GET /api/v1/jobs}: {@code {items: [{name, description, schedule, nextRunAt, lastRun}]}},
 *       {@code lastRun} the newest run that was not skipped, or null;</li>
 *   <li>{@code GET /api/v1/jobs/runs?job=&limit=}: {@code {items}} newest first (limit 1–100, default 20);</li>
 *   <li>{@code POST /api/v1/jobs/{name}/run}: audits the trigger, runs the job once and answers
 *       {@code {run}} after its first attempt (see {@link JobRunner#run}); 404 for an unknown job.</li>
 * </ul>
 */
public final class JobRoutes
{
    public static final String PERMISSION = "jobs.run";

    static final int DEFAULT_LIMIT = 20;

    static final int MAX_LIMIT = 100;

    private JobRoutes()
    {
    }

    public static void mount(Router router, String apiPrefix, PolicyService policy, JobRunner runner)
    {
        router.get(apiPrefix + "/jobs").handler(policy.requires(PERMISSION)).handler(rc ->
                list(runner).onSuccess(rc::json).onFailure(rc::fail));

        router.get(apiPrefix + "/jobs/runs").handler(policy.requires(PERMISSION)).handler(rc ->
        {
            String job = rc.queryParams().get("job");

            if (job != null && runner.job(job) == null)
            {
                rc.fail(ApiException.notFound("job_not_found", "There is no job called " + job + ".").field("job"));

                return;
            }

            runner.recent(job, limit(rc)).onSuccess(items -> rc.json(new JsonObject().put("items", items))).onFailure(rc::fail);
        });

        router.post(apiPrefix + "/jobs/:name/run").handler(policy.requires(PERMISSION)).handler(rc -> trigger(rc, runner));
    }

    private static void trigger(RoutingContext rc, JobRunner runner)
    {
        String name = rc.pathParam("name");

        if (runner.job(name) == null)
        {
            rc.fail(ApiException.notFound("job_not_found", "There is no job called " + name + "."));

            return;
        }

        JsonObject user = PolicyService.user(rc);

        AuditService.record(runner.sql(), AuditService.entry(user, AuditEvents.JOB_TRIGGERED, "job", name, new JsonObject()))
                .compose(v -> runner.run(name, JobRunner.MANUAL, UUID.fromString(user.getString("id"))))
                .onSuccess(run -> rc.json(new JsonObject().put("run", run)))
                .onFailure(rc::fail);
    }

    private static Future<JsonObject> list(JobRunner runner)
    {
        return new JobRunRepository(runner.sql()).latestPerJob().map(latest ->
        {
            JsonObject byJob = new JsonObject();

            latest.forEach(o -> byJob.put(((JsonObject) o).getString("job"), o));

            LocalDateTime now = LocalDateTime.now(PulseClock.ZONE);

            JsonArray items = new JsonArray();

            for (JobRunner.Job job : runner.jobs())
            {
                LocalDateTime next = job.schedule().next(now);

                items.add(new JsonObject()
                        .put("name", job.name())
                        .put("description", job.spec().description())
                        .put("schedule", job.schedule().expression())
                        .put("nextRunAt", next == null ? null : next.atZone(PulseClock.ZONE).toOffsetDateTime().toString())
                        .put("lastRun", byJob.getJsonObject(job.name())));
            }

            return new JsonObject().put("items", items);
        });
    }

    private static int limit(RoutingContext rc)
    {
        String raw = rc.queryParams().get("limit");

        if (raw == null)
        {
            return DEFAULT_LIMIT;
        }

        try
        {
            int limit = Integer.parseInt(raw);

            if (limit >= 1 && limit <= MAX_LIMIT)
            {
                return limit;
            }
        }
        catch (NumberFormatException ignored)
        {
            // answered below
        }

        throw ApiException.badRequest("invalid_limit", "Limit must be a whole number from 1 to " + MAX_LIMIT + ".").field("limit");
    }
}
