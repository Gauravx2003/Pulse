package com.pulse.platform.jobs;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.platform.auth.AuthSettings;
import com.pulse.platform.auth.PermissionCatalogue;
import com.pulse.platform.config.RuntimeProfile;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.ModuleRegistry;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.pgclient.PgPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * The in-process scheduler (task 5.5, design decision 1): checks every registered job's cron each
 * minute in the company time zone and runs the due ones through {@link JobRunner}. Deploy exactly one
 * instance; more would fire every job more than once. The schedule follows the real wall clock, not a
 * pinned demo date, so a demo can still trigger a job by hand from the Integrations screen.
 *
 * <p>At start it closes runs a previous process left running. A tick that falls behind (a long GC pause,
 * a busy event loop) catches up on at most {@link #MAX_CATCH_UP} missed minutes.
 *
 * <p>Config: {@code jobs.enabled} (default true) turns the ticks off; {@code jobs.tickMs} (default 15 s).
 */
public class SchedulerVerticle extends AbstractVerticle
{
    private static final Logger log = LoggerFactory.getLogger(SchedulerVerticle.class);

    static final int MAX_CATCH_UP = 10;

    private final Clock clock;

    private JobRunner runner;

    private CalcService calc;

    private LocalDateTime lastMinute;

    public SchedulerVerticle()
    {
        this(Clock.system(PulseClock.ZONE));
    }

    /** Visible for tests: a scheduler over {@code clock}. */
    public SchedulerVerticle(Clock clock)
    {
        this.clock = clock;
    }

    @Override
    public void start(Promise<Void> startPromise)
    {
        ClassLoader loader = getClass().getClassLoader();

        vertx.executeBlocking(() -> ModuleRegistry.discover(loader))
                .compose(registry -> vertx.executeBlocking(() -> FormulaRegistry.discover(loader)).map(formulas ->
                {
                    PgPool pool = Db.pool(vertx, config());

                    SqlExecutor sql = new SqlExecutor(pool);

                    PolicyService policy = new PolicyService(sql, PermissionCatalogue.of(registry).keys(),
                            registry.visibilityRelations(), AuthSettings.from(config()).trustProxy(),
                            registry.selfRefusedActions());

                    calc = new CalcService(sql, PulseClock.from(config(), RuntimeProfile.from(config())), formulas)
                            .listen(vertx);

                    runner = new JobRunner(new ModuleContext(vertx, config(), pool, policy, calc, registry));

                    return runner;
                }))
                .compose(r -> r.recover())
                .onSuccess(closed ->
                {
                    if (closed > 0)
                    {
                        log.warn("Closed {} job run(s) left running by a previous process", closed);
                    }

                    log.info("Scheduler started with {} job(s): {}", runner.jobs().size(),
                            runner.jobs().stream().map(j -> j.name() + " [" + j.schedule() + "]").toList());

                    if (config().getBoolean("jobs.enabled", true))
                    {
                        lastMinute = now();

                        vertx.setPeriodic(config().getLong("jobs.tickMs", 15_000L), id -> tick());
                    }

                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise)
    {
        (calc == null ? io.vertx.core.Future.<Void>succeededFuture() : calc.close()).onComplete(v -> stopPromise.complete());
    }

    private LocalDateTime now()
    {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
    }

    /** Runs the jobs due in each minute since the last tick (at most {@link #MAX_CATCH_UP} minutes back). */
    void tick()
    {
        LocalDateTime current = now();

        if (!current.isAfter(lastMinute))
        {
            return;
        }

        LocalDateTime from = lastMinute.plusMinutes(1);

        if (from.isBefore(current.minusMinutes(MAX_CATCH_UP - 1L)))
        {
            log.warn("Scheduler fell behind; skipping the minutes {} to {}", from, current.minusMinutes(MAX_CATCH_UP));

            from = current.minusMinutes(MAX_CATCH_UP - 1L);
        }

        lastMinute = current;

        for (LocalDateTime m = from; !m.isAfter(current); m = m.plusMinutes(1))
        {
            for (JobRunner.Job job : runner.due(m))
            {
                runner.run(job.name(), JobRunner.SCHEDULE, null)
                        .onFailure(e -> log.error("Job {} could not start", job.name(), e));
            }
        }
    }
}
