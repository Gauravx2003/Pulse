package com.pulse.modules.okrs;

import com.pulse.calc.work.OkrFormulas;
import com.pulse.calc.work.OkrProgress;
import com.pulse.modules.action_center.ActionItems;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The "OKR off track" next step (task 14.3, spec insight/progress-pulse "Next steps"): one item per
 * Individual objective of the current quarter whose status from {@code work.okrSummary} is Off track,
 * as in the prototype's {@code nextActions} ("OKR off track: &lt;objective&gt; (30%)", opens OKRs).
 */
public final class OkrOffTrackProvider implements ActionProvider
{

    public static final String KEY = "okrs.off-track";

    @Override
    public String key()
    {
        return KEY;
    }

    @Override
    public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
    {
        String personId = user == null ? null : user.getString("personId");

        UUID owner = OkrService.parseId(personId);

        if (owner == null)
        {
            return Future.succeededFuture(List.of());
        }

        LocalDate today = ctx.calc().clock().today();

        String quarter = today.getYear() + "-Q" + ((today.getMonthValue() - 1) / 3 + 1);

        Future<Object> summary = ctx.calc().get(OkrFormulas.SUMMARY, new JsonObject()
                .put("person", personId)
                .put("period", "q:" + quarter));

        Future<JsonArray> objectives = new OkrRepository(ctx.sql()).listForOwner(owner, quarter);

        return Future.all(summary, objectives).map(all ->
        {
            Map<String, String> titles = new HashMap<>();

            for (Object item : objectives.result())
            {
                JsonObject objective = (JsonObject) item;

                titles.put(objective.getString("id"), objective.getString("title"));
            }

            List<JsonObject> actions = new ArrayList<>();

            JsonArray details = summary.result() instanceof JsonObject s ? s.getJsonArray("items", new JsonArray()) : new JsonArray();

            for (Object item : details)
            {
                JsonObject detail = (JsonObject) item;

                if (!OkrProgress.STATUS_OFF_TRACK.equals(detail.getString("status")))
                {
                    continue;
                }

                long percent = Math.round(detail.getDouble("progress") * 100);

                String title = "OKR off track: " + titles.get(detail.getString("id")) + " (" + percent + "%)";

                actions.add(ActionItems.item(KEY, ActionItems.LOW, "OKRs", personId, user.getString("name"), title, "Open",
                                ActionItems.target("okrs", null, personId).put("id", detail.getString("id")))
                        .put("title", title)
                        .put("route", "okrs")
                        .put("objectiveId", detail.getString("id"))
                        .put("progress", detail.getValue("progress")));
            }

            return actions;
        });
    }
}
