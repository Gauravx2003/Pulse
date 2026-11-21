package com.pulse.modules.imports.pipeline;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/** Undo snapshots ({@code import_before_images}, V300). */
public class BeforeImageRepository extends BaseRepository
{
    public BeforeImageRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * Stores all changes of one import in a single statement, numbered in list order.
     *
     * @param changes {@code [{"table", "id", "action", "before", "afterVersion"}, ...]} as reported by
     *                {@code ImportType.commit}
     */
    public Future<Integer> insertAll(String importLogId, JsonArray changes)
    {
        if (changes.isEmpty())
        {
            return Future.succeededFuture(0);
        }

        return execute("""
                INSERT INTO import_before_images (import_log_id, seq, target_table, target_id, action, before_data, after_version)
                SELECT $1, (c.ord - 1)::int, c.x ->> 'table', (c.x ->> 'id')::uuid, c.x ->> 'action',
                       NULLIF(c.x -> 'before', 'null'::jsonb), (c.x ->> 'afterVersion')::int
                FROM jsonb_array_elements($2::jsonb) WITH ORDINALITY AS c(x, ord)
                """,
                Tuple.of(UUID.fromString(importLogId), changes));
    }

    /** Every snapshot of one import, newest change first (the order undo applies them). */
    public Future<JsonArray> forUndo(String importLogId)
    {
        return queryList("""
                SELECT seq, target_table, target_id, action, before_data, after_version
                FROM import_before_images WHERE import_log_id = $1 ORDER BY seq DESC
                """,
                Tuple.of(UUID.fromString(importLogId)));
    }
}
