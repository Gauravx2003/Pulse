package com.pulse.modules.bugs;

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
 * Action center items from Bugs &amp; support (task 13.1, spec insight/action-center "Effort, training,
 * review and quality triggers"): open high-severity bugs in the reports' code are one Medium team item;
 * each open dispute of a report's attribution is a Medium item with Review.
 */
public final class BugActionProvider implements ActionProvider
{

    public static final String HIGH_SEVERITY = "bugs.high-severity";

    public static final String DISPUTES = "bugs.disputes";

    private final String key;

    private BugActionProvider(String key)
    {
        this.key = key;
    }

    public static List<ActionProvider> all()
    {
        return List.of(new BugActionProvider(HIGH_SEVERITY), new BugActionProvider(DISPUTES));
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
            if (people.isEmpty())
            {
                return Future.succeededFuture(List.<JsonObject>of());
            }

            return HIGH_SEVERITY.equals(key) ? highSeverity(ctx, people) : disputes(ctx, user);
        });
    }

    private Future<List<JsonObject>> highSeverity(ModuleContext ctx, List<JsonObject> people)
    {
        UUID[] ids = people.stream().map(p -> UUID.fromString(p.getString("id"))).toArray(UUID[]::new);

        return ctx.sql().queryList("""
                SELECT key FROM bugs_current
                WHERE status <> 'Resolved' AND severity IN ('Critical', 'High') AND origin_person_id = ANY($1)
                ORDER BY reported_on, key""", Tuple.of(ids))
                .map(rows ->
                {
                    if (rows.isEmpty())
                    {
                        return List.of();
                    }

                    List<String> keys = rows.stream().map(r -> ((JsonObject) r).getString("key")).toList();

                    return List.of(ActionItems.item(HIGH_SEVERITY, ActionItems.MEDIUM, "Bugs", null, ActionItems.TEAM,
                            keys.size() + " high-severity " + (keys.size() == 1 ? "bug" : "bugs") + " open (" + String.join(", ", keys) + ")",
                            "Open", ActionItems.target("bugs", "team", null)));
                });
    }

    private Future<List<JsonObject>> disputes(ModuleContext ctx, JsonObject user)
    {
        return ctx.policy().scope(user, PolicyService.DOMAIN_PERSON)
                .compose(scope -> new BugRepository(ctx.sql()).openDisputes(scope))
                .map(rows ->
                {
                    List<JsonObject> items = new ArrayList<>();

                    for (Object row : rows)
                    {
                        JsonObject d = (JsonObject) row;

                        if (d.getString("personId").equals(user.getString("personId")))
                        {
                            continue;
                        }

                        items.add(ActionItems.item(DISPUTES, ActionItems.MEDIUM, "Bugs", d.getString("personId"), d.getString("personName"),
                                "Disputed attribution on " + d.getString("bugKey"), "Review",
                                ActionItems.target("bugs", "team", d.getString("personId")).put("id", d.getString("id"))));
                    }

                    return items;
                });
    }
}
