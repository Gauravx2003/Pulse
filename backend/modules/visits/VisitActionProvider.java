package com.pulse.modules.visits;

import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Customer-visit items for the Action Center (task 18.1; Track 2's Action Center, group 13, shows them):
 * the engineer's overdue reports, the account owner's pending customer feedback and, for holders of
 * effort.confirm, visits waiting to be verified (never their own).
 *
 * <p>Item shape: {@code {key, priority, source, personId, personName, text, button, target: {module, id}}}.
 */
public final class VisitActionProvider implements ActionProvider
{
    static final String REPORT_DUE = "visits.report-due";

    static final String FEEDBACK_DUE = "visits.feedback-due";

    static final String VERIFY_DUE = "visits.verify-due";

    private final String key;

    VisitActionProvider(String key)
    {
        this.key = key;
    }

    static List<ActionProvider> all()
    {
        return List.of(new VisitActionProvider(REPORT_DUE), new VisitActionProvider(FEEDBACK_DUE),
                new VisitActionProvider(VERIFY_DUE));
    }

    @Override
    public String key()
    {
        return key;
    }

    @Override
    public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
    {
        VisitRepository repo = new VisitRepository(ctx.sql());

        return switch (key)
        {
            case REPORT_DUE -> user.getString("personId") == null
                    ? Future.succeededFuture(List.of())
                    : repo.reportDue(UUID.fromString(user.getString("personId")), VisitService.today(ctx))
                            .map(rows -> items(rows, "Medium", "Visit report due: ", "Open"));
            case FEEDBACK_DUE -> repo.feedbackDue(UUID.fromString(user.getString("id")))
                    .map(rows -> items(rows, "Medium", "Customer feedback needed: ", "Open"));
            default -> !PolicyService.permissions(user).contains("effort.confirm")
                    ? Future.succeededFuture(List.of())
                    : ctx.policy().scope(user, PolicyService.DOMAIN_VISITS)
                            .compose(repo::awaitingDecision)
                            .map(rows -> withoutOwn(rows, user))
                            .map(rows -> items(rows, "Medium", "Verify visit points: ", "Verify"));
        };
    }

    private static JsonArray withoutOwn(JsonArray rows, JsonObject user)
    {
        JsonArray others = new JsonArray();

        for (Object row : rows)
        {
            if (!((JsonObject) row).getString("personId").equals(user.getString("personId")))
            {
                others.add(row);
            }
        }

        return others;
    }

    private List<JsonObject> items(JsonArray rows, String priority, String prefix, String button)
    {
        List<JsonObject> items = new ArrayList<>();

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            items.add(new JsonObject()
                    .put("key", key)
                    .put("priority", priority)
                    .put("source", "Customer visits")
                    .put("personId", row.getString("personId"))
                    .put("personName", row.getString("personName"))
                    .put("text", prefix + row.getString("customer") + " (" + row.getString("from") + " – " + row.getString("to") + ")")
                    .put("button", button)
                    .put("target", new JsonObject().put("module", "visits").put("id", row.getString("id"))));
        }

        return items;
    }
}
