package com.pulse.calc.core;

import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4.7: the training rating against spec growth/training-mentoring "Training rating" and against
 * the oracle {@code trRating} (app.js ~1652), {@code ciStats} and {@code trPerson} for every seeded
 * assignment and trainee.
 *
 * <p>Fixture: the seeded {@code seed/extracted/training_assignments.json} and the checkpoint 360°
 * requests in {@code review_requests.json}; Track 3 loads the same rows (task 21.1).
 */
class TrainingRatingTest
{
    private static final LocalDate TODAY = LocalDate.parse("2026-09-28");

    private static final String A = "TASSIGN.filter(function(x){return x.id===id})[0]";

    private static final String RATING = "(function(id){var a=" + A + ",r=trRating(a);return {parts:r.parts.map(function(p){"
            + "return {part:p[0],value:p[1],weight:p[2],detail:p[3]}}),score:r.score,'final':r['final'],"
            + "state:r.score===null?'Not rated':r['final']?'Final':'Provisional',band:{tone:r.band[0],label:r.band[1]}}})";

    private static final String CHECK_INS = "(function(id){var a=" + A + ",c=ciStats(a);return {periods:c.ps.map(function(p){"
            + "return {key:p.key,from:p.from,to:p.to,current:p.cur,status:pStat(a,p)}}),verified:c.v,submitted:c.s,"
            + "needsWork:c.n,missed:c.m,total:c.total}})";

    private static final String CHECKPOINTS = "(function(id){return trCpAvg(" + A + ")})";

    private static final String PERSON = "(function(pid){var t=trPerson(pid);return {score:t.score,'final':t['final'],n:t.n}})";

    private static final JsonObject RULES = new JsonObject().put("trWa", 40).put("trWm", 20).put("trWg", 20)
            .put("trWc", 10).put("trWp", 10);

    private static Oracle oracle;

    private static JsonObject rules;

    private static Set<LocalDate> holidays;

    private static final Map<String, JsonObject> ASSIGNMENTS = new LinkedHashMap<>();

    private static JsonArray requests;

    @BeforeAll
    static void start() throws Exception
    {
        oracle = Oracle.start();

        assertEquals(TODAY.toString(), oracle.eval("TODAY"));

        rules = (JsonObject) oracle.eval("({trWa:S.rules.trWa,trWm:S.rules.trWm,trWg:S.rules.trWg,trWc:S.rules.trWc,"
                + "trWp:S.rules.trWp})");

        holidays = new HashSet<>();

        for (Object o : new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "holidays.json"))))
        {
            holidays.add(LocalDate.parse(((JsonObject) o).getString("date")));
        }

        for (Object o : new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "training_assignments.json"))))
        {
            JsonObject a = (JsonObject) o;

            ASSIGNMENTS.put(a.getString("id"), a);
        }

        requests = new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "review_requests.json")));
    }

    @AfterAll
    static void stop()
    {
        oracle.close();
    }

    // ------------------------------------------------------------------ oracle

    @Test
    void defaultWeightsAreTheSpecWeights()
    {
        assertEquals(RULES, rules);
    }

    @Test
    void checkInsMatchTheOracleForEverySeededAssignment()
    {
        assertEquals(7, ASSIGNMENTS.size());

        for (String id : ASSIGNMENTS.keySet())
        {
            oracle.compare(CHECK_INS, Oracle.args(id), 10, args ->
            {
                JsonObject c = TrainingRating.checkIns(input(id), holidays, TODAY);

                c.getJsonArray("periods").forEach(p -> ((JsonObject) p).remove("label"));

                return c;
            });
        }
    }

    @Test
    void checkpointAverageMatchesTheOracleForEverySeededAssignment()
    {
        int withReviews = 0;

        for (String id : ASSIGNMENTS.keySet())
        {
            Double avg = TrainingRating.checkpointAverage(checkpoints(id));

            if (avg != null)
            {
                withReviews++;

                oracle.compare(CHECKPOINTS, Oracle.args(id), 10, args -> avg);
            }
            else
            {
                assertNull(oracle.call(CHECKPOINTS, Oracle.args(id)), id);
            }
        }

        assertTrue(withReviews > 0);
    }

    @Test
    void ratingMatchesTheOracleForEverySeededAssignment()
    {
        Set<String> states = new HashSet<>();

        for (String id : ASSIGNMENTS.keySet())
        {
            oracle.compare(RATING, Oracle.args(id), 10, args ->
            {
                JsonObject r = rating(id, rules);

                states.add(r.getString("state"));

                return r;
            });
        }

        // The seed has both Final and Provisional ratings.
        assertTrue(states.contains(TrainingRating.FINAL), states.toString());
        assertTrue(states.contains(TrainingRating.PROVISIONAL), states.toString());
    }

    @Test
    void ratingMatchesTheOracleWithAlteredWeights()
    {
        JsonObject altered = new JsonObject().put("trWa", 20).put("trWm", 30).put("trWg", 10).put("trWc", 25).put("trWp", 0);

        oracle.eval("S.rules.trWa=20;S.rules.trWm=30;S.rules.trWg=10;S.rules.trWc=25;S.rules.trWp=0");

        try
        {
            for (String id : ASSIGNMENTS.keySet())
            {
                oracle.compare(RATING, Oracle.args(id), 10, args -> rating(id, altered));
            }
        }
        finally
        {
            oracle.reset();
        }
    }

    @Test
    void overallMatchesTheOracleForEveryTrainee()
    {
        Map<String, JsonArray> byPerson = new LinkedHashMap<>();

        ASSIGNMENTS.forEach((id, a) -> byPerson.computeIfAbsent(a.getString("pid"), k -> new JsonArray())
                .add(new JsonObject().put("weeks", a.getJsonObject("plan").getInteger("weeks")).put("rating", rating(id, rules))));

        assertEquals(Set.of("rk", "an", "nj", "kr", "dp"), byPerson.keySet());

        byPerson.forEach((pid, plans) -> oracle.compare(PERSON, Oracle.args(pid), 10, args -> TrainingRating.person(plans)));
    }

    // ------------------------------------------------------------------ spec scenarios

    @Test
    void provisionalWithoutAManagerReview()
    {
        JsonObject a = assignment("2026-08-03", 4, "Weekly")
                .put("assess", new JsonObject().put("score", 80).put("result", "Pass"))
                .put("final", new JsonObject().put("mentor", review(4)));

        JsonObject r = rate(a, null);

        assertFalse(r.getBoolean("final"));
        assertEquals(TrainingRating.PROVISIONAL, r.getString("state"));

        a.getJsonObject("final").put("manager", review(5));

        assertEquals(TrainingRating.FINAL, rate(a, null).getString("state"));
    }

    @Test
    void fullFormulaAndReWeighting()
    {
        // Week of 3 Aug to week of 24 Aug: 3 verified, 1 needs work.
        JsonObject a = assignment("2026-08-03", 4, "Weekly")
                .put("updates", new JsonArray().add(update("2026-08-03", "Verified")).add(update("2026-08-10", "Verified"))
                        .add(update("2026-08-17", "Needs work")).add(update("2026-08-24", "Verified")))
                .put("assess", new JsonObject().put("score", 80).put("result", "Pass"))
                .put("final", new JsonObject().put("mentor", review(4)).put("manager", review(5)));

        double checkIns = 1 + 4.0 * 3 / 4;   // 4.0

        JsonObject all = rate(a, 3.5);

        assertEquals(0.4 * 4 + 0.2 * 4 + 0.2 * 5 + 0.1 * checkIns + 0.1 * 3.5, all.getDouble("score"), 1e-12);
        assertEquals("Strong", all.getJsonObject("band").getString("label"));

        // Only the assessment: the score is the assessment alone.
        JsonObject only = assignment("2026-08-03", 4, "Weekly")
                .put("updates", new JsonArray().add(update("2026-08-03", "Submitted")).add(update("2026-08-10", "Submitted"))
                        .add(update("2026-08-17", "Submitted")).add(update("2026-08-24", "Submitted")))
                .put("assess", new JsonObject().put("score", 70).put("result", "Pass"));

        assertEquals(3.5, rate(only, null).getDouble("score"), 1e-12);

        // Weights that do not total 100 are re-normalised.
        JsonObject half = new JsonObject().put("trWa", 20).put("trWm", 10).put("trWg", 10).put("trWc", 5).put("trWp", 5);

        assertEquals(all.getDouble("score"), TrainingRating.rating(a, TrainingRating.checkIns(a, Set.of(), TODAY), 3.5, half)
                .getDouble("score"), 1e-12);
    }

    @Test
    void notRatedWithNoPart()
    {
        JsonObject r = rate(assignment("2026-09-28", 4, "Weekly"), null);

        assertNull(r.getValue("score"));
        assertEquals(TrainingRating.NOT_RATED, r.getString("state"));
        assertEquals(TrainingRating.NOT_RATED, r.getJsonObject("band").getString("label"));
    }

    @Test
    void bands()
    {
        assertEquals("Outstanding", label(4.5));
        assertEquals("Strong", label(4.49));
        assertEquals("Strong", label(3.75));
        assertEquals("Meets", label(3.0));
        assertEquals("Needs support", label(2.25));
        assertEquals("Not yet", label(2.24));
    }

    @Test
    void checkInPeriods()
    {
        // Weekly from a Wednesday: the first week runs from the start, the current week is Due.
        JsonObject weekly = TrainingRating.checkIns(assignment("2026-09-16", 4, "Weekly"), Set.of(), TODAY);

        JsonArray wp = weekly.getJsonArray("periods");

        assertEquals(3, wp.size());
        assertEquals("2026-09-14", wp.getJsonObject(2).getString("key"));
        assertEquals("2026-09-16", wp.getJsonObject(2).getString("from"));
        assertEquals(TrainingRating.DUE, wp.getJsonObject(0).getString("status"));
        assertEquals(2, weekly.getInteger("missed"));

        // Daily over a holiday week: Friday 2 Oct is left out; the plan ends on 4 Oct.
        LocalDate later = LocalDate.parse("2026-10-09");

        JsonObject daily = TrainingRating.checkIns(assignment("2026-09-28", 1, "Daily"),
                Set.of(LocalDate.parse("2026-10-02")), later);

        assertEquals(4, daily.getInteger("total"));
        assertEquals(4, daily.getInteger("missed"));

        // Monthly: calendar months, the first one from the start.
        JsonObject monthly = TrainingRating.checkIns(assignment("2026-07-20", 10, "Monthly")
                .put("updates", new JsonArray().add(update("2026-07", "Verified"))), Set.of(), TODAY);

        JsonArray mp = monthly.getJsonArray("periods");

        assertEquals(3, mp.size());
        assertEquals("2026-07-20", mp.getJsonObject(2).getString("from"));
        assertEquals(1, monthly.getInteger("verified"));
        assertEquals(1, monthly.getInteger("missed"));
        assertEquals(TrainingRating.DUE, mp.getJsonObject(0).getString("status"));
    }

    @Test
    void checkpointAverageLeavesOutSelfAndUnsubmitted()
    {
        JsonArray rs = new JsonArray()
                .add(request("self", "Submitted", 5, 5))
                .add(request("manager", "Submitted", 3, null, 4))
                .add(request("peer", "Approved", 1, 1))
                .add(request("peer", "Submitted", 2));

        assertEquals(3.0, TrainingRating.checkpointAverage(rs), 1e-12);
        assertNull(TrainingRating.checkpointAverage(new JsonArray().add(request("self", "Submitted", 4))));
    }

    @Test
    void overallIsPlanLengthWeightedOverFinalRatings()
    {
        JsonArray plans = new JsonArray()
                .add(plan(12, 4.0, true))
                .add(plan(4, 3.0, true))
                .add(plan(8, 1.0, false));

        JsonObject t = TrainingRating.person(plans);

        assertEquals((4.0 * 12 + 3.0 * 4) / 16, t.getDouble("score"), 1e-12);
        assertTrue(t.getBoolean("final"));
        assertEquals(2, t.getInteger("n"));

        JsonObject provisional = TrainingRating.person(new JsonArray().add(plan(8, 1.0, false)).add(plan(2, 3.0, false)));

        assertEquals((8 + 6) / 10.0, provisional.getDouble("score"), 1e-12);
        assertFalse(provisional.getBoolean("final"));
        assertNull(TrainingRating.person(new JsonArray()).getValue("score"));
    }

    // ------------------------------------------------------------------ fixture

    /** A seeded assignment in the port's shape. */
    private static JsonObject input(String id)
    {
        JsonObject a = ASSIGNMENTS.get(id);

        JsonObject plan = a.getJsonObject("plan");

        JsonArray updates = new JsonArray();

        a.getJsonArray("updates", new JsonArray()).forEach(u -> updates.add(new JsonObject()
                .put("period", ((JsonObject) u).getString("period")).put("status", ((JsonObject) u).getString("status"))));

        return new JsonObject()
                .put("start", a.getString("start"))
                .put("end", a.getString("end"))
                .put("weeks", plan.getInteger("weeks"))
                .put("cadence", a.getString("cadence", plan.getString("cadence", "Weekly")))
                .put("updates", updates)
                .put("assess", a.getJsonObject("assess"))
                .put("final", a.getJsonObject("final"));
    }

    /** The submitted checkpoint 360° requests of an assignment ({@code ct-<id>-<day>} cycles). */
    private static JsonArray checkpoints(String id)
    {
        JsonArray out = new JsonArray();

        for (Object o : requests)
        {
            JsonObject r = (JsonObject) o;

            if (r.getString("cycle").startsWith("ct-" + id + "-") && !"Declined".equals(r.getString("status")))
            {
                JsonObject answers = r.getJsonObject("answers");

                out.add(new JsonObject().put("group", r.getString("group")).put("status", r.getString("status"))
                        .put("scores", answers == null ? new JsonArray() : answers.getJsonArray("scores", new JsonArray())));
            }
        }

        return out;
    }

    private static JsonObject rating(String id, JsonObject weights)
    {
        JsonObject a = input(id);

        return TrainingRating.rating(a, TrainingRating.checkIns(a, holidays, TODAY),
                TrainingRating.checkpointAverage(checkpoints(id)), weights);
    }

    private static JsonObject rate(JsonObject a, Double checkpoints)
    {
        return TrainingRating.rating(a, TrainingRating.checkIns(a, Set.of(), TODAY), checkpoints, RULES);
    }

    private static JsonObject assignment(String start, int weeks, String cadence)
    {
        return new JsonObject().put("start", start).put("weeks", weeks).put("cadence", cadence).put("updates", new JsonArray());
    }

    private static JsonObject update(String period, String status)
    {
        return new JsonObject().put("period", period).put("status", status);
    }

    private static JsonObject review(int rating)
    {
        return new JsonObject().put("rating", rating).put("by", "Sara Khan");
    }

    private static JsonObject request(String group, String status, Integer... scores)
    {
        JsonArray s = new JsonArray();

        for (Integer x : scores)
        {
            s.add(x);
        }

        return new JsonObject().put("group", group).put("status", status).put("scores", s);
    }

    private static JsonObject plan(int weeks, double score, boolean fin)
    {
        return new JsonObject().put("weeks", weeks)
                .put("rating", new JsonObject().put("score", score).put("final", fin));
    }

    private static String label(double score)
    {
        return TrainingRating.band(score).getString("label");
    }
}
