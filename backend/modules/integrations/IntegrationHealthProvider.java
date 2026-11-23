package com.pulse.modules.integrations;

import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Action center items for Admins from the Integrations tiles (task 13.1, spec insight/action-center
 * "Appraisal, attendance and integration triggers"): a failing sync is High and a token near or past
 * expiry is Low, each with Fix. The state and status text are the tiles' own, so the item says what the
 * Integrations screen says. Item shape as {@code com.pulse.modules.action_center.ActionItems}.
 */
final class IntegrationHealthProvider implements ActionProvider
{

    static final String KEY = "integrations.health";

    @Override
    public String key()
    {
        return KEY;
    }

    @Override
    public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
    {
        if (!PolicyService.permissions(user).contains("integrations.manage"))
        {
            return Future.succeededFuture(List.of());
        }

        return new IntegrationsService(ctx, IntegrationsService.connectors(ctx)).tiles().map(body ->
        {
            List<JsonObject> items = new ArrayList<>();

            for (Object item : body.getJsonArray("tiles"))
            {
                JsonObject tile = (JsonObject) item;

                String state = tile.getString("state");

                if (!"sync_failed".equals(state) && !"token_expiring".equals(state))
                {
                    continue;
                }

                items.add(new JsonObject()
                        .put("key", KEY)
                        .put("priority", "sync_failed".equals(state) ? "High" : "Low")
                        .put("source", "Integrations")
                        .putNull("personId")
                        .put("personName", tile.getString("name"))
                        .put("text", tile.getString("status"))
                        .put("button", "Fix")
                        .put("target", new JsonObject().put("module", "integrations").put("id", tile.getString("id"))));
            }

            return items;
        });
    }
}
