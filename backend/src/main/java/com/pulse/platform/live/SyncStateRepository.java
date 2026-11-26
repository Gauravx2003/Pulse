package com.pulse.platform.live;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

/**
 * SQL of {@code sync_state} (V190, task 24.6): the cursor and status of each live tool's sync.
 * {@code status} doubles as the one-sync-per-tool lock: {@link #lock} is a conditional update to
 * {@code running}, so of two concurrent calls only one gets the row back. A run refreshes
 * {@code last_run_at} after every page ({@link #touch}); a {@code running} row older than the stale
 * limit is treated as abandoned (the process stopped mid-sync) and can be locked again.
 */
public final class SyncStateRepository extends BaseRepository
{
    private static final String COLUMNS = "tool, cursor, last_run_at AS \"lastRunAt\", status, "
            + "last_error_code AS \"lastErrorCode\"";

    public SyncStateRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * Takes the lock of {@code tool}. Resolves to the row (with the stored cursor) when this call now
     * holds the lock, or to null when another sync is running and is not older than
     * {@code staleSeconds}.
     */
    public Future<JsonObject> lock(String tool, int staleSeconds)
    {
        return execute("INSERT INTO sync_state (tool) VALUES ($1) ON CONFLICT (tool) DO NOTHING", Tuple.of(tool))
                .compose(v -> queryOne("UPDATE sync_state SET status = 'running', last_run_at = now() "
                                + "WHERE tool = $1 AND (status <> 'running' OR last_run_at IS NULL "
                                + "OR last_run_at < now() - $2::int * interval '1 second') "
                                + "RETURNING " + COLUMNS,
                        Tuple.of(tool, staleSeconds)));
    }

    /** Keeps the lock fresh while a run makes progress. */
    public Future<Void> touch(String tool)
    {
        return execute("UPDATE sync_state SET last_run_at = now() WHERE tool = $1 AND status = 'running'",
                Tuple.of(tool)).mapEmpty();
    }

    /**
     * Releases the lock after a successful run and advances the cursor; a null {@code cursor} (the tool
     * returned nothing new) keeps the stored one. {@code from} is the cursor the run started from: when
     * the stored one no longer matches it ({@link #resetCursor} ran meanwhile), the cursor is left as is,
     * so a config saved during a run still gets its full read.
     */
    public Future<Void> succeed(String tool, String cursor, String from)
    {
        return execute("UPDATE sync_state SET status = 'succeeded', "
                + "cursor = CASE WHEN cursor IS NOT DISTINCT FROM $3 THEN COALESCE($2, cursor) ELSE cursor END, "
                + "last_error_code = NULL WHERE tool = $1", Tuple.of(tool, cursor, from)).mapEmpty();
    }

    /**
     * Clears the stored cursor, so the next sync reads everything again (jira-derived-hours 6.2). Resolves
     * to true when there was a cursor to clear.
     */
    public Future<Boolean> resetCursor(String tool)
    {
        return execute("UPDATE sync_state SET cursor = NULL WHERE tool = $1 AND cursor IS NOT NULL", Tuple.of(tool))
                .map(n -> n > 0);
    }

    /** Releases the lock after a failed run; the cursor does not move. {@code code} is never a secret. */
    public Future<Void> fail(String tool, String code)
    {
        return execute("UPDATE sync_state SET status = 'failed', last_error_code = $2 WHERE tool = $1",
                Tuple.of(tool, code)).mapEmpty();
    }

    /** {@code {tool, cursor, lastRunAt, status, lastErrorCode}}, or null when the tool never synced. */
    public Future<JsonObject> get(String tool)
    {
        return queryOne("SELECT " + COLUMNS + " FROM sync_state WHERE tool = $1", Tuple.of(tool));
    }

    /** Every tool's row, sorted by tool. */
    public Future<JsonArray> all()
    {
        return queryList("SELECT " + COLUMNS + " FROM sync_state ORDER BY tool", Tuple.tuple());
    }
}
