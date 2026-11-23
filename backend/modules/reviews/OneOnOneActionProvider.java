package com.pulse.modules.reviews;

import com.pulse.calc.core.WorkingDays;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The "1:1 overdue" Action Center item (spec culture/one-on-ones-kudos "Past 1:1s and overdue signal";
 * task 20.2), ported from the prototype's action items (app.js ~2097): for a manager (holder of
 * {@code oneonone} with {@code team.view} or {@code all.view}), each person they see whose last 1:1 was
 * more than {@value #OVERDUE_DAYS} working days ago is a Medium item. People with no 1:1 yet and the
 * viewer themselves are left out.
 *
 * <p>Item shape: {@code {key, priority, source, personId, personName, text, button, target: {module, id}}}.
 */
public final class OneOnOneActionProvider implements ActionProvider
{
    static final String KEY = "oneonone.overdue";

    /** Working days after the last 1:1 before it is overdue. */
    static final int OVERDUE_DAYS = 10;

    @Override
    public String key()
    {
        return KEY;
    }

    @Override
    public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
    {
        Set<String> held = PolicyService.permissions(user);

        if (!held.contains("oneonone") || !held.contains("team.view") && !held.contains("all.view"))
        {
            return Future.succeededFuture(List.of());
        }

        LocalDate today = ctx.calc() == null ? LocalDate.now(PolicyService.ZONE) : ctx.calc().clock().today();

        OneOnOneRepository repo = new OneOnOneRepository(ctx.sql());

        return ctx.policy().scope(user, PolicyService.DOMAIN_REVIEWS)
                .compose(scope -> Future.all(repo.lastHeld(scope, today), repo.holidays()))
                .map(all ->
                {
                    Set<LocalDate> holidays = WorkingDays.holidays(all.resultAt(1), null);

                    List<JsonObject> items = new ArrayList<>();

                    for (Object o : all.<JsonArray>resultAt(0))
                    {
                        JsonObject row = (JsonObject) o;

                        if (row.getString("personId").equals(user.getString("personId")))
                        {
                            continue;
                        }

                        int since = daysSince(LocalDate.parse(row.getString("lastOn")), today, holidays);

                        if (since > OVERDUE_DAYS)
                        {
                            items.add(new JsonObject()
                                    .put("key", KEY)
                                    .put("priority", "Medium")
                                    .put("source", "1:1s")
                                    .put("personId", row.getString("personId"))
                                    .put("personName", row.getString("personName"))
                                    .put("text", "Last 1:1 " + since + " working days ago")
                                    .put("button", "Plan 1:1")
                                    .put("target", new JsonObject().put("module", "reviews").put("id", row.getString("personId"))));
                        }
                    }

                    return items;
                });
    }

    /** Working days after {@code last} up to {@code today} (prototype {@code daysSince}: the working days between, minus one). */
    static int daysSince(LocalDate last, LocalDate today, Set<LocalDate> holidays)
    {
        return Math.max(0, WorkingDays.count(last, today, holidays, null) - 1);
    }
}
