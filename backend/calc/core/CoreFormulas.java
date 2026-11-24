package com.pulse.calc.core;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code calc/core} formula module (task 4.10): Track 1's formulas served through
 * {@link CalcService}, wired to the database and to the input ports of Tracks 2 and 3.
 *
 * <p><b>Formulas</b> (parameters in braces; {@code period} is a period key, a quarter for the index):
 * <ul>
 *   <li>{@code core.people {}}: {@code [{id, name, joinedOn}]};</li>
 *   <li>{@code core.period {period}}: the resolved period ({@link PeriodCalendar#period});</li>
 *   <li>{@code core.cultureData {person, period}}: the 11 {@link CultureScore#valData} results;</li>
 *   <li>{@code core.culture {person, period}}: the 11 culture value scores, frozen per quarter;</li>
 *   <li>{@code core.discipline {person}}: {@link Discipline#score} with its {@code metrics};</li>
 *   <li>{@code core.training {person}}: the overall training rating ({@link TrainingRating#person});</li>
 *   <li>{@code core.pillars {person, period}} and {@code core.index {person, period}}: the five pillars
 *       and the performance index, frozen per quarter;</li>
 *   <li>{@code core.indexHistory {person}}: the index of the 3 quarters before today's, oldest first;</li>
 *   <li>{@code core.appraisalPerformance {person}} and {@code core.appraisal {person}}: the appraisal
 *       performance part and the score with its recommendation ({@link Appraisal}).</li>
 * </ul>
 *
 * <p><b>Input ports</b> (formulas Tracks 2 and 3 register; until they do, the value is missing and the
 * formula leaves the part out or uses its default, see docs/calc.md):
 * {@code work.okrPillar {person, period}}, {@code work.cultureInputs {person, period}},
 * {@code people.cultureInputs {person, period}}, {@code work.extraMileItems {person, period}},
 * {@code work.disciplineInputs {person}}, {@code people.attendanceDiscipline {person}},
 * {@code people.disciplineOverride {person}}, {@code people.trainingPlans {person}} and
 * {@code people.reviewCycles {person}}.
 */
public final class CoreFormulas implements FormulaModule
{
    private static final Set<String> PERSON = Set.of("person");

    private static final Set<String> PERSON_PERIOD = Set.of("person", "period");

    private static final Set<String> QUARTERS = Set.of(PeriodCalendar.QUARTER);

    @Override
    public String id()
    {
        return "core";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(
                Formula.of("core.people", Set.of(), Set.of(CalcService.PEOPLE), CoreFormulas::people),
                Formula.of("core.period", Set.of("period"), Set.of(CalcService.SPRINTS), CoreFormulas::period),
                Formula.of("core.periods", Set.of(), Set.of(CalcService.SPRINTS), CoreFormulas::periods),
                Formula.of("core.cultureData", PERSON_PERIOD, Set.of(CalcService.PERFORMANCE), CoreFormulas::cultureData),
                new Formula("core.culture", PERSON_PERIOD, Set.of(CalcService.PERFORMANCE), QUARTERS, CoreFormulas::culture),
                Formula.of("core.discipline", PERSON, Set.of(), CoreFormulas::discipline),
                Formula.of("core.training", PERSON, Set.of(), CoreFormulas::training),
                new Formula("core.pillars", PERSON_PERIOD, Set.of(CalcService.PERFORMANCE), QUARTERS, CoreFormulas::pillars),
                new Formula("core.index", PERSON_PERIOD, Set.of(), QUARTERS, CoreFormulas::index),
                Formula.of("core.indexHistory", PERSON, Set.of(), CoreFormulas::indexHistory),
                Formula.of("core.appraisalPerformance", PERSON, Set.of(), CoreFormulas::appraisalPerformance),
                Formula.of("core.appraisal", PERSON, Set.of(CalcService.PEOPLE), CoreFormulas::appraisal));
    }

    // ------------------------------------------------------------------ people and periods

    private static Future<Object> people(CalcContext ctx, JsonObject params)
    {
        return ctx.sql().queryList("SELECT id, name, joined_on AS \"joinedOn\" FROM people ORDER BY name, id")
                .map(rows -> rows);
    }

    private static Future<Object> period(CalcContext ctx, JsonObject params)
    {
        return ctx.calendar().map(cal -> cal.period(params.getString("period")));
    }

    /** Months listed by the period control: the current one and the eleven before it. */
    static final int PERIOD_MONTHS = 12;

    /**
     * The choices of the shared period control (task 6.4): {@code {today, activeSprint, sprints,
     * months, releases}}, each list newest first with the labels of {@link PeriodCalendar}.
     */
    private static Future<Object> periods(CalcContext ctx, JsonObject params)
    {
        return ctx.calendar().map(cal ->
        {
            JsonObject active = cal.activeSprint();

            return new JsonObject()
                    .put("today", cal.today().toString())
                    .put("activeSprint", active == null ? null : active.getString("key"))
                    .put("sprints", cal.sprints())
                    .put("months", cal.months(PERIOD_MONTHS))
                    .put("releases", cal.releases());
        });
    }

    // ------------------------------------------------------------------ culture

    private static Future<Object> cultureData(CalcContext ctx, JsonObject params)
    {
        return quarter(ctx, params).compose(q ->
        {
            Tuple t = Tuple.of(person(params), q);

            Future<JsonArray> signals = ctx.sql().queryList("SELECT param_key AS key, raw_value AS raw, display_value AS display, "
                    + "source_label AS \"sourceLabel\", is_simulated AS \"isSimulated\" FROM culture_signals "
                    + "WHERE person_id = $1 AND quarter = $2", t);

            Future<Object> work = ctx.optional("work.cultureInputs", params);
            Future<Object> people = ctx.optional("people.cultureInputs", params);

            return Future.all(signals, work, people).map(all ->
            {
                JsonObject bySignal = new JsonObject();

                signals.result().forEach(o -> bySignal.put(((JsonObject) o).getString("key"), o));

                JsonObject inputs = new JsonObject();

                if (work.result() instanceof JsonObject w)
                {
                    inputs.mergeIn(w);
                }

                if (people.result() instanceof JsonObject p)
                {
                    inputs.mergeIn(p);
                }

                JsonObject rules = cultureRules(ctx);

                JsonArray out = new JsonArray();

                for (String value : CultureParameters.VALUES)
                {
                    out.add(CultureScore.valData(value, inputs, bySignal, rules));
                }

                return out;
            });
        });
    }

    private static Future<Object> culture(CalcContext ctx, JsonObject params)
    {
        return quarter(ctx, params).compose(q ->
        {
            Future<JsonArray> ratings = ctx.sql().queryList("SELECT value_name AS value, rating FROM culture_ratings "
                    + "WHERE person_id = $1 AND quarter = $2 AND rater_group = 'Combined'", Tuple.of(person(params), q));

            Future<Object> data = ctx.get("core.cultureData", params);
            Future<Object> items = ctx.optional("work.extraMileItems", params);
            Future<Object> disc = ctx.get("core.discipline", new JsonObject().put("person", params.getString("person")));

            return Future.all(ratings, data, items, disc).map(all ->
            {
                if (ratings.result().isEmpty())
                {
                    return null;
                }

                JsonArray byValue = new JsonArray();

                for (String value : CultureParameters.VALUES)
                {
                    Object rating = null;

                    for (Object o : ratings.result())
                    {
                        if (value.equals(((JsonObject) o).getString("value")))
                        {
                            rating = ((JsonObject) o).getValue("rating");
                        }
                    }

                    if (rating == null)
                    {
                        return null;
                    }

                    byValue.add(rating);
                }

                JsonArray dataScores = new JsonArray();

                ((JsonArray) data.result()).forEach(v -> dataScores.add(((JsonObject) v).getValue("score")));

                JsonObject rules = cultureRules(ctx);

                JsonArray bonus = CultureScore.valueBonus(items.result() instanceof JsonArray a ? a : new JsonArray(), rules);

                double discipline = ((JsonObject) disc.result()).getDouble("score");

                return CultureScore.cult(byValue, dataScores, bonus, discipline, rules);
            });
        });
    }

    // ------------------------------------------------------------------ Discipline and training

    private static Future<Object> discipline(CalcContext ctx, JsonObject params)
    {
        Future<Object> inputs = ctx.optional("work.disciplineInputs", params);
        Future<Object> attendance = ctx.optional("people.attendanceDiscipline", params);
        Future<Object> override = ctx.optional("people.disciplineOverride", params);

        return Future.all(inputs, attendance, override).map(all ->
        {
            JsonObject rules = ctx.rules();

            JsonObject att = rules.getJsonObject("att", new JsonObject());

            JsonObject r = new JsonObject()
                    .put("d5", rules.getValue("d5", 95)).put("d4", rules.getValue("d4", 85))
                    .put("d3", rules.getValue("d3", 70)).put("d2", rules.getValue("d2", 50))
                    .put("attInCulture", att.getBoolean("inCulture", true));

            JsonObject in = inputs.result() instanceof JsonObject i ? i.copy() : null;

            if (in != null && attendance.result() instanceof JsonObject a)
            {
                in.put("attendance", a);
            }

            JsonObject metrics = Discipline.metrics(in, r);

            return Discipline.score(metrics, override.result() instanceof JsonObject o ? o : null).put("metrics", metrics);
        });
    }

    private static Future<Object> training(CalcContext ctx, JsonObject params)
    {
        return ctx.optional("people.trainingPlans", params)
                .map(plans -> plans instanceof JsonArray a && !a.isEmpty() ? TrainingRating.person(a) : null);
    }

    // ------------------------------------------------------------------ index

    private static Future<Object> pillars(CalcContext ctx, JsonObject params)
    {
        return quarter(ctx, params).compose(q ->
        {
            Future<JsonObject> seeded = ctx.sql().queryOne("SELECT delivery, quality, okr, growth FROM pillars "
                    + "WHERE person_id = $1 AND quarter = $2", Tuple.of(person(params), q));

            Future<Object> culture = ctx.get("core.culture", params);
            Future<Object> okr = ctx.optional("work.okrPillar", params);
            Future<Object> training = ctx.get("core.training", new JsonObject().put("person", params.getString("person")));

            return Future.all(seeded, culture, okr, training).map(all ->
            {
                if (seeded.result() == null || culture.result() == null)
                {
                    return null;
                }

                JsonObject rules = ctx.rules();

                return PerformanceIndex.pillars(seeded.result(), (JsonArray) culture.result(),
                        rules.getJsonArray("vw", new JsonArray()),
                        okr.result() instanceof Number n ? n.doubleValue() : null,
                        training.result() instanceof JsonObject t ? t : null);
            });
        });
    }

    private static Future<Object> index(CalcContext ctx, JsonObject params)
    {
        return ctx.get("core.pillars", params).map(p -> p == null ? null
                : PerformanceIndex.index((JsonObject) p, ctx.rules().getJsonObject("weights", new JsonObject())));
    }

    private static Future<Object> indexHistory(CalcContext ctx, JsonObject params)
    {
        LocalDate live = ctx.today();

        JsonArray keys = new JsonArray();

        for (int back = 3; back >= 1; back--)
        {
            LocalDate d = live.minusMonths(3L * back);

            keys.add("q:" + d.getYear() + "-Q" + ((d.getMonthValue() - 1) / 3 + 1));
        }

        List<Future<Object>> values = keys.stream()
                .map(k -> ctx.get("core.index", params.copy().put("period", k)))
                .toList();

        return Future.all(values).map(all ->
        {
            JsonArray out = new JsonArray();

            values.forEach(v -> out.add(v.result()));

            return out;
        });
    }

    // ------------------------------------------------------------------ appraisal

    private static Future<Object> appraisalPerformance(CalcContext ctx, JsonObject params)
    {
        LocalDate today = ctx.today();

        String live = "q:" + today.getYear() + "-Q" + ((today.getMonthValue() - 1) / 3 + 1);

        Future<Object> history = ctx.get("core.indexHistory", params);
        Future<Object> index = ctx.get("core.index", params.copy().put("period", live));

        return Future.all(history, index).map(all ->
        {
            JsonArray h = (JsonArray) history.result();

            if (index.result() == null || h.stream().anyMatch(v -> v == null))
            {
                return null;
            }

            return Appraisal.performance(h, ((Number) index.result()).doubleValue());
        });
    }

    private static Future<Object> appraisal(CalcContext ctx, JsonObject params)
    {
        Future<Object> perf = ctx.get("core.appraisalPerformance", params);
        Future<Object> disc = ctx.get("core.discipline", params);
        Future<Object> cycles = ctx.optional("people.reviewCycles", params);
        Future<JsonObject> person = ctx.sql().queryOne("SELECT joined_on AS \"joinedOn\" FROM people WHERE id = $1",
                Tuple.of(person(params)));

        return Future.all(perf, disc, cycles, person).map(all ->
        {
            JsonObject rules = ctx.rules().getJsonObject("appraisal", Appraisal.defaultRules());

            JsonArray cs = cycles.result() instanceof JsonArray a ? a : new JsonArray();

            JsonObject f360 = Appraisal.feedback360(cs);

            JsonObject parts = Appraisal.parts(new JsonObject()
                    .put("perf", perf.result())
                    .put("r360", f360.getValue("score"))
                    .put("cycle", f360.getString("cycle"))
                    .put("mgr", Appraisal.managerReview(cs))
                    .put("disc", ((JsonObject) disc.result()).getValue("score")), rules);

            String joined = person.result() == null ? null : person.result().getString("joinedOn");

            JsonObject recommendation = joined == null ? null
                    : Appraisal.recommend(parts, LocalDate.parse(joined), LocalDate.of(ctx.today().getYear(), 9, 30), rules);

            return parts.put("recommendation", recommendation);
        });
    }

    // ------------------------------------------------------------------ helpers

    /** The quarter of the {@code period} parameter as stored in the tables: {@code "2026-Q3"}. */
    private static Future<String> quarter(CalcContext ctx, JsonObject params)
    {
        return ctx.calendar().compose(cal ->
        {
            JsonObject period = cal.period(params.getString("period"));

            if (!PeriodCalendar.QUARTER.equals(period.getString("kind")))
            {
                return Future.failedFuture(new IllegalArgumentException("period: '" + params.getString("period")
                        + "' is not a quarter; culture values and the index are quarterly."));
            }

            return Future.succeededFuture(CalcService.snapshotKey(period).substring(2));
        });
    }

    private static UUID person(JsonObject params)
    {
        String id = params.getString("person");

        if (id == null)
        {
            throw new IllegalArgumentException("person: A person id is required.");
        }

        try
        {
            return UUID.fromString(id);
        }
        catch (IllegalArgumentException e)
        {
            throw new IllegalArgumentException("person: '" + id + "' is not a person id.");
        }
    }

    private static JsonObject cultureRules(CalcContext ctx)
    {
        JsonObject rules = ctx.rules();

        return rules.put("officeDays", rules.getJsonObject("att", new JsonObject()).getValue("officeDays", 3));
    }
}
