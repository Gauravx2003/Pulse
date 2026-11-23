package com.pulse.modules.integrations;

import com.pulse.platform.module.JobSpec;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.notify.NotificationTriggers;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;

/**
 * The expiring-token check (task 5.5, spec platform/secrets-credentials "Rotation and expiry
 * display"): once a day, for each connected tool whose tile is in the "Token expires in N days" state
 * ({@link IntegrationsService#EXPIRY_WARNING_DAYS}), it writes a {@code warning} to the credential
 * activity log and notifies every active Admin, at most once a day per tool, so a manual re-run the
 * same day changes nothing. The Admin Action center item comes from the tile state itself
 * ({@link IntegrationHealthProvider}).
 *
 * <p>Counts: {@code {expiring, warned, notified}}.
 */
final class TokenExpiryJob
{
    static final String NAME = "integrations.token-expiry-check";

    private TokenExpiryJob()
    {
    }

    static JobSpec spec()
    {
        return JobSpec.of(NAME, "0 8 * * *",
                "Warns Admins about tool tokens that expire within " + IntegrationsService.EXPIRY_WARNING_DAYS + " days",
                TokenExpiryJob::run);
    }

    static Future<JsonObject> run(ModuleContext ctx)
    {
        return new IntegrationsService(ctx, IntegrationsService.connectors(ctx)).tiles().compose(body ->
        {
            List<JsonObject> expiring = new ArrayList<>();

            for (Object item : body.getJsonArray("tiles"))
            {
                JsonObject tile = (JsonObject) item;

                if ("token_expiring".equals(tile.getString("state")))
                {
                    expiring.add(tile);
                }
            }

            JsonObject counts = new JsonObject().put("expiring", expiring.size()).put("warned", 0).put("notified", 0);

            if (expiring.isEmpty())
            {
                return Future.succeededFuture(counts);
            }

            return ctx.sql().queryList("SELECT u.id FROM users u JOIN roles r ON r.id = u.role_id "
                    + "WHERE r.name = 'Admin' AND u.status = 'Active' ORDER BY u.id", Tuple.tuple()).compose(admins ->
            {
                Future<Void> chain = Future.succeededFuture();

                for (JsonObject tile : expiring)
                {
                    chain = chain.compose(v -> warn(ctx, tile, admins, counts));
                }

                return chain.map(v -> counts);
            });
        });
    }

    /** Logs and notifies once per tool and day, in one transaction. */
    private static Future<Void> warn(ModuleContext ctx, JsonObject tile, JsonArray admins, JsonObject counts)
    {
        String tool = tile.getString("id");

        String status = tile.getString("status");

        return ctx.sql().inTransaction(tx -> tx.queryOne("SELECT 1 AS done FROM credential_log WHERE tool = $1 "
                        + "AND action = 'warning' AND at >= date_trunc('day', now()) LIMIT 1", Tuple.of(tool))
                .compose(done ->
                {
                    if (done != null)
                    {
                        return Future.<Void>succeededFuture();
                    }

                    counts.put("warned", counts.getInteger("warned") + 1);

                    Future<Void> chain = tx.execute("INSERT INTO credential_log (tool, action, message) VALUES ($1, 'warning', $2)",
                            Tuple.of(tool, status)).mapEmpty();

                    for (Object id : admins)
                    {
                        String adminId = ((JsonObject) id).getString("id");

                        chain = chain.compose(v -> ctx.notifications()
                                .notify(tx, NotificationTriggers.tokenExpiring(adminId, tile.getString("name"), status))
                                .map(sent ->
                                {
                                    if (sent != null)
                                    {
                                        counts.put("notified", counts.getInteger("notified") + 1);
                                    }

                                    return null;
                                }));
                    }

                    return chain;
                }));
    }
}
