package com.pulse.platform.jobs;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The {@code job_runs} table (V150, task 5.5): one row per run of a scheduled job. */
public final class JobRunRepository extends BaseRepository
{
    static final String COLUMNS = "id, job, trigger, triggered_by AS \"triggeredBy\", started_at AS \"startedAt\", "
            + "ended_at AS \"endedAt\", outcome, attempts, next_attempt_at AS \"nextAttemptAt\", counts, error";

    /** PostgreSQL unique_violation: the {@code job_runs_one_running} index refused a second running row. */
    static final String UNIQUE_VIOLATION = "23505";

    public JobRunRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** Opens a run with outcome {@code running}; fails with unique_violation while another run of the job is running. */
    Future<JsonObject> start(String job, String trigger, UUID triggeredBy)
    {
        return queryOne("INSERT INTO job_runs (job, trigger, triggered_by, outcome) VALUES ($1, $2, $3, 'running') "
                + "RETURNING " + COLUMNS, Tuple.of(job, trigger, triggeredBy));
    }

    /** Records a tick or trigger that arrived while the previous run was unfinished. */
    Future<JsonObject> skipped(String job, String trigger, UUID triggeredBy)
    {
        return queryOne("INSERT INTO job_runs (job, trigger, triggered_by, outcome, ended_at, error) "
                + "VALUES ($1, $2, $3, 'skipped', now(), 'Previous run still running') RETURNING " + COLUMNS,
                Tuple.of(job, trigger, triggeredBy));
    }

    /** Records a failed attempt that will be retried at {@code nextAttemptAt}; the run stays {@code running}. */
    Future<JsonObject> retrying(UUID id, int attempts, String error, OffsetDateTime nextAttemptAt)
    {
        return queryOne("UPDATE job_runs SET attempts = $2, error = $3, next_attempt_at = $4 WHERE id = $1 RETURNING "
                + COLUMNS, Tuple.of(id, attempts, error, nextAttemptAt));
    }

    /** Closes a run with {@code succeeded} or {@code failed}. */
    Future<JsonObject> finish(UUID id, String outcome, int attempts, JsonObject counts, String error)
    {
        return queryOne("UPDATE job_runs SET outcome = $2, attempts = $3, counts = $4, error = $5, ended_at = now(), "
                + "next_attempt_at = NULL WHERE id = $1 RETURNING " + COLUMNS,
                Tuple.of(id, outcome, attempts, counts == null ? new JsonObject() : counts, error));
    }

    /**
     * Closes the runs a previous process left {@code running} (it stopped mid-run or before a retry):
     * single instance, so nothing else can be running them. Resolves to how many were closed.
     */
    Future<Integer> failInterrupted()
    {
        return execute("UPDATE job_runs SET outcome = 'failed', ended_at = now(), next_attempt_at = NULL, "
                + "error = coalesce(error || ' · ', '') || 'Interrupted by a restart' WHERE outcome = 'running'", Tuple.tuple());
    }

    public Future<JsonObject> byId(UUID id)
    {
        return queryOne("SELECT " + COLUMNS + " FROM job_runs WHERE id = $1", Tuple.of(id));
    }

    /** The newest runs, newest first, optionally of one job. */
    public Future<JsonArray> recent(String job, int limit)
    {
        return queryList("SELECT " + COLUMNS + " FROM job_runs WHERE ($1::text IS NULL OR job = $1) "
                + "ORDER BY started_at DESC, id LIMIT $2", Tuple.of(job, limit));
    }

    /** The newest run of each job that is not a skip. */
    public Future<JsonArray> latestPerJob()
    {
        return queryList("SELECT DISTINCT ON (job) " + COLUMNS + " FROM job_runs WHERE outcome <> 'skipped' "
                + "ORDER BY job, started_at DESC", Tuple.tuple());
    }

    /** Jobs whose newest finished run failed after its retries: the failure is still current. */
    public Future<JsonArray> failing()
    {
        return queryList("SELECT * FROM (SELECT DISTINCT ON (job) " + COLUMNS + " FROM job_runs "
                + "WHERE outcome IN ('succeeded', 'failed') ORDER BY job, started_at DESC) last "
                + "WHERE outcome = 'failed' ORDER BY \"startedAt\" DESC", Tuple.tuple());
    }
}
