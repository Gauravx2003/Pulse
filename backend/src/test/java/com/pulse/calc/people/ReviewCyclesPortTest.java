package com.pulse.calc.people;

import com.pulse.calc.core.Appraisal;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
import com.pulse.testsupport.Oracle;
import com.pulse.testsupport.TestApi;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The appraisal input port {@code people.reviewCycles} (task 20.3): on the seeded 360° data the
 * appraisal's 360° part and manager part match the oracle {@code aprParts} (app.js ~987) for every person.
 */
class ReviewCyclesPortTest
{
    private static final int PORT = 18_341;

    private static final double EPS = 1e-9;

    private static TestApi api;

    private static Oracle oracle;

    private static CalcService calc;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "reviews", "training");

        calc = new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), "2026-09-28", false), FormulaRegistry.discover());

        oracle = Oracle.start();
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (oracle != null)
        {
            oracle.close();
        }

        if (api != null)
        {
            api.close();
        }
    }

    @Test
    void the360AndManagerPartsMatchTheOracleForEveryPerson() throws Exception
    {
        JsonArray people = TestApi.await(api.sql().queryList("SELECT id::text AS id, external_id AS key FROM people WHERE source = 'seed'"));

        int with360 = 0;

        for (Object o : people)
        {
            JsonObject p = (JsonObject) o;

            JsonObject expected = (JsonObject) oracle.eval("(function(){var a=aprParts(person('" + p.getString("key")
                    + "'));return {r360:a.parts[1][2],mgr:a.parts[2][2],cycle:a.cycle}})()");

            // CalcService.get guards nulls for clients; formulas read the raw value, so compare on that.
            Object port = ResultGuard.unguard(TestApi.await(calc.get("people.reviewCycles", new JsonObject().put("person", p.getString("id")))));

            JsonArray cycles = port instanceof JsonArray a ? a : new JsonArray();

            JsonObject f360 = Appraisal.feedback360(cycles);

            close(expected.getValue("r360"), f360.getValue("score"), p.getString("key") + " 360°");

            assertEquals(expected.getString("cycle"), f360.getString("cycle"), p.getString("key"));

            // The prototype takes the last cycle in its array as the latest, and its training checkpoint cycles always come
            // after the 360° cycles whatever their dates. Pulse orders by due date, so people with checkpoint cycles are
            // checked against the latest manager review by date instead.
            long plans = api.count("SELECT count(DISTINCT c.assignment_id) FROM review_cycles c JOIN review_requests r ON r.cycle_id = c.id "
                    + "WHERE c.kind = 'training' AND r.subject_id = '" + p.getString("id") + "'");

            if (plans == 0)
            {
                close(expected.getValue("mgr"), Appraisal.managerReview(cycles), p.getString("key") + " manager");
            }
            else
            {
                JsonObject latest = api.queryOne("SELECT avg(v::numeric)::float8 AS avg FROM review_requests r JOIN review_cycles c ON c.id = r.cycle_id, "
                        + "jsonb_each_text(r.scores) s(k, v) WHERE r.subject_id = '" + p.getString("id") + "' AND r.rater_group = 'manager' "
                        + "AND r.status = 'submitted' AND c.id = (SELECT c2.id FROM review_cycles c2 JOIN review_requests r2 ON r2.cycle_id = c2.id "
                        + "WHERE r2.subject_id = '" + p.getString("id") + "' AND r2.rater_group = 'manager' AND r2.status = 'submitted' "
                        + "ORDER BY c2.due_on DESC LIMIT 1)");

                close(latest.getValue("avg"), Appraisal.managerReview(cycles), p.getString("key") + " manager (latest by date)");
            }

            with360 += expected.getValue("r360") == null ? 0 : 1;
        }

        assertTrue(with360 > 0, "the seed has people with a 360° part");
    }

    @Test
    void anUnknownPersonHasNoCycles() throws Exception
    {
        assertTrue(ReviewFormulas.byCycle(new JsonArray()) == null);

        Object none = TestApi.await(calc.get("people.reviewCycles", new JsonObject().put("person", "00000000-0000-0000-0000-000000000000")));

        assertTrue(none == null || none instanceof JsonObject j && j.getBoolean("notApplicable", false), String.valueOf(none));
    }

    private static void close(Object expected, Object actual, String what)
    {
        if (expected == null)
        {
            assertNull(actual, what);

            return;
        }

        assertTrue(actual != null && Math.abs(((Number) expected).doubleValue() - ((Number) actual).doubleValue()) < EPS,
                what + ": expected " + expected + " but was " + actual);
    }
}
