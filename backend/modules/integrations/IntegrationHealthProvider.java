package com.pulse.modules.integrations;

import com.pulse.platform.jobs.JobRunRepository;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Action center items for Admins from the Integrations tiles (task 13.1, spec insight/action-center
 * "Appraisal, attendance and integration triggers"): a failing sync is High and a token near or past
 * expiry is Low, each with Fix. A scheduled job whose newest run failed after its retries is High too
 * (task 5.5). The state and status text are the tiles' own, so the item says what the
 * Integrations screen says. Item shape as {@code com.pulse.modules.action_center.ActionItems}.
 */
final class IntegrationHealthProvider implements ActionProvider
{

    static final String KEY = "integrations.health";

    static final String JOB_KEY = "integrations.job-failed";

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

        Future<JsonArray> failedJobs = new JobRunRepository(ctx.sql()).failing();

        return new IntegrationsService(ctx, IntegrationsService.connectors(ctx)).tiles().compose(body -> failedJobs.map(jobs ->
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

            // a scheduled job that failed after its retries (task 5.5, spec jobs-scheduler "Failed job")
            for (Object item : jobs)
            {
                JsonObject run = (JsonObject) item;

                items.add(new JsonObject()
                        .put("key", JOB_KEY)
                        .put("priority", "High")
                        .put("source", "Scheduler")
                        .putNull("personId")
                        .put("personName", run.getString("job"))
                        .put("text", "Job failed after " + run.getInteger("attempts")
                                + (run.getInteger("attempts", 0) == 1 ? " attempt: " : " attempts: ") + run.getString("error", "no message"))
                        .put("button", "Fix")
                        .put("target", new JsonObject().put("module", "integrations").put("id", "jobs")));
            }

            return items;
        }));
    }
}
