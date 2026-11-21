package com.pulse.modules.imports.pipeline;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/** Staging rows of the Check step ({@code import_checks}, V300). */
public class ImportCheckRepository extends BaseRepository
{
    public ImportCheckRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** Stores a check and returns {@code {id, expires_at}}. */
    public Future<JsonObject> insert(String userId, String importType, String mode, String fileName,
                                     String fileHash, int fileBytes, JsonArray readyRows, JsonObject result)
    {
        return queryOne("""
                INSERT INTO import_checks (user_id, import_type, mode, file_name, file_hash, file_bytes, rows_data, result)
                VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8::jsonb)
                RETURNING id, expires_at
                """,
                Tuple.of(UUID.fromString(userId), importType, mode, fileName, fileHash, fileBytes, readyRows, result));
    }

    /**
     * The check if it belongs to this user and type and has not expired, locked until the
     * transaction ends; {@code null} otherwise (another user's id looks exactly like a missing one).
     */
    public Future<JsonObject> lockValid(String checkId, String userId, String importType)
    {
        return queryOne("""
                SELECT id, import_type, mode, file_name, file_hash, rows_data, result
                FROM import_checks
                WHERE id = $1 AND user_id = $2 AND import_type = $3 AND expires_at > now()
                FOR UPDATE
                """,
                Tuple.of(UUID.fromString(checkId), UUID.fromString(userId), importType));
    }

    /** Deletes one check of this user; resolves to the number of rows removed (0 or 1). */
    public Future<Integer> delete(String checkId, String userId)
    {
        return execute("DELETE FROM import_checks WHERE id = $1 AND user_id = $2",
                Tuple.of(UUID.fromString(checkId), UUID.fromString(userId)));
    }

    /** Removes every expired check (spec: staged data is deleted on expiry). */
    public Future<Integer> deleteExpired()
    {
        return execute("DELETE FROM import_checks WHERE expires_at <= now()", Tuple.tuple());
    }
}
