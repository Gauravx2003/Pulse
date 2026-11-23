package com.pulse.platform.jobs;

import com.pulse.platform.logging.Redactor;
import com.pulse.platform.module.JobSpec;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.ModuleRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs the registered jobs (task 5.5, spec platform/jobs-scheduler): the platform jobs
 * ({@link PlatformJobs}) and every module's {@link JobSpec}. Used by the scheduler verticle for ticks
 * and by {@link JobRoutes} for the Admin trigger.
 *
 * <p>Each run writes one {@code job_runs} row. The {@code job_runs_one_running} index allows one
 * running row per job, so a tick or trigger that arrives while a run is unfinished (including one
 * waiting for a retry) records a {@code skipped} row instead, whichever verticle sends it. A failed
 * attempt is retried after {@code jobs.backoffMs} (default 60 s), doubling each time, up to
 * {@code jobs.attempts} attempts in all (default 3); then the run is {@code failed}.
 *
 * <p>Config: {@code jobs.attempts}, {@code jobs.backoffMs}, and {@code jobs.schedules} ({@code {name:
 * cron}}) to move a job, for example the Friday pulse prompt to another time.
 */
public final class JobRunner
{
    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    public static final String SCHEDULE = "schedule";

    public static final String MANUAL = "manual";

    static final int MAX_ERROR = 300;

    private final ModuleContext ctx;

    private final Map<String, Job> jobs;

    private final int attempts;

    private final long backoffMs;

    /** A job with its parsed schedule. */
    public record Job(JobSpec spec, CronSchedule schedule)
    {
        public String name()
        {
            return spec.name();
        }
    }

    public JobRunner(ModuleContext ctx)
    {
        this.ctx = ctx;

        JsonObject config = ctx.config() == null ? new JsonObject() : ctx.config();

        this.attempts = Math.max(1, config.getInteger("jobs.attempts", 3));
        this.backoffMs = Math.max(1, config.getLong("jobs.backoffMs", 60_000L));
        this.jobs = Collections.unmodifiableMap(load(ctx.registry(), config.getJsonObject("jobs.schedules", new JsonObject())));
    }

    private static Map<String, Job> load(ModuleRegistry registry, JsonObject overrides)
    {
        List<JobSpec> specs = new ArrayList<>(PlatformJobs.all());

        if (registry != null)
        {
            specs.addAll(registry.jobs());
        }

        Map<String, Job> out = new LinkedHashMap<>();

        for (JobSpec spec : specs)
        {
            String expression = overrides.getString(spec.name(), spec.schedule());

            Job job = new Job(spec, CronSchedule.parse(expression));

            if (out.putIfAbsent(spec.name(), job) != null)
            {
                throw new IllegalStateException("Job '" + spec.name() + "' is registered more than once");
            }
        }

        return out;
    }

    public Collection<Job> jobs()
    {
        return jobs.values();
    }

    public Job job(String name)
    {
        return jobs.get(name);
    }

    /** Jobs due in the minute of {@code time} (company time zone). */
    public List<Job> due(LocalDateTime time)
    {
        return jobs.values().stream().filter(j -> j.schedule().matches(time)).toList();
    }

    /** Closes runs a previous process left running; call once when the scheduler starts. */
    public Future<Integer> recover()
    {
        return new JobRunRepository(ctx.sql()).failInterrupted();
    }

    /**
     * Starts a run of {@code name} and resolves once its first attempt has finished, to the run row:
     * {@code succeeded}, {@code failed} (no retries left), {@code running} with {@code nextAttemptAt}
     * (a retry is scheduled) or {@code skipped} (the previous run is unfinished).
     *
     * @param trigger     {@link #SCHEDULE} or {@link #MANUAL}
     * @param triggeredBy the Admin's user id for a manual trigger, else null
     */
    public Future<JsonObject> run(String name, String trigger, UUID triggeredBy)
    {
        Job job = jobs.get(name);

        if (job == null)
        {
            return Future.failedFuture(new IllegalArgumentException("Unknown job " + name));
        }

        JobRunRepository runs = new JobRunRepository(ctx.sql());

        return runs.start(name, trigger, triggeredBy).recover(e ->
        {
            if (e instanceof PgException pg && JobRunRepository.UNIQUE_VIOLATION.equals(pg.getSqlState()))
            {
                log.info("Job {} skipped: previous run still running", name);

                return runs.skipped(name, trigger, triggeredBy).map(row -> row.put("skipped", true));
            }

            return Future.failedFuture(e);
        }).compose(row ->
        {
            if (row.getBoolean("skipped", false))
            {
                row.remove("skipped");

                return Future.succeededFuture(row);
            }

            Promise<JsonObject> firstAttempt = Promise.promise();

            attempt(job, UUID.fromString(row.getString("id")), 1, firstAttempt);

            return firstAttempt.future();
        });
    }

    private void attempt(Job job, UUID runId, int attempt, Promise<JsonObject> firstAttempt)
    {
        JobRunRepository runs = new JobRunRepository(ctx.sql());

        Future<JsonObject> body;

        try
        {
            body = job.spec().task().apply(ctx);
        }
        catch (RuntimeException e)
        {
            body = Future.failedFuture(e);
        }

        if (body == null)
        {
            body = Future.succeededFuture();
        }

        body.transform(done -> done.succeeded()
                ? runs.finish(runId, "succeeded", attempt, done.result(), null)
                : failed(job, runId, attempt, done.cause())
        ).onComplete(done ->
        {
            if (done.failed())
            {
                log.error("Job {} could not record its run", job.name(), done.cause());
            }

            if (done.succeeded())
            {
                firstAttempt.tryComplete(done.result());
            }
            else
            {
                firstAttempt.tryFail(done.cause());
            }
        });
    }

    /** Closes the run as failed when no attempt is left, else records the error and schedules the retry. */
    private Future<JsonObject> failed(Job job, UUID runId, int attempt, Throwable error)
    {
        JobRunRepository runs = new JobRunRepository(ctx.sql());

        String message = message(error);

        if (attempt >= attempts)
        {
            log.error("Job {} failed after {} attempt(s): {}", job.name(), attempt, message);

            return runs.finish(runId, "failed", attempt, null, message);
        }

        long delay = backoffMs << Math.min(attempt - 1, 20);

        OffsetDateTime next = OffsetDateTime.ofInstant(Instant.now().plusMillis(delay), ZoneOffset.UTC);

        log.warn("Job {} attempt {} failed, retrying in {} ms: {}", job.name(), attempt, delay, message);

        return runs.retrying(runId, attempt, message, next).onSuccess(row ->
                ctx.vertx().setTimer(delay, id -> attempt(job, runId, attempt + 1, Promise.promise())));
    }

    /** The data access executor of the runner's context. */
    public com.pulse.platform.db.SqlExecutor sql()
    {
        return ctx.sql();
    }

    /** The newest runs, newest first. */
    public Future<JsonArray> recent(String job, int limit)
    {
        return new JobRunRepository(ctx.sql()).recent(job, limit);
    }

    /** The error text kept on a run: the exception message, cut, never a stack trace. */
    static String message(Throwable error)
    {
        String text = error == null ? "Failed" : error.getMessage();

        if (text == null || text.isBlank())
        {
            text = error == null ? "Failed" : error.getClass().getSimpleName();
        }

        text = Redactor.redact(text.strip());

        return text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR - 1) + "…" : text;
    }
}
