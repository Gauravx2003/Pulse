package com.pulse.modules.pull_requests;

import com.pulse.calc.work.PrFormulas;
import com.pulse.modules.action_center.ActionItems;
import com.pulse.modules.work.Reports;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Action center item from pull requests (task 13.1, spec insight/action-center): rubber-stamp approvals
 * by the reports in the active sprint (big PRs approved with no comments) are one Low team item.
 */
public final class RubberStampActionProvider implements ActionProvider
{

    public static final String KEY = "pull_requests.rubber-stamp";

    @Override
    public String key()
    {
        return KEY;
    }

    @Override
    public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
    {
        return Reports.of(ctx, user).compose(people -> ctx.calc().get("core.periods", new JsonObject()).compose(periods ->
        {
            Object sprint = ((JsonObject) periods).getValue("activeSprint");

            if (!(sprint instanceof String key) || people.isEmpty())
            {
                return Future.succeededFuture(List.<JsonObject>of());
            }

            List<Future<Object>> stats = new ArrayList<>();

            for (JsonObject person : people)
            {
                stats.add(ctx.calc().get(PrFormulas.PERSON, new JsonObject().put("person", person.getString("id")).put("period", key)));
            }

            return Future.all(stats).map(done ->
            {
                int stamps = 0;

                for (Future<Object> s : stats)
                {
                    stamps += s.result() instanceof JsonObject o ? o.getInteger("stamps", 0) : 0;
                }

                return stamps == 0 ? List.<JsonObject>of() : List.of(ActionItems.item(KEY, ActionItems.LOW, "Pull requests", null,
                        ActionItems.TEAM, stamps + " rubber-stamp " + (stamps == 1 ? "approval" : "approvals")
                                + " this sprint (big PRs, no comments)", "Open", ActionItems.target("pull_requests", "team", null)));
            });
        }));
    }
}
