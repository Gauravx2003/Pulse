package com.pulse.modules.progress;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.work.ActionCenterFormulas;
import com.pulse.calc.work.ProgressFormulas;
import com.pulse.calc.work.WorkViewFormulas;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.notify.NotificationTriggers;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Progress and the weekly pulse (task 13.3, spec insight/progress-pulse). Progress shows a person what
 * their manager sees about them (spec "Employee parity"): the same KPIs, next steps, achievements and
 * trends for both, from CalcService ({@link ProgressFormulas}). Pulse answers are read through the pulse
 * domain (the person, their manager and all.view); a peer is refused. The note is free text: it is never
 * logged, audited or put into a notification.
 */
final class ProgressService
{

    /**
     * Mood at or below this, or workload at or above {@link #HIGH_WORKLOAD}, is a low answer.
     */
    static final int LOW_MOOD = 2;

    static final int HIGH_WORKLOAD = 5;

    static final int NOTE_MAX = 500;

    private final ModuleContext ctx;

    private final CalcService calc;

    private final PolicyService policy;

    ProgressService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.calc = ctx.calc();

        this.policy = ctx.policy();
    }

    /**
     * {@code {personId, kpis, nextSteps, achievements, trends, utilization, pulse, can}} of a person the
     * user may see.
     */
    Future<JsonObject> progress(JsonObject user, UUID personId)
    {
        return policy.requireVisible(user, personId.toString(), PolicyService.DOMAIN_PERSON).compose(scope ->
        {
            JsonObject ref = new JsonObject().put("person", personId.toString());

            Future<Object> achievements = calc.get(ProgressFormulas.ACHIEVEMENTS, ref);

            Future<Object> steps = calc.get(ProgressFormulas.NEXT_STEPS, ref);

            Future<Object> trends = calc.get(ProgressFormulas.TRENDS, ref);

            Future<Object> utilization = calc.get(WorkViewFormulas.UTILIZATION_TREND, ref);

            Future<Object> training = calc.has("people.trainingOverall")
                    ? calc.get("people.trainingOverall", ref).recover(e -> Future.succeededFuture(null))
                    : Future.succeededFuture(null);

            Future<JsonObject> latest = policy.scope(user, PolicyService.DOMAIN_PULSE)
                    .compose(pulseScope -> new PulseRepository(ctx.sql()).latest(pulseScope, personId));

            return Future.all(List.of(achievements, steps, trends, utilization, training, latest)).map(all ->
            {
                boolean self = personId.toString().equals(user.getString("personId"));

                JsonArray earned = (JsonArray) achievements.result();

                JsonArray next = new JsonArray();

                for (Object o : (JsonArray) steps.result())
                {
                    JsonObject step = ((JsonObject) o).copy();

                    // the same list for everyone (parity); only the person acts on a self-only step
                    step.put("actionable", self || !step.getBoolean("selfOnly", false));

                    step.getJsonObject("target").put("personId", personId.toString());

                    next.add(step);
                }

                JsonObject t = (JsonObject) trends.result();

                JsonArray index = t.getJsonArray("index");

                Object now = index.getJsonObject(index.size() - 1).getValue("index");

                Object before = index.getJsonObject(index.size() - 2).getValue("index");

                JsonObject answer = latest.result();

                LocalDate week = calc.clock().today().with(DayOfWeek.MONDAY);

                boolean open = self && (answer == null || LocalDate.parse(answer.getString("weekStart")).isBefore(week));

                JsonObject kpis = new JsonObject()
                        .put("index", now)
                        .put("indexChange", now instanceof Number n && before instanceof Number b
                                ? (Object) (Math.round((n.doubleValue() - b.doubleValue()) * 100) / 100.0) : null)
                        .put("achievements", earned.size())
                        .put("nextSteps", next.size())
                        .put("training", training.result())
                        .put("trainingPlans", t.getValue("training") instanceof JsonArray plans ? plans.size() : 0)
                        .put("pulse", answer == null ? null : new JsonObject()
                                .put("mood", answer.getInteger("mood"))
                                .put("workload", answer.getInteger("workload"))
                                .put("weekStart", answer.getString("weekStart")));

                Set<String> held = PolicyService.permissions(user);

                return ResultGuard.guardObject(new JsonObject()
                        .put("personId", personId.toString())
                        .put("kpis", kpis)
                        .put("nextSteps", next)
                        .put("achievements", earned)
                        .put("trends", t)
                        .put("utilization", utilization.result())
                        .put("pulse", new JsonObject().put("open", open).put("week", week.toString()))
                        .put("can", new JsonObject()
                                .put("pulse", self)
                                .put("startOneOnOne", !self && (held.contains("team.view") || held.contains("all.view")))));
            });
        });
    }

    /**
     * {@code {items: [{weekStart, mood, workload, note, submittedAt}]}}, newest first; refused unless the
     * user is the person, their manager or holds all.view.
     */
    Future<JsonObject> pulse(JsonObject user, UUID personId)
    {
        return policy.requireVisible(user, personId.toString(), PolicyService.DOMAIN_PULSE)
                .compose(scope -> new PulseRepository(ctx.sql()).forPerson(scope, personId))
                .map(rows ->
                {
                    JsonArray items = new JsonArray();

                    for (int i = rows.size() - 1; i >= 0; i--)
                    {
                        JsonObject r = rows.getJsonObject(i);

                        items.add(new JsonObject()
                                .put("weekStart", r.getString("weekStart"))
                                .put("mood", r.getInteger("mood"))
                                .put("workload", r.getInteger("workload"))
                                .put("note", r.getString("note"))
                                .put("submittedAt", r.getValue("submittedAt")));
                    }

                    return new JsonObject().put("items", items);
                });
    }

    /**
     * Saves the signed-in person's answer for this week. Both answers are required ("Answer both
     * questions."); a low answer notifies the manager, and the Action center shows it as a Do today item
     * ({@link PulseActionProvider}).
     */
    Future<JsonObject> submit(JsonObject user, JsonObject body)
    {
        String person = user.getString("personId");

        if (person == null)
        {
            return Future.failedFuture(ApiException.unprocessable("no_profile", "Only people with a performance profile answer the pulse."));
        }

        Integer mood = score(body.getValue("mood"));

        Integer workload = score(body.getValue("workload"));

        if (mood == null || workload == null)
        {
            return Future.failedFuture(ApiException.badRequest("validation_failed", "Answer both questions.")
                    .field(mood == null ? "mood" : "workload"));
        }

        String note = body.getString("note") == null ? "" : body.getString("note").strip();

        if (note.length() > NOTE_MAX)
        {
            return Future.failedFuture(ApiException.badRequest("validation_failed", "Keep the note under " + NOTE_MAX + " characters.")
                    .field("note"));
        }

        UUID personId = UUID.fromString(person);

        LocalDate week = calc.clock().today().with(DayOfWeek.MONDAY);

        boolean low = mood <= LOW_MOOD || workload >= HIGH_WORKLOAD;

        return ctx.sql().inTransaction(tx ->
        {
            PulseRepository repo = new PulseRepository(tx);

            return repo.upsert(personId, week, mood, workload, note).compose(saved ->
            {
                if (!low)
                {
                    return Future.succeededFuture(saved);
                }

                return repo.managerOf(personId).compose(m -> m == null || m.getString("managerUserId") == null
                        ? Future.succeededFuture(saved)
                        : ctx.notifications().notify(tx, NotificationTriggers.lowPulse(m.getString("managerUserId"), personId.toString(),
                                m.getString("name"), week)).map(saved));
            });
        }).map(saved ->
        {
            calc.invalidate(ActionCenterFormulas.PULSE);

            CalcService.publish(ctx.vertx(), ActionCenterFormulas.PULSE);

            return new JsonObject()
                    .put("weekStart", saved.getString("weekStart"))
                    .put("mood", saved.getInteger("mood"))
                    .put("workload", saved.getInteger("workload"))
                    .put("note", saved.getString("note"))
                    .put("low", low);
        });
    }

    /**
     * {@code {items}}: Team wins of the user's team (their department; everyone for all.view): kudos,
     * 5-star visits, completed training and proactive fixes, without private data.
     */
    Future<JsonObject> wins(JsonObject user)
    {
        boolean everyone = PolicyService.permissions(user).contains("all.view");

        Future<io.vertx.core.json.JsonArray> rows = everyone
                ? ctx.sql().queryList("SELECT id FROM people ORDER BY name, id", Tuple.tuple())
                : ctx.sql().queryList("""
                        SELECT p.id FROM people p
                        WHERE p.department = (SELECT department FROM people WHERE user_id = $1 LIMIT 1)
                        ORDER BY p.name, p.id""", Tuple.of(UUID.fromString(user.getString("id"))));

        return rows.compose(list ->
        {
            if (list.isEmpty())
            {
                return Future.succeededFuture(new JsonObject().put("items", new JsonArray()));
            }

            String ids = list.stream().map(r -> ((JsonObject) r).getString("id")).collect(Collectors.joining(","));

            return calc.get(ActionCenterFormulas.WINS, new JsonObject().put("people", ids))
                    .map(won -> new JsonObject().put("items", won));
        });
    }

    /**
     * The Friday prompt (task 5.5's scheduler): a "Weekly pulse" notice to every active Employee without
     * an answer for this week. Resolves to the number of prompts sent.
     */
    Future<Integer> prompt()
    {
        LocalDate week = calc.clock().today().with(DayOfWeek.MONDAY);

        return new PulseRepository(ctx.sql()).withoutAnswer(week).compose(rows -> ctx.sql().inTransaction(tx ->
        {
            List<Future<JsonObject>> sent = new ArrayList<>();

            for (Object o : rows)
            {
                sent.add(ctx.notifications().notify(tx, PulsePrompt.notice(((JsonObject) o).getString("userId"), week)));
            }

            return Future.all(new ArrayList<>(sent)).map(all -> (int) sent.stream().filter(f -> f.result() != null).count());
        }));
    }

    private static Integer score(Object value)
    {
        return value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && n.intValue() >= 1 && n.intValue() <= 5
                ? n.intValue() : null;
    }
}
