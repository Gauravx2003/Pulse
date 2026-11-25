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
     * returned nothing new) keeps the stored one.
     */
    public Future<Void> succeed(String tool, String cursor)
    {
        return execute("UPDATE sync_state SET status = 'succeeded', cursor = COALESCE($2, cursor), "
                + "last_error_code = NULL WHERE tool = $1", Tuple.of(tool, cursor)).mapEmpty();
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
