package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 4.9: a property test over every ported function in {@code calc/core} with empty and extreme
 * inputs. For each case the raw result holds no NaN or infinity, the guarded result
 * ({@link ResultGuard#guard}) holds no null, NaN, infinity or −0, and the function does not crash (an
 * {@link IllegalArgumentException} for an input it rejects by design is allowed).
 *
 * <p>Extreme values stay in each input's domain (scores, weights, percentages, counts), including 0,
 * −0, the top of the scale and far beyond it; random cases draw from them with a fixed seed.
 */
class CalcPropertyTest
{
    private static final LocalDate TODAY = LocalDate.parse("2026-09-28");

    private static final double[] EXTREMES = {0, -0.0, 0.0001, 1, 2.5, 5, 100, 1e6};

    private static final int RANDOM_CASES = 300;

    private final Random random = new Random(4_9);

    private final List<String> failures = new ArrayList<>();

    private int cases;

    // ------------------------------------------------------------------ performance index

    @Test
    void performanceIndex()
    {
        check("index all-zero weights", () -> PerformanceIndex.index(pillars(3), weights(0)));
        check("cultureAvg empty", () -> PerformanceIndex.cultureAvg(new JsonArray(), new JsonArray()));
        check("growth no training", () -> PerformanceIndex.growth(3, null));

        for (double x : EXTREMES)
        {
            for (double w : EXTREMES)
            {
                check("index " + x + "/" + w, () -> PerformanceIndex.index(pillars(x), weights(w)));
                check("cultureAvg " + x + "/" + w, () -> PerformanceIndex.cultureAvg(eleven(x), eleven(w)));
                check("growth " + x + "/" + w, () -> PerformanceIndex.growth(x,
                        new JsonObject().put("final", true).put("score", w)));
            }

            check("pillars " + x, () -> PerformanceIndex.pillars(new JsonObject().put("delivery", x).put("quality", x)
                    .put("okr", x).put("growth", x), eleven(x), eleven(0), null, null));
        }

        repeat("index random", () -> PerformanceIndex.index(pillars(any()), new JsonObject().put("delivery", any())
                .put("quality", any()).put("culture", any()).put("okr", any()).put("growth", any())));
    }

    // ------------------------------------------------------------------ culture

    @Test
    void culture()
    {
        JsonObject rules = new JsonObject().put("staleDays", 2).put("officeDays", 3).put("dataShare", 40);

        for (String v : CultureParameters.VALUES)
        {
            check("valData empty " + v, () -> CultureScore.valData(v, new JsonObject(), new JsonObject(), rules));
            check("valData null " + v, () -> CultureScore.valData(v, null, null, rules));

            for (double x : EXTREMES)
            {
                check("valData " + v + " " + x, () -> CultureScore.valData(v, inputsFor(v, x), new JsonObject(), rules));
            }

            JsonObject data = CultureScore.valData(v, new JsonObject(), new JsonObject(), rules);

            check("detail empty " + v, () -> CultureScore.detail(v, 0, new JsonObject(), data, 0, 0, new JsonArray(), rules));
            check("detail extreme " + v, () -> CultureScore.detail(v, 1e6, new JsonObject().put("Manager", 1e6), data,
                    1e6, 1e6, new JsonArray(), rules));
        }

        check("valueBonus empty", () -> CultureScore.valueBonus(new JsonArray(), rules));
        check("valueBonus null", () -> CultureScore.valueBonus(null, rules));
        check("valueBonus no values", () -> CultureScore.valueBonus(new JsonArray().add(new JsonObject()
                .put("state", "confirmed").put("points", 3).put("values", new JsonArray())), rules));

        JsonArray none = new JsonArray();

        for (int i = 0; i < 11; i++)
        {
            none.addNull();
        }

        for (double x : EXTREMES)
        {
            check("bandScore " + x, () -> CultureScore.bandScore(x, new JsonArray().add(95).add(85).add(70).add(55), true));
            check("bandScore lower " + x, () -> CultureScore.bandScore(x, new JsonArray().add(0).add(1).add(2).add(4), false));
            check("valueBonus " + x, () -> CultureScore.valueBonus(new JsonArray().add(new JsonObject().put("state", "confirmed")
                    .put("points", x).put("values", new JsonArray().add("Collaboration").add("Resilience"))), rules));
            check("cult " + x, () -> CultureScore.cult(eleven(x), none, eleven(x), x, rules.copy().put("dataShare", x)));
        }

        repeat("cult random", () -> CultureScore.cult(eleven(any()), eleven(any()), eleven(any()), any(),
                rules.copy().put("dataShare", any())));
    }

    // ------------------------------------------------------------------ Discipline

    @Test
    void discipline()
    {
        JsonObject rules = new JsonObject().put("d5", 95).put("d4", 85).put("d3", 70).put("d2", 50).put("attInCulture", true);

        check("metrics null", () -> Discipline.metrics(null, rules));
        check("score none", () -> Discipline.score(null, null));
        check("metrics empty", () -> Discipline.metrics(discipline(0, new JsonArray()), rules));

        for (double x : EXTREMES)
        {
            check("metrics " + x, () -> Discipline.metrics(discipline(x, days(20)), rules));
            check("score " + x, () -> Discipline.score(Discipline.metrics(discipline(x, days(20)), rules), null));
        }

        repeat("metrics random", () -> Discipline.metrics(discipline(any(), days(random.nextInt(60))),
                rules.copy().put("d5", any()).put("d4", any()).put("d3", any()).put("d2", any())));
    }

    // ------------------------------------------------------------------ attendance

    @Test
    void attendance()
    {
        JsonObject rules = new JsonObject().put("officeDays", 3).put("notice", 2).put("noticeLong", 7)
                .put("lateAfter", "10:30").put("grace", 3);

        LocalDate from = LocalDate.parse("2026-09-01");
        LocalDate to = LocalDate.parse("2026-09-30");

        check("stats empty", () -> Attendance.stats(new JsonObject(), new JsonObject(), new JsonArray(), Set.of(),
                Set.of(), from, to, TODAY, rules));
        check("stats uploaded no records", () -> Attendance.stats(new JsonObject(), new JsonObject(), new JsonArray(),
                Set.of(), Set.of("2026-09"), from, to, TODAY, rules));
        check("stats before joining", () -> Attendance.stats(new JsonObject().put("joinedOn", "2026-12-01"),
                new JsonObject(), new JsonArray(), Set.of(), Set.of("2026-09"), from, to, TODAY, rules));
        check("stats period after today", () -> Attendance.stats(new JsonObject(), new JsonObject(), new JsonArray(),
                Set.of(), Set.of("2026-10"), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), TODAY, rules));
        check("stats zero office days", () -> Attendance.stats(new JsonObject(), records("O", "09:00"), new JsonArray(),
                Set.of(), Set.of("2026-09"), from, to, TODAY, rules.copy().put("officeDays", 0).put("grace", 0)));
        check("stats all late", () -> Attendance.stats(new JsonObject(), records("O", "23:59"), new JsonArray(),
                Set.of(), Set.of("2026-09"), from, to, TODAY, rules));
        check("stats all leave", () -> Attendance.stats(new JsonObject(), records("L", null), new JsonArray(),
                Set.of(), Set.of("2026-09"), from, to, TODAY, rules));
        check("score none", () -> Attendance.score(null, null, null));
        check("band none", () -> Attendance.band(null));
        check("presence none", () -> Attendance.presenceScore(null, true));
        check("presence off", () -> Attendance.presenceScore(100L, false));
    }

    // ------------------------------------------------------------------ training

    @Test
    void training()
    {
        JsonObject rules = new JsonObject().put("trWa", 40).put("trWm", 20).put("trWg", 20).put("trWc", 10).put("trWp", 10);

        JsonObject empty = new JsonObject().put("start", TODAY.toString()).put("weeks", 1).put("cadence", "Weekly");

        check("checkIns empty", () -> TrainingRating.checkIns(empty, Set.of(), TODAY));
        check("checkIns not started", () -> TrainingRating.checkIns(empty.copy().put("start", "2027-01-04"), Set.of(), TODAY));
        check("rating empty", () -> rating(empty, null, rules));
        check("rating zero weights", () -> rating(full(5), 5.0, new JsonObject().put("trWa", 0).put("trWm", 0)
                .put("trWg", 0).put("trWc", 0).put("trWp", 0)));
        check("checkpoints empty", () -> TrainingRating.checkpointAverage(new JsonArray()));
        check("checkpoints null", () -> TrainingRating.checkpointAverage(null));
        check("person empty", () -> TrainingRating.person(new JsonArray()));
        check("band none", () -> TrainingRating.band(null));

        for (String cadence : new String[] {"Daily", "Weekly", "Monthly"})
        {
            check("checkIns long " + cadence, () -> TrainingRating.checkIns(new JsonObject().put("start", "2020-01-01")
                    .put("weeks", 400).put("cadence", cadence), Set.of(), TODAY));
        }

        for (double x : EXTREMES)
        {
            check("rating " + x, () -> rating(full(x), x, rules));
            check("person " + x, () -> TrainingRating.person(new JsonArray().add(new JsonObject().put("weeks", x)
                    .put("rating", new JsonObject().put("score", x).put("final", true)))));
            check("band " + x, () -> TrainingRating.band(x));
        }

        repeat("rating random", () -> rating(full(any()), any(), new JsonObject().put("trWa", any()).put("trWm", any())
                .put("trWg", any()).put("trWc", any()).put("trWp", any())));
    }

    // ------------------------------------------------------------------ appraisal

    @Test
    void appraisal()
    {
        JsonObject rules = Appraisal.defaultRules();

        LocalDate asOf = LocalDate.parse("2026-09-30");

        check("parts empty", () -> Appraisal.parts(new JsonObject(), rules));
        check("recommend empty", () -> Appraisal.recommend(Appraisal.parts(new JsonObject(), rules), asOf, asOf, rules));
        check("feedback360 empty", () -> Appraisal.feedback360(new JsonArray()));
        check("managerReview empty", () -> Appraisal.managerReview(new JsonArray()));
        check("averageScores empty", () -> Appraisal.averageScores(new JsonArray()));
        check("joined in the future", () -> Appraisal.recommend(Appraisal.parts(new JsonObject().put("perf", 4), rules),
                LocalDate.parse("2027-06-01"), asOf, rules));

        JsonObject zero = rules.copy().put("w", new JsonObject().put("perf", 0).put("r360", 0).put("mgr", 0).put("disc", 0));

        check("zero weights", () -> Appraisal.recommend(Appraisal.parts(new JsonObject().put("perf", 4), zero),
                LocalDate.parse("2020-01-01"), asOf, zero));

        // Two bands with the same minimum: no band width to place the score in.
        JsonObject flat = rules.copy();

        flat.getJsonArray("bands").getJsonObject(1).put("min", 4.5);

        check("flat band", () -> Appraisal.recommend(Appraisal.parts(new JsonObject().put("perf", 4.6), flat),
                LocalDate.parse("2020-01-01"), asOf, flat));

        for (double x : EXTREMES)
        {
            JsonObject in = new JsonObject().put("perf", x).put("r360", x).put("mgr", x).put("disc", x);

            check("recommend " + x, () -> Appraisal.recommend(Appraisal.parts(in, rules), LocalDate.parse("2026-03-15"),
                    asOf, rules));
            check("performance " + x, () -> Appraisal.performance(new JsonArray().add(x).add(x).add(x), x));
        }

        repeat("recommend random", () ->
        {
            JsonObject r = rules.copy().put("max", any()).put("w", new JsonObject().put("perf", any()).put("r360", any())
                    .put("mgr", any()).put("disc", any()));

            return Appraisal.recommend(Appraisal.parts(new JsonObject().put("perf", any()).put("mgr", any()), r),
                    TODAY.minusMonths(random.nextInt(40)), asOf, r);
        });
    }

    // ------------------------------------------------------------------ calendar

    @Test
    void calendar()
    {
        PeriodCalendar none = new PeriodCalendar(new JsonArray(), new JsonArray(), TODAY);

        check("all with no sprints", () -> none.period("all"));
        check("quarter with no sprints", () -> none.period("q:2026-Q3"));
        check("month", () -> none.period("m:2026-02"));
        check("working days empty range", () -> WorkingDays.count(TODAY, TODAY.minusDays(5), Set.of(), null));
    }

    // ------------------------------------------------------------------ guard itself

    @Test
    void guardCleansEveryBadValue()
    {
        JsonObject raw = new JsonObject().put("nan", Double.NaN).put("inf", Double.POSITIVE_INFINITY).put("negZero", -0.0)
                .put("none", null).put("list", new JsonArray().add(Double.NEGATIVE_INFINITY).addNull().add(1.5))
                .put("ok", 3).put("text", "x").put("parameter", new JsonObject().put("notApplicable", true).put("weight", 20));

        assertTrue(ResultGuard.violations(raw).containsAll(List.of("$.nan: NaN", "$.inf: Infinity", "$.negZero: -0",
                "$.list[0]: -Infinity")), ResultGuard.violations(raw).toString());

        JsonObject g = (JsonObject) ResultGuard.guard(raw);

        assertTrue(ResultGuard.isClean(g), g.encode());
        assertTrue(ResultGuard.isNotApplicable(g.getValue("nan")));
        assertTrue(ResultGuard.isNotApplicable(g.getValue("none")));
        assertTrue(ResultGuard.isNotApplicable(g.getJsonArray("list").getValue(1)));
        assertTrue(Double.doubleToRawLongBits(g.getDouble("negZero")) == 0);
        assertTrue(g.getJsonArray("list").getValue(2).equals(1.5));

        // A culture parameter's own flag is not the not-applicable state.
        assertTrue(!ResultGuard.isNotApplicable(g.getValue("parameter")));
        assertTrue(ResultGuard.isNotApplicable(ResultGuard.notApplicable("No on-call shifts")));

        // The input is not changed.
        assertTrue(Double.isNaN(raw.getDouble("nan")));
    }

    // ------------------------------------------------------------------ harness

    private void check(String name, Supplier<Object> port)
    {
        cases++;

        Object raw;

        try
        {
            raw = port.get();
        }
        catch (IllegalArgumentException rejected)
        {
            return;
        }
        catch (RuntimeException e)
        {
            failures.add(name + ": threw " + e);

            return;
        }

        for (String v : ResultGuard.violations(raw))
        {
            if (!v.endsWith(": -0"))
            {
                failures.add(name + ": " + v);
            }
        }

        Object guarded = ResultGuard.guard(raw);

        if (!ResultGuard.isClean(guarded))
        {
            failures.add(name + ": guarded result not clean " + guarded);
        }
    }

    private void repeat(String name, Supplier<Object> port)
    {
        for (int i = 0; i < RANDOM_CASES; i++)
        {
            check(name + " #" + i, port);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void report()
    {
        if (!failures.isEmpty())
        {
            fail(failures.size() + " of " + cases + " cases failed:\n" + String.join("\n", failures.subList(0,
                    Math.min(40, failures.size()))));
        }
    }

    private double any()
    {
        return EXTREMES[random.nextInt(EXTREMES.length)];
    }

    // ------------------------------------------------------------------ inputs

    private static JsonObject pillars(double x)
    {
        return new JsonObject().put("delivery", x).put("quality", x).put("culture", x).put("okr", x).put("growth", x);
    }

    private static JsonObject weights(double w)
    {
        return pillars(w);
    }

    private static JsonArray eleven(double x)
    {
        JsonArray out = new JsonArray();

        for (int i = 0; i < 11; i++)
        {
            out.add(x);
        }

        return out;
    }

    private static JsonObject inputsFor(String value, double x)
    {
        JsonObject out = new JsonObject();

        for (Object o : CultureParameters.of(value))
        {
            out.put(((JsonObject) o).getString("key"), new JsonObject().put("raw", x).put("of", 0).put("high", 0));
        }

        return out;
    }

    private static JsonObject discipline(double x, JsonArray days)
    {
        int n = (int) Math.min(x, 1000);

        return new JsonObject()
                .put("days", days)
                .put("stale", n).put("replan", new JsonArray().add(n).add(0)).put("status", new JsonArray().add(n).add(0))
                .put("block", new JsonArray().add(n).add(0)).put("logSame", n).put("bulk", n)
                .put("dod", x).put("review", x).put("standup", x)
                .put("attendance", new JsonObject().put("score", x).put("absent", n));
    }

    private static JsonArray days(int n)
    {
        JsonArray out = new JsonArray();

        LocalDate d = LocalDate.parse("2026-08-03");

        while (out.size() < n)
        {
            if (d.getDayOfWeek().getValue() <= 5)
            {
                out.add(new JsonArray().add(d.toString()).add(out.size() % 2 == 0));
            }

            d = d.plusDays(1);
        }

        return out;
    }

    private static JsonObject records(String status, String checkIn)
    {
        JsonObject out = new JsonObject();

        for (LocalDate d = LocalDate.parse("2026-09-01"); d.isBefore(TODAY); d = d.plusDays(1))
        {
            JsonObject r = new JsonObject().put("status", status);

            if (checkIn != null)
            {
                r.put("checkIn", checkIn).put("checkOut", "23:59");
            }

            if ("L".equals(status))
            {
                r.put("leaveType", "Casual").put("appliedOn", d.toString());
            }

            out.put(d.toString(), r);
        }

        return out;
    }

    private static JsonObject full(double x)
    {
        return new JsonObject().put("start", "2026-08-03").put("weeks", 4).put("cadence", "Weekly")
                .put("updates", new JsonArray().add(new JsonObject().put("period", "2026-08-03").put("status", "Verified")))
                .put("assess", new JsonObject().put("score", x).put("result", "Pass"))
                .put("final", new JsonObject().put("mentor", new JsonObject().put("rating", x).put("by", "A"))
                        .put("manager", new JsonObject().put("rating", x).put("by", "B")));
    }

    private static JsonObject rating(JsonObject a, Double checkpoints, JsonObject rules)
    {
        return TrainingRating.rating(a, TrainingRating.checkIns(a, Set.of(), TODAY), checkpoints, rules);
    }
}
