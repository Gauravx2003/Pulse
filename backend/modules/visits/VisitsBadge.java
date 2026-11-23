package com.pulse.modules.visits;

import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.BadgeProvider;
import com.pulse.platform.module.ModuleContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The Customer visits menu badge (spec platform/notifications "Menu badges": visits needing action):
 * the caller's overdue reports, customer feedback they owe as account owner and visits waiting for their
 * verification, counted with the same queries as the Action Center items ({@link VisitActionProvider}).
 */
public final class VisitsBadge implements BadgeProvider
{
    @Override
    public String screen()
    {
        return "visits";
    }

    @Override
    public Future<Integer> countFor(JsonObject user, ModuleContext ctx)
    {
        List<Future<List<JsonObject>>> parts = new ArrayList<>();

        for (ActionProvider provider : VisitActionProvider.all())
        {
            parts.add(provider.actionsFor(user, ctx));
        }

        return Future.all(parts).map(all ->
        {
            int count = 0;

            for (int i = 0; i < parts.size(); i++)
            {
                count += all.<List<JsonObject>>resultAt(i).size();
            }

            return count;
        });
    }
}
