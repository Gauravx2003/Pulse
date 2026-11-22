package com.pulse.platform.ingest;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/** The {@code sync_log} table (V100): one row per sync run, never a secret in {@code message}. */
public final class SyncLogRepository extends BaseRepository
{
    private static final String COLUMNS = "id, tool, started_at, finished_at, outcome, counts, message, "
            + "triggered_by, simulated";

    public SyncLogRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** Opens a run with outcome {@code running}; resolves to the row. */
    public Future<JsonObject> start(String tool, UUID triggeredBy, boolean simulated)
    {
        return queryOne("INSERT INTO sync_log (tool, outcome, triggered_by, simulated) VALUES ($1, 'running', $2, $3) "
                + "RETURNING " + COLUMNS, Tuple.of(tool, triggeredBy, simulated));
    }

    /** Closes a run with {@code success} or {@code failed}; resolves to the row. */
    public Future<JsonObject> finish(UUID id, String outcome, JsonObject counts, String message)
    {
        return queryOne("UPDATE sync_log SET finished_at = now(), outcome = $2, counts = $3, message = $4 "
                + "WHERE id = $1 RETURNING " + COLUMNS, Tuple.of(id, outcome, counts, message));
    }

    /** The newest runs, optionally of one tool. */
    public Future<JsonArray> recent(String tool, int limit)
    {
        return queryList("SELECT " + COLUMNS + " FROM sync_log WHERE ($1::text IS NULL OR tool = $1) "
                + "ORDER BY started_at DESC LIMIT $2", Tuple.of(tool, limit));
    }

    /** The newest finished run of each tool: {@code {tool, outcome, finished_at, message, counts}}. */
    public Future<JsonArray> latestPerTool()
    {
        return queryList("SELECT DISTINCT ON (tool) " + COLUMNS + " FROM sync_log WHERE outcome <> 'running' "
                + "ORDER BY tool, started_at DESC", Tuple.tuple());
    }

    /** The newest successful run of each tool. */
    public Future<JsonArray> latestSuccessPerTool()
    {
        return queryList("SELECT DISTINCT ON (tool) " + COLUMNS + " FROM sync_log WHERE outcome = 'success' "
                + "ORDER BY tool, started_at DESC", Tuple.tuple());
    }
}
