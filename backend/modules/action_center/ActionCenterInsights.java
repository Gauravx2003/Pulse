package com.pulse.modules.action_center;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.work.ActionCenterFormulas;
import com.pulse.calc.work.WorkViewFormulas;
import com.pulse.modules.progress.PulseRepository;
import com.pulse.modules.work.Reports;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Action center's insight sections (task 13.2, spec insight/action-center): people signals, sprint
 * forecast, next-sprint capacity, skills coverage, team pulse, team wins and the weekly digest preview.
 * Every section covers the viewer's reports (a Manager) or everyone (all.view), never the viewer; the
 * numbers come from CalcService ({@link ActionCenterFormulas}). Pulse answers and the signals drawn from
 * them are shown only for people whose pulse the viewer may read (the pulse domain).
 */
final class ActionCenterInsights
{

    /**
     * Signals drawn from the weekly pulse; dropped for people outside the viewer's pulse scope.
     */
    private static final Set<String> PULSE_SIGNALS = Set.of("Pulse check-in low", "Mood falling");

    private static final int DIGEST_ITEMS = 5;

    private static final int DIGEST_WINS = 3;

    private final ModuleContext ctx;

    private final CalcService calc;

    ActionCenterInsights(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.calc = ctx.calc();
    }

    /**
     * {@code {people: [{id, name, risk, tone, signals, next}], watch: [names], count}}: most signals first.
     */
    Future<JsonObject> signals(JsonObject user)
    {
        return Reports.of(ctx, user).compose(people -> pulseVisible(user, people).compose(pulse ->
        {
            List<Future<Object>> rows = people.stream()
                    .map(p -> calc.get(ActionCenterFormulas.SIGNALS, person(p)))
                    .toList();

            return Future.all(new ArrayList<>(rows)).map(all ->
            {
                List<JsonObject> out = new ArrayList<>();

                for (int i = 0; i < people.size(); i++)
                {
                    JsonObject p = people.get(i);

                    JsonObject result = (JsonObject) rows.get(i).result();

                    JsonArray signals = new JsonArray();

                    for (Object o : result.getJsonArray("signals", new JsonArray()))
                    {
                        JsonObject s = (JsonObject) o;

                        if (pulse.contains(p.getString("id")) || !PULSE_SIGNALS.contains(s.getString("label")))
                        {
                            signals.add(s);
                        }
                    }

                    JsonObject risk = com.pulse.calc.work.ActionCenter.risk(signals);

                    out.add(new JsonObject()
                            .put("id", p.getString("id"))
                            .put("name", p.getString("name"))
                            .put("risk", risk.getString("risk"))
                            .put("tone", risk.getString("tone"))
                            .put("signals", signals)
                            .put("next", signals.isEmpty() ? "Keep going" : signals.getJsonObject(0).getString("next")));
                }

                out.sort(Comparator.comparingInt((JsonObject r) -> -r.getJsonArray("signals").size()));

                JsonArray watch = new JsonArray();

                out.stream().filter(r -> "High".equals(r.getString("risk"))).forEach(r -> watch.add(r.getString("name")));

                return new JsonObject().put("people", new JsonArray(new ArrayList<>(out))).put("watch", watch).put("count", out.size());
            });
        }));
    }

    /**
     * {@code {sprint, people: [{id, name, remaining, capacity, fits, pct, carry}], remaining, fitsPct, carry}}
     * for the active sprint ({@code sprint} its key, null without one); people on onboarding are left out.
     */
    Future<JsonObject> forecast(JsonObject user)
    {
        return Reports.of(ctx, user).compose(people ->
        {
            List<Future<Object>> onboarding = people.stream().map(p -> calc.get(WorkViewFormulas.ONBOARDING, person(p))).toList();

            List<Future<Object>> rows = people.stream().map(p -> calc.get(ActionCenterFormulas.FORECAST, person(p))).toList();

            List<Future<?>> waits = new ArrayList<>(onboarding);

            waits.addAll(rows);

            return Future.all(waits).map(all ->
            {
                JsonArray out = new JsonArray();

                double remaining = 0;

                double fits = 0;

                int carry = 0;

                String sprint = null;

                for (int i = 0; i < people.size(); i++)
                {
                    if (present(onboarding.get(i).result()) || !(rows.get(i).result() instanceof JsonObject f) || !present(f))
                    {
                        continue;
                    }

                    sprint = sprint == null ? f.getString("sprint") : sprint;

                    JsonObject p = people.get(i);

                    remaining += f.getDouble("remaining");

                    fits += f.getDouble("fits");

                    carry += f.getJsonArray("carry").size();

                    out.add(f.copy().put("id", p.getString("id")).put("name", p.getString("name")));
                }

                return new JsonObject()
                        .put("people", out)
                        .put("remaining", remaining)
                        .put("fitsPct", remaining > 0 ? (int) Math.round(fits / remaining * 100) : 100)
                        .put("carry", carry)
                        .put("sprint", sprint);
            });
        });
    }

    /**
     * {@code {sprint: {key, name, from, end}, people: [...], total: {focusDays, suggestedPoints}}}; new
     * joiners have no suggestion and are left out of the team's points.
     */
    Future<JsonObject> capacity(JsonObject user)
    {
        return Reports.of(ctx, user).compose(people ->
        {
            Future<Object> sprint = calc.get(ActionCenterFormulas.NEXT_SPRINT, new JsonObject());

            List<Future<Object>> rows = people.stream().map(p -> calc.get(ActionCenterFormulas.CAPACITY, person(p))).toList();

            List<Future<?>> waits = new ArrayList<>(rows);

            waits.add(sprint);

            return Future.all(waits).map(all ->
            {
                JsonArray out = new JsonArray();

                double focus = 0;

                long points = 0;

                for (int i = 0; i < people.size(); i++)
                {
                    if (!(rows.get(i).result() instanceof JsonObject row) || !present(row))
                    {
                        continue;
                    }

                    JsonObject p = people.get(i);

                    focus += row.getDouble("focusDays");

                    if (row.getValue("suggestedPoints") instanceof Number n)
                    {
                        points += n.longValue();
                    }

                    out.add(row.copy().put("id", p.getString("id")).put("name", p.getString("name")));
                }

                return new JsonObject()
                        .put("sprint", sprint.result())
                        .put("people", out)
                        .put("total", new JsonObject()
                                .put("focusDays", Math.round(focus * 10) / 10.0)
                                .put("suggestedPoints", points));
            });
        });
    }

    /**
     * {@code {people: [{id, name, initials}], areas: [{area, counts, people, coverage, tone, suggestion}]}}.
     */
    Future<JsonObject> coverage(JsonObject user)
    {
        return Reports.of(ctx, user).compose(people -> calc.get(ActionCenterFormulas.COVERAGE, team(people)))
                .map(result -> (JsonObject) result);
    }

    /**
     * {@code {weeks: [{week, mood, workload, answers}], answered, mood, low: [names]}} over the people whose
     * pulse the viewer may read.
     */
    Future<JsonObject> pulse(JsonObject user)
    {
        return Reports.of(ctx, user).compose(people -> pulseVisible(user, people).compose(visible ->
        {
            List<JsonObject> readable = people.stream().filter(p -> visible.contains(p.getString("id"))).toList();

            return calc.get(ActionCenterFormulas.TEAM_PULSE, team(readable)).map(result -> (JsonObject) result);
        }));
    }

    /**
     * {@code {items: [{who, what, text, at, value?, kind}]}}, newest first.
     */
    Future<JsonObject> wins(JsonObject user)
    {
        return Reports.of(ctx, user).compose(people -> calc.get(ActionCenterFormulas.WINS, team(people)))
                .map(result -> new JsonObject().put("items", result));
    }

    /**
     * The Monday summary preview: {@code {date, high, total, items, watch: [{name, signals}], wins}}.
     */
    Future<JsonObject> digest(JsonObject user, Future<JsonObject> items)
    {
        Future<JsonObject> signals = signals(user);

        Future<JsonObject> wins = wins(user);

        return Future.all(items, signals, wins).map(all ->
        {
            JsonArray list = items.result().getJsonArray("items", new JsonArray());

            JsonArray top = new JsonArray();

            list.stream().limit(DIGEST_ITEMS).forEach(top::add);

            JsonArray watch = new JsonArray();

            for (Object o : signals.result().getJsonArray("people"))
            {
                JsonObject row = (JsonObject) o;

                if ("High".equals(row.getString("risk")))
                {
                    watch.add(new JsonObject()
                            .put("name", row.getString("name"))
                            .put("signals", row.getJsonArray("signals").stream()
                                    .map(s -> ((JsonObject) s).getString("label").toLowerCase(java.util.Locale.ROOT))
                                    .collect(Collectors.joining(", "))));
                }
            }

            JsonArray topWins = new JsonArray();

            wins.result().getJsonArray("items", new JsonArray()).stream().limit(DIGEST_WINS).forEach(topWins::add);

            return new JsonObject()
                    .put("date", ctx.calc().clock().today().toString())
                    .put("high", list.stream().filter(i -> "High".equals(((JsonObject) i).getString("priority"))).count())
                    .put("total", list.size())
                    .put("items", top)
                    .put("watch", watch)
                    .put("wins", topWins);
        });
    }

    // ------------------------------------------------------------------ helpers

    private Future<Set<String>> pulseVisible(JsonObject user, List<JsonObject> people)
    {
        if (people.isEmpty())
        {
            return Future.succeededFuture(Set.of());
        }

        List<UUID> ids = people.stream().map(p -> UUID.fromString(p.getString("id"))).toList();

        return ctx.policy().scope(user, PolicyService.DOMAIN_PULSE)
                .compose(scope -> new PulseRepository(ctx.sql()).visible(scope, ids));
    }

    private static JsonObject person(JsonObject p)
    {
        return new JsonObject().put("person", p.getString("id"));
    }

    private static JsonObject team(List<JsonObject> people)
    {
        return new JsonObject().put("people", people.stream().map(p -> p.getString("id")).collect(Collectors.joining(",")));
    }

    /**
     * False for a guarded "no value" ({@code {"notApplicable": true}}).
     */
    private static boolean present(Object value)
    {
        return value != null && !ResultGuard.isNotApplicable(value);
    }
}
