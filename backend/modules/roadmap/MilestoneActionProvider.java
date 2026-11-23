package com.pulse.modules.roadmap;

import com.pulse.modules.action_center.ActionItems;
import com.pulse.modules.work.Reports;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Action center item for a manager (task 15.2, spec growth/roadmap "Propose"): each milestone a report
 * proposed is a Medium item with Confirm, until confirmed.
 */
public final class MilestoneActionProvider implements ActionProvider
{

    public static final String KEY = "roadmap.milestone-proposed";

    @Override
    public String key()
    {
        return KEY;
    }

    @Override
    public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
    {
        return Reports.of(ctx, user).compose(people -> people.isEmpty() ? Future.succeededFuture(List.<JsonObject>of())
                : ctx.policy().scope(user, PolicyService.DOMAIN_PERSON)
                        .compose(scope -> new RoadmapRepository(ctx.sql()).proposed(scope))
                        .map(rows ->
                        {
                            List<JsonObject> items = new ArrayList<>();

                            for (Object row : rows)
                            {
                                JsonObject m = (JsonObject) row;

                                if (m.getString("personId").equals(user.getString("personId")))
                                {
                                    continue;
                                }

                                items.add(ActionItems.item(KEY, ActionItems.MEDIUM, "Road map", m.getString("personId"),
                                        m.getString("personName"), "Milestone proposed: " + m.getString("title") + " (" + m.getString("when") + ")",
                                        "Confirm", ActionItems.target("roadmap", null, m.getString("personId")).put("id", m.getString("id"))));
                            }

                            return items;
                        }));
    }
}
