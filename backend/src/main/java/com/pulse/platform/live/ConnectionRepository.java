package com.pulse.platform.live;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.Base64;

/**
 * SQL of {@code integration_connections} (V190, task 24.2). {@link #view} is the only read that may
 * reach a client and never selects the ciphertext or IV; {@link #sealed} is for decryption inside a
 * test or sync run only.
 */
public final class ConnectionRepository extends BaseRepository
{
    private static final String VIEW = "SELECT tool, config, secret_last4 AS last4, "
            + "(secret_ciphertext IS NOT NULL) AS \"hasSecret\", expires_at AS \"expiresAt\", state, "
            + "rotated_at AS \"rotatedAt\", updated_at AS \"updatedAt\", version "
            + "FROM integration_connections WHERE tool = $1";

    /** Insert or replace config and secret; the row is connected afterwards. */
    private static final String SAVE = "INSERT INTO integration_connections AS ic "
            + "(tool, config, secret_ciphertext, secret_iv, secret_last4, expires_at, state, rotated_at) "
            + "VALUES ($1, $2, $3, $4, $5, $6::text::timestamptz, 'connected', now()) "
            + "ON CONFLICT (tool) DO UPDATE SET "
            + "config = EXCLUDED.config, "
            + "secret_ciphertext = EXCLUDED.secret_ciphertext, "
            + "secret_iv = EXCLUDED.secret_iv, "
            + "secret_last4 = EXCLUDED.secret_last4, "
            + "expires_at = EXCLUDED.expires_at, "
            + "state = 'connected', "
            + "rotated_at = now(), "
            + "updated_at = now(), "
            + "version = ic.version + 1";

    public ConnectionRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** {@code {tool, config, last4, hasSecret, expiresAt, state, rotatedAt, updatedAt, version}}, or null. */
    public Future<JsonObject> view(String tool)
    {
        return queryOne(VIEW, Tuple.of(tool));
    }

    /** {@code {config, ciphertext, iv, state}} with ciphertext and IV in base64 (null when none), or null. */
    public Future<JsonObject> sealed(String tool)
    {
        return queryOne("SELECT config, secret_ciphertext AS ciphertext, secret_iv AS iv, state "
                + "FROM integration_connections WHERE tool = $1", Tuple.of(tool));
    }

    /**
     * Stores {@code config} and a {@link com.pulse.platform.secrets.SecretBox#seal sealed} secret
     * ({@code {ciphertext, iv, last4}}, base64). {@code expiresAt} is an ISO date or timestamp, or null.
     */
    public Future<Void> save(String tool, JsonObject config, JsonObject sealed, String expiresAt)
    {
        return execute(SAVE, Tuple.of(tool,
                        config,
                        bytes(sealed.getString("ciphertext")),
                        bytes(sealed.getString("iv")),
                        sealed.getString("last4"),
                        expiresAt))
                .mapEmpty();
    }

    /** Deletes the stored secret and marks the tool disconnected; config is kept. True when a row changed. */
    public Future<Boolean> clearSecret(String tool)
    {
        return execute("UPDATE integration_connections SET secret_ciphertext = NULL, secret_iv = NULL, "
                        + "secret_last4 = NULL, state = 'disconnected', updated_at = now(), version = version + 1 "
                        + "WHERE tool = $1 AND (secret_ciphertext IS NOT NULL OR state <> 'disconnected')",
                Tuple.of(tool))
                .map(n -> n > 0);
    }

    private static Buffer bytes(String base64)
    {
        return Buffer.buffer(Base64.getDecoder().decode(base64));
    }
}
