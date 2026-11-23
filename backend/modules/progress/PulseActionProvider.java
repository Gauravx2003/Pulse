package com.pulse.modules.progress;

import com.pulse.modules.action_center.ActionItems;
import com.pulse.modules.work.Reports;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Action center item from the weekly pulse (task 13.1, spec insight/action-center "Work and people
 * triggers"): a report whose latest answer has mood 2 or lower or workload 5 is High. Answers are read
 * through a scope of the pulse domain, so only the person's manager and {@code all.view} see them.
 */
public final class PulseActionProvider implements ActionProvider
{

    public static final String KEY = "progress.low-pulse";

    static final int LOW_MOOD = 2;

    static final int HIGH_WORKLOAD = 5;

    @Override
    public String key()
    {
        return KEY;
    }

    @Override
    public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
    {
        return Reports.of(ctx, user).compose(people -> people.isEmpty() ? Future.succeededFuture(List.<JsonObject>of())
                : ctx.policy().scope(user, PolicyService.DOMAIN_PULSE).compose(scope ->
                {
                    PulseRepository repo = new PulseRepository(ctx.sql());

                    List<Future<JsonObject>> latest = new ArrayList<>();

                    for (JsonObject person : people)
                    {
                        latest.add(repo.latest(scope, UUID.fromString(person.getString("id"))));
                    }

                    return Future.all(latest).map(done ->
                    {
                        List<JsonObject> items = new ArrayList<>();

                        for (int i = 0; i < people.size(); i++)
                        {
                            JsonObject answer = latest.get(i).result();

                            if (answer == null || (answer.getInteger("mood") > LOW_MOOD && answer.getInteger("workload") < HIGH_WORKLOAD))
                            {
                                continue;
                            }

                            String note = answer.getString("note");

                            JsonObject person = people.get(i);

                            items.add(ActionItems.item(KEY, ActionItems.HIGH, "Pulse", person.getString("id"), person.getString("name"),
                                    "Weekly pulse: mood " + answer.getInteger("mood") + "/5, workload " + answer.getInteger("workload") + "/5"
                                            + (note == null || note.isBlank() ? "" : " — “" + note.strip() + "”"),
                                    "Open", ActionItems.target("progress", null, person.getString("id"))));
                        }

                        return items;
                    });
                }));
    }
}
