package com.pulse.modules.effort;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.PeriodCalendar;
import com.pulse.calc.work.EffortFormulas;
import com.pulse.modules.work.PersonRepository;
import com.pulse.modules.work.Reports;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.policy.Scope;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Effort screens (task 11.3): the Person tab (time, daily log, items, the period table, culture
 * updates) and the Team tab (KPIs, people, period totals, requesters, the items to review), every
 * number from the {@code work.effort*} formulas. Confirm and reject of extra-mile items (task 11.2, spec work/effort-extra-mile "Confirmation"). Only a
 * holder of {@code effort.confirm} who can see the person decides; nobody confirms their own items
 * (separation of duties, 422 {@code self_action_refused}); only finished work is confirmed; a decided
 * item stays decided. Each decision is audited in its transaction and then drops the calculation cache
 * of {@link EffortFormulas#EXTRA_ITEMS} on every instance, so the culture bonus moves on the next read.
 * Points, the monthly caps and the 0.3 bonus cap are the formulas' ({@code work.extraMileItems},
 * {@code core.culture}).
 */
public final class EffortService
{

    public static final String CONFIRMED = "confirmed";

    public static final String REJECTED = "rejected";

    private static final String PENDING = "pending";

    private final ModuleContext ctx;

    private final PolicyService policy;

    public EffortService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();
    }

    /**
     * Periods in the period table and the team totals: the chosen one and the ones before it.
     */
    static final int HISTORY = 4;

    // ------------------------------------------------------------------ views

    /**
     * The Person tab: {@code {person, period, effort, dailyLog, culture, periods, canConfirm}}.
     */
    public Future<JsonObject> person(JsonObject user, String personId, String periodKey)
    {
        return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON).compose(scope -> period(periodKey).compose(period ->
        {
            JsonObject params = new JsonObject().put("person", personId).put("period", period.getString("key"));

            Future<JsonObject> identity = new PersonRepository(ctx.sql()).identity(scope, UUID.fromString(personId));

            Future<Object> effort = ctx.calc().get(EffortFormulas.EFFORT, params);

            Future<Object> daily = ctx.calc().get(EffortFormulas.DAILY_LOG, params);

            Future<Object> culture = ctx.calc().get(EffortFormulas.CULTURE_UPDATES, new JsonObject().put("person", personId));

            Future<JsonArray> history = history(period).compose(keys -> periodRows(personId, keys));

            return Future.all(List.of(identity, effort, daily, culture, history)).map(all -> new JsonObject()
                    .put("person", identity.result())
                    .put("period", period)
                    .put("effort", effort.result())
                    .put("dailyLog", daily.result())
                    .put("culture", culture.result())
                    .put("periods", history.result())
                    .put("canConfirm", PolicyService.permissions(user).contains("effort.confirm")
                            && !personId.equals(user.getString("personId"))));
        }));
    }

    /**
     * The Team tab for a manager's reports (everyone for {@code all.view}): {@code {period, kpis, people,
     * periods, requesters, review, canConfirm}}; {@code review} lists the items still awaiting a manager.
     */
    public Future<JsonObject> team(JsonObject user, String periodKey)
    {
        return Reports.of(ctx, user).compose(people -> period(periodKey).compose(period ->
        {
            List<Future<Object>> efforts = new ArrayList<>();

            for (JsonObject person : people)
            {
                efforts.add(ctx.calc().get(EffortFormulas.EFFORT, new JsonObject().put("person", person.getString("id"))
                        .put("period", period.getString("key"))));
            }

            String ids = String.join(",", people.stream().map(p -> p.getString("id")).toList());

            Future<Object> totals = ctx.calc().get(EffortFormulas.TOTALS, new JsonObject().put("people", ids).put("period", period.getString("key")));

            Future<JsonArray> history = history(period).compose(keys -> periodRows(ids, keys));

            Future<JsonObject> teamName = ctx.sql().queryOne(
                    "SELECT string_agg(DISTINCT department, ', ') AS team FROM people WHERE id = ANY($1)",
                    Tuple.of(people.stream().map(p -> UUID.fromString(p.getString("id"))).toArray(UUID[]::new)));

            List<Future<?>> all = new ArrayList<>(efforts);

            all.add(totals);

            all.add(history);

            all.add(teamName);

            return Future.all(all).map(done ->
            {
                JsonArray rows = new JsonArray();

                JsonArray review = new JsonArray();

                Map<String, JsonObject> requesters = new LinkedHashMap<>();

                for (int i = 0; i < people.size(); i++)
                {
                    JsonObject person = people.get(i);

                    JsonObject e = (JsonObject) efforts.get(i).result();

                    JsonArray list = e.getJsonArray("items", new JsonArray());

                    for (Object o : list)
                    {
                        JsonObject item = (JsonObject) o;

                        if (PENDING.equals(item.getString("state")))
                        {
                            review.add(item.copy().put("personName", person.getString("name")));
                        }
                    }

                    for (Object o : e.getJsonArray("requesters", new JsonArray()))
                    {
                        JsonObject r = (JsonObject) o;

                        JsonObject sum = requesters.computeIfAbsent(r.getString("by"), k -> new JsonObject().put("by", k).put("items", 0).put("hours", 0.0));

                        sum.put("items", sum.getInteger("items") + r.getInteger("items", 0)).put("hours", sum.getDouble("hours") + number(r.getValue("hours")));
                    }

                    rows.add(new JsonObject()
                            .put("id", person.getString("id"))
                            .put("name", person.getString("name"))
                            .put("logged", e.getValue("logged"))
                            .put("daysWorked", e.getValue("daysWorked"))
                            .put("active", e.getValue("active"))
                            .put("work", e.getValue("work"))
                            .put("codeOnly", e.getValue("codeOnly"))
                            .put("share", e.getValue("share"))
                            .put("items", list.size())
                            .put("points", e.getValue("ptsConf"))
                            .put("pending", e.getValue("ptsPend")));
                }

                List<JsonObject> ranked = new ArrayList<>(requesters.values());

                ranked.sort((a, b) -> Double.compare(b.getDouble("hours"), a.getDouble("hours")));

                long awaiting = review.stream().filter(i -> ((JsonObject) i).getBoolean("isDone", false)).count();

                JsonObject sum = (JsonObject) totals.result();

                return new JsonObject()
                        .put("period", period)
                        .put("team", teamName.result() == null ? null : teamName.result().getString("team"))
                        .put("kpis", new JsonObject()
                                .put("logged", sum.getValue("logged"))
                                .put("daysWorked", sum.getValue("daysWorked"))
                                .put("unplanned", sum.getValue("unplanned"))
                                .put("share", sum.getValue("share"))
                                .put("items", sum.getValue("items"))
                                .put("doneItems", sum.getValue("doneItems"))
                                .put("awaiting", awaiting))
                        .put("people", rows)
                        .put("periods", history.result())
                        .put("requesters", new JsonArray(new ArrayList<Object>(ranked)))
                        .put("review", review)
                        .put("canConfirm", PolicyService.permissions(user).contains("effort.confirm"));
            });
        }));
    }

    /**
     * The chosen period and up to {@value #HISTORY} - 1 periods of its kind before it, newest first.
     */
    private Future<List<JsonObject>> history(JsonObject period)
    {
        return ctx.calc().get("core.periods", new JsonObject()).map(result ->
        {
            JsonObject periods = (JsonObject) result;

            String kind = period.getString("kind");

            JsonArray list = PeriodCalendar.SPRINT.equals(kind) ? periods.getJsonArray("sprints")
                    : "month".equals(kind) ? periods.getJsonArray("months") : new JsonArray().add(period);

            List<JsonObject> out = new ArrayList<>();

            boolean found = false;

            for (Object o : list)
            {
                JsonObject p = (JsonObject) o;

                found |= p.getString("key").equals(period.getString("key"));

                if (found && out.size() < HISTORY)
                {
                    out.add(p);
                }
            }

            return out.isEmpty() ? List.of(period) : out;
        });
    }

    /**
     * Per period, the totals of these people ({@code work.effortTotals}): {@code [{period, short, logged,
     * daysWorked, work, unplanned, weekend, visitHours, items, points}]}.
     */
    private Future<JsonArray> periodRows(String people, List<JsonObject> periods)
    {
        List<Future<Object>> cells = new ArrayList<>();

        for (JsonObject p : periods)
        {
            cells.add(ctx.calc().get(EffortFormulas.TOTALS, new JsonObject().put("people", people).put("period", p.getString("key"))));
        }

        return Future.all(cells).map(done ->
        {
            JsonArray out = new JsonArray();

            for (int i = 0; i < periods.size(); i++)
            {
                JsonObject p = periods.get(i);

                JsonObject t = (JsonObject) cells.get(i).result();

                out.add(new JsonObject()
                        .put("period", p.getString("key"))
                        .put("short", p.getString("short"))
                        .put("logged", t.getValue("logged"))
                        .put("daysWorked", t.getValue("daysWorked"))
                        .put("work", t.getValue("work"))
                        .put("unplanned", t.getValue("unplanned"))
                        .put("weekend", t.getValue("weekend"))
                        .put("visitHours", t.getValue("visitHours"))
                        .put("items", t.getValue("items"))
                        .put("points", t.getValue("points")));
            }

            return out;
        });
    }

    private Future<JsonObject> period(String key)
    {
        String chosen = key == null || key.isBlank() ? "m:" + YearMonth.from(ctx.calc().clock().today()) : key;

        return ctx.calc().get("core.period", new JsonObject().put("period", chosen))
                .map(p -> (JsonObject) p)
                .recover(error -> error instanceof IllegalArgumentException
                        ? Future.failedFuture(ApiException.badRequest("invalid_parameter", "Unknown period.").field("period"))
                        : Future.failedFuture(error));
    }

    private static double number(Object value)
    {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    // ------------------------------------------------------------------ decisions

    /**
     * Confirms or rejects one item on the version the client saw.
     *
     * @param request {@code {sourceIp, requestId}} for the refused audit entry ({@link PolicyService#request})
     */
    public Future<JsonObject> decide(JsonObject user, UUID id, String decision, int version, JsonObject request)
    {
        return policy.scope(user, PolicyService.DOMAIN_PERSON)
                .compose(scope -> ctx.sql().inTransaction(tx -> new ExtraItemRepository(tx).lockVisible(scope, id)
                        .compose(item ->
                        {
                            if (item == null)
                            {
                                return Future.failedFuture(ApiException.notFound("not_found", "Item not found."));
                            }

                            Future<Void> self = CONFIRMED.equals(decision)
                                    ? policy.refuseSelf(user, AuditEvents.EFFORT_CONFIRM, item.getString("personId"), null, request)
                                    : Future.succeededFuture();

                            return self.compose(v ->
                            {
                                ApiException refused = refusal(item, decision);

                                return refused != null ? Future.failedFuture(refused) : record(tx, user, item, decision, version);
                            });
                        })))
                .onSuccess(item -> changed());
    }

    /**
     * "Confirm all done items": confirms each listed item that is visible, pending, done and not the
     * caller's own, in one transaction; the others are skipped with a reason
     * ({@code not_found}, {@code own}, {@code decided}, {@code not_done}). Resolves to
     * {@code {confirmed: [ids], skipped: [{id, reason}]}}.
     */
    public Future<JsonObject> confirmAll(JsonObject user, List<UUID> ids)
    {
        String own = user.getString("personId");

        return policy.scope(user, PolicyService.DOMAIN_PERSON)
                .compose(scope -> ctx.sql().inTransaction(tx ->
                {
                    JsonObject result = new JsonObject().put("confirmed", new JsonArray()).put("skipped", new JsonArray());

                    Future<Void> chain = Future.succeededFuture();

                    for (UUID id : ids.stream().distinct().toList())
                    {
                        chain = chain.compose(v -> confirmOne(tx, scope, user, own, id, result));
                    }

                    return chain.map(v -> result);
                }))
                .onSuccess(result ->
                {
                    if (!result.getJsonArray("confirmed").isEmpty())
                    {
                        changed();
                    }
                });
    }

    private Future<Void> confirmOne(SqlExecutor tx, Scope scope, JsonObject user, String own, UUID id, JsonObject result)
    {
        return new ExtraItemRepository(tx).lockVisible(scope, id).compose(item ->
        {
            String reason = item == null ? "not_found"
                    : item.getString("personId").equals(own) ? "own"
                    : !PENDING.equals(item.getString("state")) ? "decided"
                    : !item.getBoolean("isDone", false) ? "not_done"
                    : null;

            if (reason != null)
            {
                result.getJsonArray("skipped").add(new JsonObject().put("id", id.toString()).put("reason", reason));

                return Future.succeededFuture();
            }

            return record(tx, user, item, CONFIRMED, item.getInteger("version")).map(saved ->
            {
                result.getJsonArray("confirmed").add(id.toString());

                return null;
            });
        });
    }

    private static ApiException refusal(JsonObject item, String decision)
    {
        if (!PENDING.equals(item.getString("state")))
        {
            return ApiException.unprocessable("already_decided", "This item was already " + item.getString("state") + ".");
        }

        if (CONFIRMED.equals(decision) && !item.getBoolean("isDone", false))
        {
            return ApiException.unprocessable("not_done", "Only finished work can be confirmed; this item is waiting for Done.");
        }

        return null;
    }

    private static Future<JsonObject> record(SqlExecutor tx, JsonObject user, JsonObject item, String decision, int version)
    {
        UUID id = UUID.fromString(item.getString("id"));

        return new ExtraItemRepository(tx).decide(id, decision, UUID.fromString(user.getString("id")), version)
                .compose(saved -> AuditService.record(tx, AuditService.entry(user,
                                CONFIRMED.equals(decision) ? AuditEvents.EFFORT_CONFIRM : AuditEvents.EFFORT_REJECT,
                                "extra_item", id.toString(), new JsonObject()
                                        .put("personId", item.getString("personId"))
                                        .put("kind", item.getString("kind"))
                                        .put("ref", item.getString("ref"))))
                        .map(v -> saved));
    }

    /**
     * Drops the cached effort and culture results here and on every other instance.
     */
    private void changed()
    {
        ctx.calc().invalidate(EffortFormulas.EXTRA_ITEMS);

        CalcService.publish(ctx.vertx(), EffortFormulas.EXTRA_ITEMS);
    }
}
