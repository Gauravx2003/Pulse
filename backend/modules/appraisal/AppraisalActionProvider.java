package com.pulse.modules.appraisal;

import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.Set;

/**
 * Appraisal items for the Action Center (task 22.2), ported from the prototype (app.js ~2108–2109):
 *
 * <ul>
 *   <li>{@code appraisal.drafts}: a manager who proposes but does not approve (appraisal.manage without
 *       appraisal.approve) and still has proposals in Draft, Low.</li>
 *   <li>{@code appraisal.waiting}: an approver (appraisal.approve) with Submitted proposals, Medium.</li>
 * </ul>
 *
 * <p>Item shape: {@code {key, priority, source, personId, personName, text, button, target: {module, id}}}.
 */
public final class AppraisalActionProvider implements ActionProvider
{
    static final String DRAFTS = "appraisal.drafts";

    static final String WAITING = "appraisal.waiting";

    private final String key;

    AppraisalActionProvider(String key)
    {
        this.key = key;
    }

    static List<ActionProvider> all()
    {
        return List.of(new AppraisalActionProvider(DRAFTS), new AppraisalActionProvider(WAITING));
    }

    @Override
    public String key()
    {
        return key;
    }

    @Override
    public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
    {
        Set<String> held = PolicyService.permissions(user);

        boolean drafts = DRAFTS.equals(key) && held.contains("appraisal.manage") && !held.contains("appraisal.approve");

        boolean waiting = WAITING.equals(key) && held.contains("appraisal.approve");

        if (!drafts && !waiting)
        {
            return Future.succeededFuture(List.of());
        }

        // No items while the appraisal tables are not installed (a test schema with other modules only).
        return ctx.sql().queryOne("SELECT to_regclass('appraisal_proposals') IS NOT NULL AS installed", Tuple.tuple()).compose(t ->
        {
            if (!t.getBoolean("installed"))
            {
                return Future.succeededFuture(List.of());
            }

            return new AppraisalService(ctx).statuses(user).map(statuses ->
            {
                int n = statuses.getInteger(drafts ? AppraisalService.DRAFT : AppraisalService.SUBMITTED, 0);

                if (n == 0)
                {
                    return List.<JsonObject>of();
                }

                return List.of(new JsonObject()
                        .put("key", key)
                        .put("priority", drafts ? "Low" : "Medium")
                        .put("source", "Appraisal")
                        .put("personId", null)
                        .put("personName", drafts ? "Team" : "HR")
                        .put("text", drafts ? n + " increment proposal" + (n == 1 ? "" : "s") + " still in draft"
                                : n + " proposal" + (n == 1 ? "" : "s") + " waiting for approval")
                        .put("button", "Open")
                        .put("target", new JsonObject().put("module", "appraisal").put("id", null)));
            });
        });
    }
}
