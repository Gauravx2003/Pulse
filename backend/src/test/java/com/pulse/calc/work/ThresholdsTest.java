package com.pulse.calc.work;

import com.pulse.calc.core.Appraisal;
import com.pulse.calc.core.CoreThresholds;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.RuleCatalogue;
import com.pulse.calc.people.PeopleThresholds;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 23.6: the thresholds, tone bands and team maths moved into {@code calc/} keep the behaviour the
 * modules and screens had, and are registered for CalcService ({@code core.thresholds},
 * {@code core.proposed}, {@code work.thresholds}, {@code work.effortTotals}, {@code people.thresholds}).
 */
class ThresholdsTest
{

    @Test
    void theThresholdFormulasAreRegistered()
    {
        List<String> names = FormulaRegistry.discover().formulas().stream().map(f -> f.name()).toList();

        for (String name : List.of("core.thresholds", "core.proposed", "work.thresholds", "work.effortTotals", "people.thresholds"))
        {
            assertTrue(names.contains(name), name);
        }
    }

    @Test
    void aToneIsTheFirstBandTheValueReaches()
    {
        JsonArray bands = CoreThresholds.SCORE_TONES;

        assertEquals("ok", CoreThresholds.tone(4, bands));
        assertEquals("ok", CoreThresholds.tone(4.6, bands));
        assertEquals("brand", CoreThresholds.tone(3.99, bands));
        assertEquals("brand", CoreThresholds.tone(3, bands));
        assertEquals("accent", CoreThresholds.tone(2.9, bands));
        assertEquals("", CoreThresholds.tone(null, bands));
    }

    @Test
    void theProposedRatingKeepsTheCalibrationCutOffs()
    {
        assertEquals("Exceeds", CoreThresholds.proposed(4.2));
        assertEquals("Meets", CoreThresholds.proposed(4.19));
        assertEquals("Meets", CoreThresholds.proposed(3.5));
        assertEquals("Developing", CoreThresholds.proposed(3.49));
    }

    @Test
    void workThresholdsKeepTheModulesNumbers()
    {
        JsonObject t = WorkThresholds.json(RuleCatalogue.defaults());

        assertEquals(2, t.getInteger("staleItems"));
        assertEquals(10, t.getInteger("oneOnOneOverdueDays"));
        assertEquals(new JsonObject().put("lowMood", 2).put("highWorkload", 5), t.getJsonObject("pulse"));

        assertTrue(ActionCenter.low(2, 1));
        assertTrue(ActionCenter.low(5, 5));
        assertFalse(ActionCenter.low(3, 4));

        JsonObject tones = t.getJsonObject("tones");

        // as the screens had them: Progress mood, workload and PR meter; Action center pulse, forecast and coverage
        assertEquals(List.of("ok", "brand", "danger"), tones(tones.getJsonArray("mood"), 4, 3, 2));
        assertEquals(List.of("danger", "warn", ""), tones(tones.getJsonArray("workload"), 5, 4, 3));
        assertEquals(List.of("", "accent"), tones(tones.getJsonArray("teamMood"), 3.5, 3.4));
        assertEquals(List.of("warn", "info"), tones(tones.getJsonArray("teamWorkload"), 4, 3.9));
        assertEquals(List.of("", "accent", "danger"), tones(tones.getJsonArray("forecastFit"), 100, 80, 79));
        assertEquals(List.of("ok", "info", ""), tones(tones.getJsonArray("coverage"), 4, 2, 1));
        assertEquals(List.of("ok", "accent", "danger"), tones(tones.getJsonArray("prCompliance"), 85, 70, 69));

        // the PR meter follows rule prTarget
        assertEquals("ok", CoreThresholds.tone(75, WorkThresholds.prComplianceTones(new JsonObject().put("prTarget", 75))));

        assertEquals("ok", WorkThresholds.gainTone(0.1));
        assertEquals("", WorkThresholds.gainTone(0.05));
        assertEquals("danger", WorkThresholds.gainTone(-0.1));
        assertEquals("", WorkThresholds.gainTone(null));
    }

    @Test
    void peopleAndCoreThresholdsKeepTheModulesNumbers()
    {
        assertEquals(16.0, PeopleThresholds.json().getDouble("trainingWeeklyHours"));

        JsonObject core = CoreThresholds.json();

        assertEquals(3, core.getInteger("newJoinerMonths"));
        assertEquals(new JsonObject().put("exceeds", 4.2).put("meets", 3.5), core.getJsonObject("index"));
        assertEquals(new JsonObject().put("shortNoticeDays", 2.0).put("officePct", 70).put("lateExcess", 3).put("uploadDueDays", 5),
                core.getJsonObject("attendance"));
        assertEquals(TeamFormulas.NEW_JOINER_MONTHS, CoreThresholds.NEW_JOINER_MONTHS);
    }

    @Test
    void teamEffortTotalsAddUpThePeopleAndTheShare()
    {
        JsonObject a = new JsonObject().put("logged", 60.0).put("daysWorked", 7.5).put("work", 8).put("unplanned", 10.0).put("untracked", 5.0)
                .put("weekend", 1).put("visitH", 0.0).put("ptsConf", 2.5).put("ptsPend", 1.0)
                .put("items", new JsonArray().add(new JsonObject().put("isDone", true)).add(new JsonObject().put("isDone", false)));

        JsonObject b = new JsonObject().put("logged", 40.0).put("daysWorked", 5.0).put("work", 8).put("unplanned", 20.0).put("untracked", 0.0)
                .put("weekend", 0).put("visitH", 6.0).put("ptsConf", 1.0).put("ptsPend", 0.0).put("items", new JsonArray());

        JsonObject t = Effort.teamTotals(Arrays.asList(a, b, null, new JsonObject().put("notApplicable", true)), RuleCatalogue.defaults());

        assertEquals(100.0, t.getDouble("logged"));
        assertEquals(12.5, t.getDouble("daysWorked"));
        assertEquals(16.0, t.getDouble("work"));
        assertEquals(35.0, t.getDouble("unplanned"));
        assertEquals(35, t.getInteger("share"));
        assertTrue(t.getBoolean("planning"), "35% is at or above unplannedAlert 30");
        assertEquals(1.0, t.getDouble("weekend"));
        assertEquals(6.0, t.getDouble("visitHours"));
        assertEquals(2, t.getInteger("items"));
        assertEquals(1, t.getInteger("doneItems"));
        assertEquals(3.5, t.getDouble("points"));
        assertEquals(1.0, t.getDouble("pending"));

        assertEquals(0, Effort.share(5, 0));
        assertEquals(33, Effort.share(1, 3));
    }

    @Test
    void appraisalMoneyKeepsTheTableMaths()
    {
        assertEquals(11.2, Appraisal.newCtc(10, 12));
        assertNull(Appraisal.newCtc(null, 12));

        JsonObject rec = new JsonObject().put("eligible", true).put("band", new JsonObject().put("lo", 7).put("hi", 10));

        assertFalse(Appraisal.exception(rec, 8));
        assertTrue(Appraisal.exception(rec, 12));
        assertFalse(Appraisal.exception(rec.copy().put("eligible", false), 12));

        JsonArray rows = new JsonArray()
                .add(new JsonObject().put("ctc", 10).put("pct", 10))
                .add(new JsonObject().put("ctc", 20).put("pct", 15))
                .add(new JsonObject().put("ctc", null).put("pct", 20));

        JsonObject money = Appraisal.budget(rows, new JsonObject().put("budget", 12));

        assertEquals(30.0, money.getDouble("payroll"));
        assertEquals(3.6, money.getDouble("budget"));
        assertEquals(4.0, money.getDouble("spend"));
        assertTrue(money.getBoolean("overBudget"));
        assertEquals(13.3, money.getDouble("averagePct"));
    }

    private static List<String> tones(JsonArray bands, Number... values)
    {
        return Arrays.stream(values).map(v -> CoreThresholds.tone(v, bands)).toList();
    }
}
