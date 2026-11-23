package com.pulse.platform.jobs;

import com.pulse.platform.module.JobSpec;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.RetentionRule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The jobs the platform itself owns (task 5.5). Module jobs come from {@link
 * com.pulse.platform.module.PulseModule#jobs()}: the expiring-token check lives with the Integrations
 * module, the weekly pulse prompt and digest with Progress, the stale-signal refresh with Work and the
 * month-end reminder with Attendance.
 */
public final class PlatformJobs
{
    /** Daily purge of raw events past their retention window (spec platform/privacy-retention). */
    public static final String RETENTION_PURGE = "platform.retention-purge";

    /** The platform's own run logs: kept as long as raw tool events. */
    static final List<RetentionRule> PLATFORM_RETENTION = List.of(
            RetentionRule.rawEvents("sync_log", "started_at"),
            RetentionRule.rawEvents("job_runs", "started_at"));

    private PlatformJobs()
    {
    }

    public static List<JobSpec> all()
    {
        return List.of(JobSpec.of(RETENTION_PURGE, "30 2 * * *",
                "Deletes raw tool events older than their retention window (13 months); scores derived from them stay",
                PlatformJobs::purge));
    }

    /**
     * Deletes, for every retention rule of the platform and the modules, the rows dated before today
     * minus the window. A table that is not installed is left out. Counts: rows deleted per table.
     */
    static Future<JsonObject> purge(ModuleContext ctx)
    {
        List<RetentionRule> rules = new ArrayList<>(PLATFORM_RETENTION);

        if (ctx.registry() != null)
        {
            rules.addAll(ctx.registry().retention());
        }

        LocalDate today = ctx.calc().clock().today();

        JsonObject counts = new JsonObject();

        Future<Void> chain = Future.succeededFuture();

        for (RetentionRule rule : rules)
        {
            chain = chain.compose(v -> ctx.sql().queryOne("SELECT to_regclass($1)::text AS t", Tuple.of(rule.table()))
                    .compose(found ->
                    {
                        if (found == null || found.getString("t") == null)
                        {
                            return Future.succeededFuture();
                        }

                        // identifiers are checked by RetentionRule; the cut-off is a parameter
                        return ctx.sql().execute("DELETE FROM " + rule.table() + " WHERE " + rule.column() + " < $1::date",
                                        Tuple.of(today.minusMonths(rule.months())))
                                .map(n ->
                                {
                                    counts.put(rule.table(), counts.getInteger(rule.table(), 0) + n);

                                    return null;
                                });
                    }));
        }

        return chain.map(v -> counts);
    }
}
