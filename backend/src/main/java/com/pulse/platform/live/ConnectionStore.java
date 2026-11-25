package com.pulse.platform.live;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.secrets.SecretBox;
import com.pulse.platform.secrets.SecretKeyException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.Objects;

/**
 * Stored connections of live tools (task 24.2, design.md decision 2): non-secret config plus the
 * secret encrypted by {@link SecretBox}, one row per tool in {@code integration_connections}.
 *
 * <ul>
 *   <li>Only registered live connectors are stored here. Seeded tools keep the Phase 1
 *       masked-placeholder path ({@code integration_settings}) and make no outbound call.</li>
 *   <li>{@link #save} encrypts before anything is written, so without a usable key it fails with
 *       {@link SecretKeyException} (422) and stores nothing.</li>
 *   <li>{@link #view} is what responses may carry: config and {@code last4}, never ciphertext.</li>
 *   <li>{@link #secret} decrypts for one test or sync run. A secret that does not decrypt fails with
 *       {@link SecretKeyException#UNREADABLE}; the caller sends no request and records
 *       {@link SecretKeyException#SYNC_ERROR}.</li>
 * </ul>
 *
 * Build it over a transaction's executor to save in the same transaction as the credential log and
 * the audit entry.
 */
public final class ConnectionStore
{
    private final ConnectionRepository repo;

    private final SecretBox box;

    private final LiveConnectorRegistry connectors;

    public ConnectionStore(SqlExecutor sql, SecretBox box, LiveConnectorRegistry connectors)
    {
        this.repo = new ConnectionRepository(sql);
        this.box = Objects.requireNonNull(box, "box");
        this.connectors = Objects.requireNonNull(connectors, "connectors");
    }

    /**
     * Encrypts {@code secret} and stores it with {@code config} (already validated against the
     * connector's {@code configSchema}); {@code expiresAt} is an ISO date or timestamp, or null.
     * Resolves to {@link #view}.
     */
    public Future<JsonObject> save(String tool, JsonObject config, String secret, String expiresAt)
    {
        if (!connectors.has(tool))
        {
            return Future.failedFuture(ApiException.unprocessable("connector_not_live",
                    "This tool has no live connector; its credentials are stored as a masked placeholder."));
        }

        if (secret == null || secret.isEmpty())
        {
            return Future.failedFuture(ApiException.badRequest("validation_failed", "Enter the secret.")
                    .field("secret"));
        }

        return box.seal(tool, secret)
                .compose(sealed -> repo.save(tool, config == null ? new JsonObject() : config, sealed, expiresAt))
                .compose(v -> view(tool));
    }

    /**
     * {@code {tool, config, last4, hasSecret, expiresAt, state, rotatedAt, updatedAt, version}}, or null
     * when the tool was never saved. Holds no ciphertext, IV or secret.
     */
    public Future<JsonObject> view(String tool)
    {
        return repo.view(tool);
    }

    /**
     * The decrypted secret, for the duration of one test or sync run only. Fails with
     * {@link SecretKeyException} when there is no stored secret or it can't be decrypted.
     */
    public Future<String> secret(String tool)
    {
        return repo.sealed(tool).compose(row ->
        {
            if (row == null || row.getString("ciphertext") == null)
            {
                return Future.failedFuture(SecretKeyException.unreadable());
            }

            return box.open(tool, row.getString("ciphertext"), row.getString("iv"));
        });
    }

    /** Deletes the stored secret (Disconnect); config and every ingested row are kept. */
    public Future<Boolean> clearSecret(String tool)
    {
        return repo.clearSecret(tool);
    }
}
