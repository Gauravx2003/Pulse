package com.pulse.modules.integrations;

import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.config.RuntimeProfile;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.live.ConnectionStore;
import com.pulse.platform.live.GuardedHttp;
import com.pulse.platform.live.LiveConfig;
import com.pulse.platform.live.LiveConnector;
import com.pulse.platform.live.LiveContext;
import com.pulse.platform.live.LiveHttpException;
import com.pulse.platform.live.LoopbackFlag;
import com.pulse.platform.live.TestProofs;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.secrets.SecretKeyException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Generic Test connection, Save and Disconnect for every registered live connector (task 24.5,
 * design.md decision 4, specs admin/integrations-connectors "Real connection test" and
 * secrets-credentials "Save bound to a passing test"). Nothing here knows a tool. Seeded tools keep
 * the Phase 1 routes of {@link IntegrationsService}.
 *
 * <ul>
 *   <li><b>Test</b> checks the values against the connector's {@code configSchema} and
 *       {@code secretSpec}, then runs {@code testConnection} through the guarded client. It passes only
 *       on HTTP 200, and only a pass carries a {@link TestProofs proof}. Nothing is stored.</li>
 *   <li><b>Save</b> needs that proof: issued for the same tool and values, at most 10 minutes old, and
 *       unused. It encrypts the secret, then writes the credential activity log and the audit entry,
 *       all in one transaction.</li>
 *   <li><b>Disconnect</b> deletes the secret and keeps the config and every ingested row. It writes
 *       the credential activity log and the audit entry.</li>
 * </ul>
 * No response, log line, log entry or audit entry carries the secret.
 */
final class LiveConnectionsService
{
    static final String HINT_401 = "401 Unauthorized, check the username and password or token";

    private static final Logger log = LoggerFactory.getLogger(LiveConnectionsService.class);

    private final ModuleContext ctx;

    private final TestProofs proofs;

    LiveConnectionsService(ModuleContext ctx)
    {
        this.ctx = ctx;
        this.proofs = new TestProofs(ctx.vertx());
    }

    // ------------------------------------------------------------------ read

    /**
     * {@code {tool, source, configSchema, secretSpec, requiredScopes, connection}}. {@code connection}
     * is the stored config, {@code last4}, state and expiry, or null; it has no ciphertext.
     */
    Future<JsonObject> connection(String tool)
    {
        LiveConnector connector = connector(tool);

        return ctx.connections(ctx.sql()).view(tool).map(view -> describe(connector).put("connection", view));
    }

    // ------------------------------------------------------------------ test

    /**
     * Runs the connector's test with the submitted values. Resolves to
     * {@code {ok, status, code, account, scopes, scopeNote, fields, expiresAt, hint, warnings, proof,
     * proofExpiresAt}}; {@code proof} is set only when the test passed.
     */
    Future<JsonObject> test(String tool, JsonObject body)
    {
        LiveConnector connector = connector(tool);

        String secret = body.getString("secret");

        JsonObject checked = LiveConfig.validate(connector, body.getJsonObject("config"), secret, allowLoopback());

        JsonObject config = checked.getJsonObject("config");

        String expiresAt = LiveConfig.expiresAt(body.getString("expiresAt"));

        LiveContext live;

        try
        {
            JsonArray origins = connector.allowedOrigins(config);

            live = LiveContext.of(tool, config, secret, new GuardedHttp(ctx.vertx(), tool, origins), ctx.accounts());
        }
        catch (IllegalArgumentException e)
        {
            // the connector could not derive an origin from the config; its message names no secret
            return Future.failedFuture(ApiException.badRequest("validation_failed", e.getMessage()).field("config"));
        }

        Future<JsonObject> run;

        try
        {
            run = connector.testConnection(live);
        }
        catch (RuntimeException e)
        {
            run = Future.failedFuture(e);
        }

        return run
                .map(result -> result == null ? failed(null, null, null) : result)
                .recover(err -> Future.succeededFuture(failure(tool, err)))
                .eventually(v ->
                {
                    live.close();

                    return Future.succeededFuture();
                })
                .map(result ->
                {
                    JsonObject out = response(result, checked.getJsonArray("warnings"), secret);

                    if (out.getBoolean("ok"))
                    {
                        JsonObject proof = proofs.issue(tool, config, secret, expiresAt, out.getString("expiresAt"));

                        out.put("proof", proof.getString("proof")).put("proofExpiresAt", proof.getLong("expiresAt"));
                    }

                    return out;
                });
    }

    /** The answer to the client: known fields only, the HTTP 200 rule, and no secret anywhere. */
    private static JsonObject response(JsonObject result, JsonArray warnings, String secret)
    {
        Integer status = result.getValue("status") instanceof Number n ? n.intValue() : null;

        boolean ok = Boolean.TRUE.equals(result.getValue("ok")) && status != null && status == 200;

        String hint = result.getString("hint");

        if (!ok && hint == null)
        {
            hint = status != null && status == 401 ? HINT_401
                    : status != null && status == 200 ? "The connector reported a failed test."
                    : status != null ? "The server answered " + status + "." : null;
        }

        JsonArray allWarnings = warnings.copy();

        if (result.getValue("warnings") instanceof JsonArray more)
        {
            more.forEach(allWarnings::add);
        }

        JsonObject out = new JsonObject()
                .put("ok", ok)
                .put("status", status)
                .put("code", ok ? null : result.getString("code", status == null ? null : String.valueOf(status)))
                .put("account", result.getValue("account"))
                .put("scopes", result.getValue("scopes") instanceof JsonArray a ? a : new JsonArray())
                .put("scopeNote", result.getString("scopeNote"))
                .put("fields", result.getValue("fields") instanceof JsonObject f ? f : new JsonObject())
                .put("expiresAt", result.getValue("expiresAt") == null ? null : String.valueOf(result.getValue("expiresAt")))
                .put("hint", hint)
                .put("warnings", allWarnings);

        return (JsonObject) scrub(out, secret);
    }

    /** A failed test from an error: a refused or failed request, or a connector fault. */
    private static JsonObject failure(String tool, Throwable err)
    {
        if (err instanceof LiveHttpException e)
        {
            Integer status = e.status() > 0 ? e.status() : null;

            return failed(status, e.code(), hint(e));
        }

        // no message: it could hold anything the connector put there
        log.warn("Live connector test failed for {}: {}", tool, err.getClass().getSimpleName());

        return failed(null, "error", "The connection test failed unexpectedly. Check the values and try again.");
    }

    private static String hint(LiveHttpException e)
    {
        if (e.status() == 401)
        {
            return HINT_401;
        }

        if (e.status() == 403)
        {
            return "403 Forbidden, the account can sign in but lacks permission to read this data";
        }

        return switch (e.code())
        {
            case LiveHttpException.REDIRECT ->
                    "The server answered with a redirect, which Pulse does not follow. Check the base URL.";
            case LiveHttpException.TIMEOUT -> "The server did not answer in time. Check the base URL and the network.";
            case LiveHttpException.CONNECTION -> "Pulse could not reach the server. Check the base URL and the network.";
            case LiveHttpException.ORIGIN, LiveHttpException.ADDRESS, LiveHttpException.METHOD, LiveHttpException.PATH ->
                    "The request was refused before it was sent: " + e.getMessage();
            default -> e.status() > 0 ? "The server answered " + e.status() + "." : e.getMessage();
        };
    }

    private static JsonObject failed(Integer status, String code, String hint)
    {
        return new JsonObject().put("ok", false).put("status", status).put("code", code).put("hint", hint);
    }

    /** Replaces every occurrence of the secret in any string with "••••". */
    private static Object scrub(Object value, String secret)
    {
        if (secret == null || secret.isEmpty())
        {
            return value;
        }

        if (value instanceof String s)
        {
            return s.contains(secret) ? s.replace(secret, "••••") : s;
        }

        if (value instanceof JsonObject o)
        {
            JsonObject out = new JsonObject();

            o.forEach(e -> out.put(scrub(e.getKey(), secret).toString(), scrub(e.getValue(), secret)));

            return out;
        }

        if (value instanceof JsonArray a)
        {
            JsonArray out = new JsonArray();

            a.forEach(v -> out.add(scrub(v, secret)));

            return out;
        }

        return value;
    }

    // ------------------------------------------------------------------ save

    /**
     * Stores the connection after checking the proof. The proof is used up by this call even when the
     * save fails. Resolves to {@link #connection}.
     */
    Future<JsonObject> save(JsonObject user, String tool, JsonObject body)
    {
        LiveConnector connector = connector(tool);

        String secret = body.getString("secret");

        JsonObject config = LiveConfig.validate(connector, body.getJsonObject("config"), secret, allowLoopback())
                .getJsonObject("config");

        String expiresAt = LiveConfig.expiresAt(body.getString("expiresAt"));

        // refuse before the proof is used up, so the Admin can set the key and save again
        if (!ctx.secrets().configured())
        {
            return Future.failedFuture(SecretKeyException.missing());
        }

        JsonObject proof = proofs.consume(body.getString("proof"), tool, config, secret, expiresAt);

        if (proof == null)
        {
            return Future.failedFuture(ApiException.unprocessable("test_required",
                    "Run Test connection with these values, then save within 10 minutes.").field("proof"));
        }

        String expiry = expiresAt != null ? expiresAt : proof.getString("testedExpiresAt");

        UUID actor = UUID.fromString(user.getString("id"));

        return ctx.sql().inTransaction(tx ->
        {
            ConnectionStore store = ctx.connections(tx);

            IntegrationsRepository repo = new IntegrationsRepository(tx);

            return store.view(tool).compose(before ->
            {
                boolean added = before == null || !Boolean.TRUE.equals(before.getBoolean("hasSecret"));

                String change = added ? "added" : "changed";

                return store.save(tool, config, secret, expiry)
                        .compose(v -> repo.log(tool, change, (added ? "Credentials added" : "Credentials changed")
                                + " (live connection test passed)", actor))
                        .compose(v -> AuditService.record(tx, AuditService.entry(user, AuditEvents.CREDENTIAL_CHANGED,
                                "integration", tool, new JsonObject()
                                        .put("change", change)
                                        .put("live", true)
                                        .put("fields", new JsonArray(List.copyOf(config.fieldNames()))))));
            });
        }).compose(v -> connection(tool));
    }

    // ------------------------------------------------------------------ disconnect

    /**
     * Deletes the stored secret; the config and every row already ingested stay. 409 when there is no
     * stored secret. Resolves to {@link #connection}.
     */
    Future<JsonObject> disconnect(JsonObject user, String tool)
    {
        connector(tool);

        UUID actor = UUID.fromString(user.getString("id"));

        return ctx.sql().inTransaction(tx ->
        {
            IntegrationsRepository repo = new IntegrationsRepository(tx);

            return ctx.connections(tx).clearSecret(tool).compose(changed ->
            {
                if (!changed)
                {
                    return Future.failedFuture(ApiException.conflict("not_connected", "This tool is not connected."));
                }

                return repo.log(tool, "disconnected", "Disconnected (stored secret deleted; synced data kept)", actor)
                        .compose(v -> AuditService.record(tx, AuditService.entry(user, AuditEvents.CREDENTIAL_CHANGED,
                                "integration", tool, new JsonObject().put("change", "disconnected").put("live", true))));
            });
        }).compose(v -> connection(tool));
    }

    // ------------------------------------------------------------------ helpers

    private LiveConnector connector(String tool)
    {
        LiveConnector connector = ctx.connectors().get(tool);

        if (connector == null)
        {
            throw ApiException.notFound("not_found", "No live connector for this tool.");
        }

        return connector;
    }

    private static JsonObject describe(LiveConnector c)
    {
        return new JsonObject()
                .put("tool", c.tool())
                .put("source", c.source())
                .put("configSchema", c.configSchema())
                .put("secretSpec", c.secretSpec())
                .put("requiredScopes", c.requiredScopes());
    }

    private boolean allowLoopback()
    {
        return LoopbackFlag.allowed(ctx.config(), RuntimeProfile.from(ctx.config()));
    }
}
