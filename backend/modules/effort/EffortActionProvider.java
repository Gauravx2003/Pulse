package com.pulse.modules.effort;

import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.modules.action_center.ActionItems;
import com.pulse.modules.work.Reports;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Action center items from effort (task 13.1, spec insight/action-center): days in the active sprint
 * with commits but no Jira worklog are Low per report; done extra-mile items awaiting confirmation are
 * one Medium team item for a holder of {@code effort.confirm}.
 */
public final class EffortActionProvider implements ActionProvider
{

    public static final String CODE_ONLY = "effort.code-only";

    public static final String TO_CONFIRM = "effort.to-confirm";

    private final String key;

    private EffortActionProvider(String key)
    {
        this.key = key;
    }

    public static List<ActionProvider> all()
    {
        return List.of(new EffortActionProvider(CODE_ONLY), new EffortActionProvider(TO_CONFIRM));
    }

    @Override
    public String key()
    {
        return key;
    }

    @Override
    public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
    {
        return Reports.of(ctx, user).compose(people -> CODE_ONLY.equals(key) ? codeOnly(ctx, people) : toConfirm(ctx, user, people));
    }

    private Future<List<JsonObject>> codeOnly(ModuleContext ctx, List<JsonObject> people)
    {
        return ctx.calc().get("core.periods", new JsonObject()).compose(periods ->
        {
            Object sprint = ((JsonObject) periods).getValue("activeSprint");

            if (!(sprint instanceof String key) || people.isEmpty())
            {
                return Future.succeededFuture(List.<JsonObject>of());
            }

            List<Future<Object>> work = new ArrayList<>();

            for (JsonObject person : people)
            {
                work.add(ctx.calc().get(WorkloadFormulas.PERIOD_WORK, new JsonObject().put("person", person.getString("id"))
                        .put("period", key)));
            }

            return Future.all(work).map(done ->
            {
                List<JsonObject> items = new ArrayList<>();

                for (int i = 0; i < people.size(); i++)
                {
                    int days = work.get(i).result() instanceof JsonObject w ? w.getInteger("codeOnly", 0) : 0;

                    if (days > 0)
                    {
                        JsonObject person = people.get(i);

                        items.add(ActionItems.item(CODE_ONLY, ActionItems.LOW, "Effort", person.getString("id"), person.getString("name"),
                                days + (days == 1 ? " day" : " days") + " with commits but no Jira worklog", "Open",
                                ActionItems.target("effort", "person", person.getString("id"))));
                    }
                }

                return items;
            });
        });
    }

    private Future<List<JsonObject>> toConfirm(ModuleContext ctx, JsonObject user, List<JsonObject> people)
    {
        if (!PolicyService.permissions(user).contains("effort.confirm") || people.isEmpty())
        {
            return Future.succeededFuture(List.of());
        }

        UUID[] ids = people.stream().map(p -> UUID.fromString(p.getString("id"))).toArray(UUID[]::new);

        return ctx.sql().queryOne("""
                SELECT count(*) AS waiting FROM extra_items
                WHERE state = 'pending' AND is_done AND person_id = ANY($1)""", Tuple.of(ids))
                .map(row ->
                {
                    long waiting = row.getLong("waiting");

                    return waiting == 0 ? List.<JsonObject>of() : List.of(ActionItems.item(TO_CONFIRM, ActionItems.MEDIUM,
                            "Extra-mile points", null, ActionItems.TEAM,
                            waiting + " done " + (waiting == 1 ? "item" : "items") + " waiting for your confirmation", "Review",
                            ActionItems.target("effort", "team", null)));
                });
    }
}
