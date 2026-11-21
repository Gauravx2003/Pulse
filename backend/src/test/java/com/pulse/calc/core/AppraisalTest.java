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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4.8: the appraisal score and recommendation against spec appraisal/appraisal and against the
 * oracle {@code aprRecommend} (app.js ~996) for every seeded person, under the default Appraisal
 * Standards and an altered set.
 *
 * <p>Fixture: 360° cycles and responses from the seeded {@code review_cycles.json} and
 * {@code review_requests.json}, joining dates from {@code joined_dates.json}. The live index, the 3
 * closed quarters' index ({@code trend}) and the Discipline score come from the prototype until
 * CalcService (task 4.10) and the snapshot store (task 4.9) supply them.
 */
class AppraisalTest
{
    /** The prototype counts service to the end of the appraisal year. */
    private static final LocalDate AS_OF = LocalDate.parse("2026-09-30");

    private static final String RECOMMEND = "(function(id){var r=aprRecommend(person(id)),a=r.a;return {pct:r.pct,"
            + "band:r.band,eligible:r.eligible,months:r.months===undefined?null:r.months,"
            + "prorated:r.prorated===undefined?null:r.prorated,why:r.why,parts:a.parts.map(function(x){return {key:x[0],"
            + "label:x[1],value:x[2],weight:aprRules().w[x[0]]}}),score:a.score,cycle:a.cycle,missing:a.missing}})";

    private static Oracle oracle;

    private static JsonArray cycles;

    private static JsonArray requests;

    private static final Map<String, LocalDate> JOINED = new HashMap<>();

    @BeforeAll
    static void start() throws Exception
    {
        oracle = Oracle.start();

        cycles = new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "review_cycles.json")));
        requests = new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "review_requests.json")));

        for (Object o : new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "joined_dates.json"))))
        {
            JsonObject j = (JsonObject) o;

            JOINED.put(j.getString("person"), LocalDate.parse(j.getString("joined_on")));
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
    void defaultRulesAreThePrototypeDefaults()
    {
        JsonObject expected = (JsonObject) oracle.eval("APR_DEFAULT");

        JsonObject actual = Appraisal.defaultRules();

        assertEquals(expected.getJsonObject("w"), actual.getJsonObject("w"));
        assertEquals(expected.getInteger("max"), actual.getInteger("max"));
        assertEquals(expected.getInteger("budget"), actual.getInteger("budget"));
        assertEquals(expected.getInteger("minMonths"), actual.getInteger("minMonths"));

        for (int i = 0; i < 6; i++)
        {
            JsonObject e = expected.getJsonArray("bands").getJsonObject(i);
            JsonObject a = actual.getJsonArray("bands").getJsonObject(i);

            assertEquals(e.getDouble("min"), a.getDouble("min"));
            assertEquals(e.getInteger("lo"), a.getInteger("lo"));
            assertEquals(e.getInteger("hi"), a.getInteger("hi"));
            assertEquals(e.getString("name"), a.getString("name"));
        }

        // A copy: changing it does not change the defaults.
        actual.put("max", 99);

        assertEquals(20, Appraisal.defaultRules().getInteger("max"));
    }

    @Test
    void recommendationMatchesTheOracleForEverySeededPerson()
    {
        Set<String> seen = compareAll(Appraisal.defaultRules());

        // The seed covers an eligible, a pro-rated and an ineligible person.
        assertTrue(seen.contains("eligible"), seen.toString());
        assertTrue(seen.contains("prorated"), seen.toString());
        assertTrue(seen.contains("ineligible"), seen.toString());
    }

    @Test
    void recommendationMatchesTheOracleWithAlteredStandards()
    {
        JsonObject rules = Appraisal.defaultRules();

        rules.put("w", new JsonObject().put("perf", 40).put("r360", 30).put("mgr", 10).put("disc", 20))
                .put("max", 12).put("minMonths", 9);

        rules.getJsonArray("bands").getJsonObject(1).put("min", 3.9).put("lo", 9);

        oracle.eval("aprRules();S.apr=" + rules.encode());

        compareAll(rules);
    }

    private static Set<String> compareAll(JsonObject rules)
    {
        JsonArray ids = (JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})");

        assertEquals(8, ids.size());

        Set<String> seen = new HashSet<>();

        for (Object o : ids)
        {
            String id = (String) o;

            oracle.compare(RECOMMEND, Oracle.args(id), 10, args ->
            {
                JsonObject parts = Appraisal.parts(inputs(id), rules);

                JsonObject r = Appraisal.recommend(parts, JOINED.getOrDefault(id, LocalDate.parse("2025-01-01")), AS_OF, rules);

                seen.add(!r.getBoolean("eligible") ? "ineligible" : r.getBoolean("prorated") ? "prorated" : "eligible");

                r.remove("improvementPlan");

                return r.put("months", r.getValue("months")).put("prorated", r.getValue("prorated"))
                        .put("parts", parts.getJsonArray("parts")).put("score", parts.getValue("score"))
                        .put("cycle", parts.getValue("cycle")).put("missing", parts.getJsonArray("missing"));
            });
        }

        return seen;
    }

    // ------------------------------------------------------------------ spec scenarios

    @Test
    void missing360ReWeightsTheOtherParts()
    {
        JsonObject rules = Appraisal.defaultRules();

        JsonObject all = Appraisal.parts(new JsonObject().put("perf", 4.0).put("r360", 3.0).put("mgr", 4.0).put("disc", 3.0), rules);

        assertEquals(0.5 * 4 + 0.2 * 3 + 0.2 * 4 + 0.1 * 3, all.getDouble("score"), 1e-12);

        JsonObject no360 = Appraisal.parts(new JsonObject().put("perf", 4.0).put("mgr", 4.0).put("disc", 3.0), rules);

        assertEquals((50 * 4 + 20 * 4 + 10 * 3) / 80.0, no360.getDouble("score"), 1e-12);
        assertEquals(new JsonArray().add("360° feedback (peers & cross-dept)"), no360.getJsonArray("missing"));

        // A 360° cycle needs 2 peer or cross-department responses; self and manager do not count.
        JsonArray one = new JsonArray().add(cycle("Q3", response("peer", 4), response("self", 5), response("manager", 5)));

        assertNull(Appraisal.feedback360(one).getValue("score"));
        assertEquals(5.0, Appraisal.managerReview(one));

        JsonArray two = new JsonArray()
                .add(cycle("Q2", response("peer", 2, 2), response("cross", 2)))
                .add(cycle("Q3", response("peer", 4)));

        assertEquals(2.0, Appraisal.feedback360(two).getDouble("score"));
        assertEquals("Q2", Appraisal.feedback360(two).getString("cycle"));
    }

    @Test
    void bandsAndPositionInBand()
    {
        JsonObject rules = Appraisal.defaultRules();

        assertEquals("Outstanding", Appraisal.band(4.5, rules).getString("name"));
        assertEquals("Exceeds", Appraisal.band(4.49, rules).getString("name"));
        assertEquals("Strong", Appraisal.band(3.5, rules).getString("name"));
        assertEquals("Meets", Appraisal.band(3.0, rules).getString("name"));
        assertEquals("Developing", Appraisal.band(2.5, rules).getString("name"));
        assertEquals("Below expectations", Appraisal.band(2.49, rules).getString("name"));

        // Strong 3.5–4.0 → 7–10%: 3.75 is half way, 8.5%.
        assertEquals(8.5, pct(3.75, "2020-01-01"));

        // 3.6 → 7 + 3 × 0.2 = 7.6 → 7.5%.
        assertEquals(7.5, pct(3.6, "2020-01-01"));

        // Outstanding runs to 5: 5.0 → 20%.
        assertEquals(20.0, pct(5.0, "2020-01-01"));

        // Below expectations: 0% and an improvement plan.
        JsonObject below = recommend(2.0, "2020-01-01");

        assertEquals(0.0, below.getDouble("pct"));
        assertTrue(below.getBoolean("improvementPlan"));
        assertFalse(recommend(3.0, "2020-01-01").getBoolean("improvementPlan"));
    }

    @Test
    void capAndHalfPercentRounding()
    {
        JsonObject rules = Appraisal.defaultRules().put("max", 12);

        JsonObject parts = Appraisal.parts(new JsonObject().put("perf", 4.8), rules);

        assertEquals(12.0, Appraisal.recommend(parts, LocalDate.parse("2020-01-01"), AS_OF, rules).getDouble("pct"));

        assertEquals(7.5, Appraisal.halfPercent(7.25));
        assertEquals(7.0, Appraisal.halfPercent(7.24));
        assertEquals(8.0, Appraisal.halfPercent(7.75));

        JsonObject standard = Appraisal.defaultRules();

        assertEquals(20.0, Appraisal.checkCap(20.0, standard));
        assertThrows(IllegalArgumentException.class, () -> Appraisal.checkCap(22.0, standard));
        assertThrows(IllegalArgumentException.class, () -> Appraisal.checkCap(-1.0, standard));
        assertThrows(IllegalArgumentException.class, () -> Appraisal.checkCap(null, standard));
    }

    @Test
    void eligibilityAndProRating()
    {
        // 4 months of service: not eligible, 0.
        JsonObject four = recommend(3.75, "2026-05-20");

        assertFalse(four.getBoolean("eligible"));
        assertEquals(0.0, four.getDouble("pct"));
        assertEquals(4, four.getInteger("months"));
        assertEquals("Joined 4 months ago (needs 6)", four.getString("why"));

        // 6 months: eligible, 8.5% × 6 ÷ 12 = 4.25 → 4.5%.
        JsonObject six = recommend(3.75, "2026-03-02");

        assertTrue(six.getBoolean("eligible"));
        assertTrue(six.getBoolean("prorated"));
        assertEquals(4.5, six.getDouble("pct"));

        // 12 months or more: full.
        JsonObject year = recommend(3.75, "2025-09-30");

        assertFalse(year.getBoolean("prorated"));
        assertEquals(8.5, year.getDouble("pct"));

        // Months ignore the day of the month.
        assertEquals(12, Appraisal.monthsBetween(LocalDate.parse("2025-09-30"), LocalDate.parse("2026-09-01")));

        // No review data at all.
        JsonObject none = Appraisal.recommend(Appraisal.parts(new JsonObject(), Appraisal.defaultRules()),
                LocalDate.parse("2020-01-01"), AS_OF, Appraisal.defaultRules());

        assertEquals("Not enough review data", none.getString("why"));
        assertFalse(none.getBoolean("eligible"));
    }

    // ------------------------------------------------------------------ fixture

    /** The appraisal inputs for a seeded person. */
    private static JsonObject inputs(String id)
    {
        JsonArray mine = new JsonArray();

        for (Object o : cycles)
        {
            JsonObject c = (JsonObject) o;

            JsonArray responses = new JsonArray();

            for (Object x : requests)
            {
                JsonObject r = (JsonObject) x;

                if (c.getString("id").equals(r.getString("cycle")) && id.equals(r.getString("subject")))
                {
                    JsonObject answers = r.getJsonObject("answers");

                    responses.add(new JsonObject().put("group", r.getString("group")).put("status", r.getString("status"))
                            .put("scores", answers == null ? new JsonArray() : answers.getJsonArray("scores", new JsonArray())));
                }
            }

            mine.add(new JsonObject().put("name", c.getString("name")).put("responses", responses));
        }

        String p = "person('" + id + "')";

        JsonObject f360 = Appraisal.feedback360(mine);

        return new JsonObject()
                .put("perf", Appraisal.performance((JsonArray) oracle.eval(p + ".trend.slice(0,3)"),
                        ((Number) oracle.eval("index(" + p + ")")).doubleValue()))
                .put("r360", f360.getValue("score"))
                .put("cycle", f360.getString("cycle"))
                .put("mgr", Appraisal.managerReview(mine))
                .put("disc", oracle.eval("discScore(" + p + ").score"));
    }

    private static JsonObject cycle(String name, JsonObject... responses)
    {
        JsonArray rs = new JsonArray();

        for (JsonObject r : responses)
        {
            rs.add(r);
        }

        return new JsonObject().put("name", name).put("responses", rs);
    }

    private static JsonObject response(String group, int... scores)
    {
        JsonArray s = new JsonArray();

        for (int x : scores)
        {
            s.add(x);
        }

        return new JsonObject().put("group", group).put("status", "Submitted").put("scores", s);
    }

    private static JsonObject recommend(double score, String joinedOn)
    {
        JsonObject rules = Appraisal.defaultRules();

        return Appraisal.recommend(Appraisal.parts(new JsonObject().put("perf", score), rules),
                LocalDate.parse(joinedOn), AS_OF, rules);
    }

    private static double pct(double score, String joinedOn)
    {
        return recommend(score, joinedOn).getDouble("pct");
    }
}
