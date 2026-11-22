package com.pulse.modules.integrations;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/** {@code integration_settings} and {@code credential_log} (V140). */
final class IntegrationsRepository extends BaseRepository
{
    private static final String SETTINGS = "s.tool, s.method, s.credentials, s.credentials_saved_at, "
            + "s.tested_at, s.test_ok, s.token_expires_on, s.sync_mode, s.config, s.updated_at, s.version, u.name AS saved_by_name";

    IntegrationsRepository(SqlExecutor sql)
    {
        super(sql);
    }

    Future<JsonArray> settings()
    {
        return queryList("SELECT " + SETTINGS + " FROM integration_settings s "
                + "LEFT JOIN users u ON u.id = s.credentials_saved_by ORDER BY s.tool", Tuple.tuple());
    }

    Future<JsonObject> settings(String tool)
    {
        return queryOne("SELECT " + SETTINGS + " FROM integration_settings s "
                + "LEFT JOIN users u ON u.id = s.credentials_saved_by WHERE s.tool = $1", Tuple.of(tool));
    }

    /** Stores masked credentials; resolves to true when the tool had none before (added, not changed). */
    Future<Boolean> saveCredentials(String tool, String method, JsonObject masked, UUID by)
    {
        return queryOne("WITH old AS (SELECT method FROM integration_settings WHERE tool = $1) "
                + "INSERT INTO integration_settings (tool, method, credentials, credentials_saved_at, credentials_saved_by, "
                + "tested_at, test_ok) VALUES ($1, $2, $3, now(), $4, NULL, NULL) "
                + "ON CONFLICT (tool) DO UPDATE SET method = EXCLUDED.method, credentials = EXCLUDED.credentials, "
                + "credentials_saved_at = now(), credentials_saved_by = EXCLUDED.credentials_saved_by, "
                + "tested_at = NULL, test_ok = NULL, token_expires_on = NULL, updated_at = now(), "
                + "version = integration_settings.version + 1 "
                + "RETURNING (SELECT method FROM old) IS NULL AS added", Tuple.of(tool, method, masked, by))
                .map(row -> Boolean.TRUE.equals(row.getBoolean("added")));
    }

    /** Removes the credentials; resolves to the number of rows changed (0: was not connected). */
    Future<Integer> disconnect(String tool)
    {
        return execute("UPDATE integration_settings SET method = NULL, credentials = '{}'::jsonb, "
                + "credentials_saved_at = NULL, credentials_saved_by = NULL, tested_at = NULL, test_ok = NULL, "
                + "token_expires_on = NULL, "
                + "updated_at = now(), version = version + 1 WHERE tool = $1 AND method IS NOT NULL", Tuple.of(tool));
    }

    Future<Void> saveSyncMode(String tool, String syncMode)
    {
        return execute("INSERT INTO integration_settings (tool, sync_mode) VALUES ($1, $2) "
                + "ON CONFLICT (tool) DO UPDATE SET sync_mode = EXCLUDED.sync_mode, updated_at = now(), "
                + "version = integration_settings.version + 1", Tuple.of(tool, syncMode)).mapEmpty();
    }

    Future<Void> saveConfig(String tool, JsonObject config, String syncMode)
    {
        return execute("INSERT INTO integration_settings (tool, config, sync_mode) VALUES ($1, $2, $3) "
                + "ON CONFLICT (tool) DO UPDATE SET config = EXCLUDED.config, sync_mode = EXCLUDED.sync_mode, "
                + "updated_at = now(), version = integration_settings.version + 1",
                Tuple.of(tool, config, syncMode)).mapEmpty();
    }

    Future<Void> log(String tool, String action, String message, UUID actor)
    {
        return execute("INSERT INTO credential_log (tool, action, message, actor_id) VALUES ($1, $2, $3, $4)",
                Tuple.of(tool, action, message, actor)).mapEmpty();
    }

    /** Credential activity, newest first, with the actor's name ("System" when none). */
    Future<JsonArray> credentialLog(int limit)
    {
        return queryList("SELECT l.id, l.tool, l.action, l.message, l.at, coalesce(u.name, 'System') AS by "
                + "FROM credential_log l LEFT JOIN users u ON u.id = l.actor_id ORDER BY l.at DESC LIMIT $1",
                Tuple.of(limit));
    }
}
