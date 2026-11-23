package com.pulse.modules.work;

import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.work.WorkViewFormulas;
import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.modules.action_center.ActionItems;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Action center items from the live sprint (task 13.1, spec insight/action-center "Work and people
 * triggers"): a report over the overload threshold (the Critical band, above rule {@code over}) is High
 * with Rebalance, never an onboarding joiner; 2 or more stale issues is Medium.
 */
public final class WorkloadActionProvider implements ActionProvider
{

    public static final String OVERLOAD = "work.overload";

    public static final String STALE = "work.stale";

    private static final String CRITICAL = "Critical";

    private static final int STALE_ITEMS = 2;

    private final String key;

    private WorkloadActionProvider(String key)
    {
        this.key = key;
    }

    public static List<ActionProvider> all()
    {
        return List.of(new WorkloadActionProvider(OVERLOAD), new WorkloadActionProvider(STALE));
    }

    @Override
    public String key()
    {
        return key;
    }

    @Override
    public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
    {
        return Reports.of(ctx, user).compose(people ->
        {
            List<Future<Object>> live = new ArrayList<>();

            List<Future<Object>> onboarding = new ArrayList<>();

            for (JsonObject person : people)
            {
                JsonObject params = new JsonObject().put("person", person.getString("id"));

                live.add(ctx.calc().get(WorkloadFormulas.LIVE_SPRINT, params));

                onboarding.add(OVERLOAD.equals(key) ? ctx.calc().get(WorkViewFormulas.ONBOARDING, params)
                        : Future.succeededFuture(null));
            }

            List<Future<?>> all = new ArrayList<>(live);

            all.addAll(onboarding);

            return Future.all(all).map(done ->
            {
                List<JsonObject> items = new ArrayList<>();

                for (int i = 0; i < people.size(); i++)
                {
                    JsonObject person = people.get(i);

                    if (!(live.get(i).result() instanceof JsonObject m) || ResultGuard.isNotApplicable(m))
                    {
                        continue;
                    }

                    String sprint = "Sprint " + m.getString("sprint", "").replace("s:S", "");

                    if (OVERLOAD.equals(key))
                    {
                        boolean joiner = onboarding.get(i).result() instanceof JsonObject plan && !ResultGuard.isNotApplicable(plan);

                        JsonArray band = m.getJsonArray("band", new JsonArray());

                        if (!joiner && band.size() > 1 && CRITICAL.equals(band.getString(1)))
                        {
                            items.add(ActionItems.item(key, ActionItems.HIGH, "Workload", person.getString("id"), person.getString("name"),
                                    "At " + m.getValue("util") + "% of capacity in " + sprint + " — "
                                            + String.format(Locale.ROOT, "%.1f", m.getDouble("work", 0.0)) + " d of work for "
                                            + m.getValue("cap") + " d",
                                    "Rebalance", ActionItems.target("utilization", null, null)));
                        }
                    }
                    else
                    {
                        JsonArray stale = m.getJsonArray("stale", new JsonArray());

                        if (stale.size() >= STALE_ITEMS)
                        {
                            items.add(ActionItems.item(key, ActionItems.MEDIUM, "Work items", person.getString("id"),
                                    person.getString("name"), stale.size() + " issues without an update ("
                                            + String.join(", ", stale.stream().map(String::valueOf).toList()) + ")",
                                    "Open", ActionItems.target("work-items", null, person.getString("id"))));
                        }
                    }
                }

                return items;
            });
        });
    }
}
