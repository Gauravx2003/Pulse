package com.pulse.modules.imports.pipeline;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.ListQuery;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/** Import history ({@code import_log}, V300). */
public class ImportLogRepository extends BaseRepository
{
    /** Columns every history read returns, with the derived undo state. */
    private static final String COLUMNS = """
            l.id, l.check_id, l.import_type, l.mode, l.file_name, l.user_id, u.name AS user_name,
            l.committed_at, l.rows_read, l.imported, l.skipped, l.errors, l.warnings, l.summary,
            l.undo_until, l.undone_at,
            CASE WHEN l.undone_at IS NOT NULL THEN 'undone'
                 WHEN now() < l.undo_until THEN 'available'
                 ELSE 'expired' END AS undo_state
            """;

    public ImportLogRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * Serializes commits and undos of one import type until the transaction ends (spec:
     * "Concurrency"). Must run inside {@code SqlExecutor.inTransaction}.
     */
    public Future<JsonObject> lockType(String importType)
    {
        return queryOne("SELECT pg_advisory_xact_lock(hashtext($1)) IS NULL AS locked",
                Tuple.of("pulse.imports." + importType));
    }

    /** The committed import for this check id, or {@code null}. */
    public Future<JsonObject> byCheckId(String checkId)
    {
        return queryOne("SELECT " + COLUMNS + " FROM import_log l LEFT JOIN users u ON u.id = l.user_id WHERE l.check_id = $1",
                Tuple.of(UUID.fromString(checkId)));
    }

    public Future<JsonObject> byId(String id)
    {
        return queryOne("SELECT " + COLUMNS + " FROM import_log l LEFT JOIN users u ON u.id = l.user_id WHERE l.id = $1",
                Tuple.of(UUID.fromString(id)));
    }

    /** Locks one history row for undo; {@code {id, import_type, user_id, undone_at, undo_open}} or null. */
    public Future<JsonObject> lockForUndo(String id)
    {
        return queryOne("""
                SELECT id, import_type, user_id, undone_at, now() < undo_until AS undo_open
                FROM import_log WHERE id = $1 FOR UPDATE
                """,
                Tuple.of(UUID.fromString(id)));
    }

    /** Inserts a history row; resolves to its id. */
    public Future<String> insert(JsonObject entry)
    {
        return queryOne("""
                INSERT INTO import_log (check_id, import_type, mode, file_name, file_hash, user_id,
                                        rows_read, imported, skipped, errors, warnings, summary)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12::jsonb)
                RETURNING id
                """,
                Tuple.tuple()
                        .addValue(UUID.fromString(entry.getString("checkId")))
                        .addValue(entry.getString("importType"))
                        .addValue(entry.getString("mode"))
                        .addValue(entry.getString("fileName"))
                        .addValue(entry.getString("fileHash"))
                        .addValue(UUID.fromString(entry.getString("userId")))
                        .addValue(entry.getInteger("rowsRead"))
                        .addValue(entry.getInteger("imported"))
                        .addValue(entry.getInteger("skipped"))
                        .addValue(entry.getInteger("errors"))
                        .addValue(entry.getInteger("warnings"))
                        .addValue(entry.getJsonObject("summary")))
                .map(row -> row.getString("id"));
    }

    public Future<Integer> markUndone(String id, String userId)
    {
        return execute("UPDATE import_log SET undone_at = now(), undone_by = $2, version = version + 1 WHERE id = $1",
                Tuple.of(UUID.fromString(id), UUID.fromString(userId)));
    }

    /**
     * History newest first, paged ({@code ?page, ?pageSize, ?import_type}). When {@code onlyUserId}
     * is not null, only that user's imports are listed.
     */
    public Future<JsonObject> history(MultiMap params, String onlyUserId)
    {
        ListQuery query = ListQuery.from("SELECT " + COLUMNS + " FROM import_log l LEFT JOIN users u ON u.id = l.user_id")
                .filter("import_type", "l.import_type")
                .sort("committed_at", "l.committed_at")
                .defaultSort("-committed_at")
                .tieBreaker("l.id");

        if (onlyUserId != null)
        {
            query.where("l.user_id = ?", UUID.fromString(onlyUserId));
        }

        return page(query, params);
    }
}
