package com.pulse.calc.core;

import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4.5: Discipline against spec culture/culture-values and against the oracle {@code discMetrics}
 * (app.js ~703) and {@code discScore} for every seeded person, with and without overrides.
 *
 * <p>Fixture: the rule inputs come from the seeded {@code seed/extracted/discipline_inputs.json}; the
 * daily-update days, the stale count and PR review adherence (Track 2) and the attendance score (task
 * 4.6) come from the prototype until their own ports supply them.
 */
class DisciplineTest
{
    private static final String METRICS = "(function(id){var m=discMetrics(person(id));if(!m)return null;"
            + "return {days:m.days.length,updated:m.upd,compliance:m.comp,stale:m.stale,replan:m.replan,status:m.status,"
            + "block:m.block,process:m.proc,base:m.base,adjustments:m.adj.map(function(a){return {label:a[0],detail:a[1],"
            + "points:a[2]}}),auto:m.auto}})";

    private static final String SCORE = "(function(id){var s=discScore(person(id));"
            + "return {score:s.score,source:s.src,reason:s.reason===undefined?null:s.reason}})";

    private static Oracle oracle;

    private static JsonObject rules;

    private static final Map<String, JsonObject> SEEDED = new HashMap<>();

    @BeforeAll
    static void start() throws Exception
    {
        oracle = Oracle.start();

        JsonObject r = (JsonObject) oracle.eval("S.rules");

        rules = new JsonObject().put("d5", r.getValue("d5")).put("d4", r.getValue("d4")).put("d3", r.getValue("d3"))
                .put("d2", r.getValue("d2")).put("attInCulture", r.getJsonObject("att").getBoolean("inCulture"));

        for (Object o : new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "discipline_inputs.json"))))
        {
            JsonObject row = (JsonObject) o;

            SEEDED.put(row.getString("person"), row);
        }
    }

    @AfterAll
    static void stop()
    {
        oracle.close();
    }

    @AfterEach
    void reset()
    {
        oracle.reset();
    }

    // ------------------------------------------------------------------ oracle

    @Test
    void metricsMatchTheOracleForEverySeededPerson()
    {
        JsonArray ids = ids();

        assertEquals(8, ids.size());

        for (Object o : ids)
        {
            String id = (String) o;

            JsonObject in = inputs(id);

            oracle.compare(METRICS, Oracle.args(id), 2, args ->
            {
                JsonObject m = Discipline.metrics(in, rules);

                if (m == null)
                {
                    return null;
                }

                JsonArray adj = new JsonArray();

                m.getJsonArray("adjustments").forEach(a -> adj.add(new JsonObject()
                        .put("label", ((JsonObject) a).getValue("label"))
                        .put("detail", ((JsonObject) a).getValue("detail"))
                        .put("points", ((JsonObject) a).getValue("points"))));

                return new JsonObject()
                        .put("days", m.getJsonArray("days").size()).put("updated", m.getValue("updated"))
                        .put("compliance", m.getValue("compliance")).put("stale", m.getValue("stale"))
                        .put("replan", m.getValue("replan")).put("status", m.getValue("status"))
                        .put("block", m.getValue("block")).put("process", m.getValue("process"))
                        .put("base", m.getValue("base")).put("adjustments", adj).put("auto", m.getValue("auto"));
            });
        }
    }

    @Test
    void scoreMatchesTheOracleWithoutOverrides()
    {
        compareScores(Map.of());
    }

    @Test
    void scoreMatchesTheOracleWithOverrides()
    {
        Map<String, JsonObject> overrides = Map.of(
                "ps", new JsonObject().put("score", 4.5).put("reason", "Covered on-call for two weeks"),
                "kr", new JsonObject().put("score", 2).put("reason", "Repeated late status updates"),
                "rk", new JsonObject().put("score", 3.5).put("reason", "No Jira data yet; manager view"));

        oracle.eval("S.discOverride = " + new JsonObject(new HashMap<>(overrides)).encode());

        compareScores(overrides);
    }

    private static void compareScores(Map<String, JsonObject> overrides)
    {
        for (Object o : ids())
        {
            String id = (String) o;

            JsonObject in = inputs(id);

            oracle.compare(SCORE, Oracle.args(id), 2, args ->
            {
                JsonObject s = Discipline.score(Discipline.metrics(in, rules), overrides.get(id));

                return s.put("reason", s.getValue("reason"));
            });
        }
    }

    // ------------------------------------------------------------------ spec scenarios

    @Test
    void underFourWeeksOfDataShowsThree()
    {
        JsonObject in = sample(100).put("days", days("2026-09-14", 10, 10));

        JsonObject m = Discipline.metrics(in, rules);

        assertEquals(2, m.getInteger("weeks"));
        assertFalse(m.getBoolean("enoughData"));
        assertEquals(5, m.getInteger("base"));
        assertEquals(3.0, m.getDouble("auto"));

        JsonObject four = Discipline.metrics(sample(100).put("days", days("2026-08-31", 20, 20)), rules);

        assertTrue(four.getBoolean("enoughData"));
        assertEquals(5.0, four.getDouble("auto"));
    }

    @Test
    void baseBandFromCompliance()
    {
        assertEquals(5, base(19));   // 95%
        assertEquals(4, base(17));   // 85%
        assertEquals(3, base(14));   // 70%
        assertEquals(2, base(10));   // 50%
        assertEquals(1, base(9));    // 45%
    }

    @Test
    void neverMoreThanOneBelowTheBaseAndNeverBelowOne()
    {
        // Every rule fires: −0.5 −0.5 −0.25 −0.25 −0.25 −0.25 −0.5 −0.25 = −2.75, limited to base − 1.
        JsonObject worst = new JsonObject()
                .put("stale", 4).put("replan", new JsonArray().add(1).add(4)).put("status", new JsonArray().add(5).add(10))
                .put("logSame", 50).put("bulk", 4).put("block", new JsonArray().add(1).add(4))
                .put("dod", 50).put("review", 50).put("standup", 50)
                .put("attendance", new JsonObject().put("score", 60).put("absent", 2));

        JsonObject m = Discipline.metrics(worst.copy().put("days", days("2026-08-31", 20, 17)), rules);

        assertEquals(4, m.getInteger("base"));
        assertEquals(8, m.getJsonArray("adjustments").size());
        assertEquals(3.0, m.getDouble("auto"));

        JsonObject low = Discipline.metrics(worst.copy().put("days", days("2026-08-31", 20, 5)), rules);

        assertEquals(1, low.getInteger("base"));
        assertEquals(1.0, low.getDouble("auto"));
    }

    @Test
    void ruleHFollowsTheAttendanceSetting()
    {
        JsonObject in = sample(100).put("days", days("2026-08-31", 20, 18))
                .put("attendance", new JsonObject().put("score", 80).put("absent", 1));

        JsonObject on = Discipline.metrics(in, rules);

        // 18 of 20 days = 90% → base 4; attendance 80% −0.25 and 1 day absent −0.25.
        assertEquals(4, on.getInteger("base"));
        assertEquals(3.5, on.getDouble("auto"));
        assertEquals(2, on.getJsonArray("adjustments").stream()
                .filter(a -> "h".equals(((JsonObject) a).getString("rule"))).count());

        JsonObject off = Discipline.metrics(in, rules.copy().put("attInCulture", false));

        assertTrue(off.getJsonArray("adjustments").isEmpty());
        assertEquals(4.0, off.getDouble("auto"));
    }

    @Test
    void overrideNeedsAScoreFromOneToFiveAndAReason()
    {
        assertEquals("Covered on-call", Discipline.validateOverride(4.0, "  Covered on-call  "));
        assertThrows(IllegalArgumentException.class, () -> Discipline.validateOverride(0.5, "A long enough reason"));
        assertThrows(IllegalArgumentException.class, () -> Discipline.validateOverride(5.5, "A long enough reason"));
        assertThrows(IllegalArgumentException.class, () -> Discipline.validateOverride(null, "A long enough reason"));
        assertThrows(IllegalArgumentException.class, () -> Discipline.validateOverride(4.0, "too short"));

        JsonObject s = Discipline.score(null, new JsonObject().put("score", 4).put("reason", "Covered on-call"));

        assertEquals("override", s.getString("source"));
        assertEquals("Covered on-call", s.getString("reason"));
        assertEquals("none", Discipline.score(null, null).getString("source"));
        assertEquals(3.0, Discipline.score(null, null).getDouble("score"));
        assertNull(Discipline.metrics(null, rules));
    }

    // ------------------------------------------------------------------ fixture

    private static JsonArray ids()
    {
        return (JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})");
    }

    /** The port's inputs for a person: the seeded rule inputs plus the prototype's measured ones. */
    private static JsonObject inputs(String id)
    {
        JsonObject seeded = SEEDED.get(id);

        if (seeded == null)
        {
            return null;
        }

        String p = "person('" + id + "')";

        JsonObject att = (JsonObject) oracle.eval("(function(a){return {score: a.score, absent: a.absent}})(attQ3(" + p + "))");

        return new JsonObject()
                .put("days", oracle.call("discDays", Oracle.js(p)))
                .put("stale", oracle.eval(p + ".tasks.filter(function(t){return t.status!=='Done'&&idle(t," + p
                        + ")>S.rules.staleDays}).length"))
                .put("replan", seeded.getJsonArray("replan"))
                .put("status", seeded.getJsonArray("status"))
                .put("block", seeded.getJsonArray("block"))
                .put("logSame", seeded.getValue("logSame"))
                .put("bulk", seeded.getValue("bulk"))
                .put("dod", seeded.getValue("dod"))
                .put("review", oracle.call("prDiscReview", id, seeded.getValue("review")))
                .put("standup", seeded.getValue("standup"))
                .put("attendance", att);
    }

    private static JsonObject sample(int all)
    {
        return new JsonObject()
                .put("stale", 0).put("replan", new JsonArray().add(1).add(0)).put("status", new JsonArray().add(0).add(10))
                .put("logSame", all).put("bulk", 0).put("block", new JsonArray().add(1).add(1))
                .put("dod", all).put("review", all).put("standup", all);
    }

    /** {@code n} weekdays from {@code from}, the first {@code updated} of them with an update. */
    private static JsonArray days(String from, int n, int updated)
    {
        JsonArray out = new JsonArray();

        LocalDate d = LocalDate.parse(from);

        while (out.size() < n)
        {
            if (d.getDayOfWeek().getValue() <= 5)
            {
                out.add(new JsonArray().add(d.toString()).add(out.size() < updated));
            }

            d = d.plusDays(1);
        }

        return out;
    }

    private static int base(int updatedOf20)
    {
        return Discipline.metrics(sample(100).put("days", days("2026-08-31", 20, updatedOf20)), rules).getInteger("base");
    }
}
