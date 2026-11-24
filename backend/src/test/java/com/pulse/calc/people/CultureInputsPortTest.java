package com.pulse.calc.people;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track 3's culture inputs (docs/calc.md §5) on the seed match the oracle {@code valData} (app.js ~2202)
 * raw values for every seeded person in Q3 2026: office presence, customer visit rating, training rating,
 * kudos received and incidents handled. Discipline overrides read as none on the seed.
 */
class CultureInputsPortTest
{
    private static final int PORT = 18_371;

    /** Prototype parameter name to the input key. */
    private static final Map<String, String> PARAMS = Map.of(
            "Office and on-site presence", "office_presence",
            "Customer visit rating", "customer_visit_rating",
            "Training rating", "training_rating",
            "Kudos received", "kudos_received",
            "Incidents and escalations handled", "incidents_handled");

    private static TestApi api;

    private static Oracle oracle;

    private static CalcService calc;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress",
                "visits", "reviews", "training", "attendance", "culture");

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
    void theInputsMatchTheOracleForEveryPerson() throws Exception
    {
        JsonObject expected = (JsonObject) oracle.eval("(function(){var names=" + new JsonArray(PARAMS.keySet().stream().toList()).encode()
                + ",out={};PEOPLE.forEach(function(p){out[p.id]={};VALUES.forEach(function(v){valData(p,v).params.forEach(function(x){"
                + "if(names.indexOf(x.name)>=0)out[p.id][x.name]=x.raw===undefined?null:x.raw})})});return out})()");

        int compared = 0;

        for (String key : expected.fieldNames())
        {
            JsonObject row = api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = '" + key + "'");

            if (row == null)
            {
                continue;
            }

            JsonObject inputs = (JsonObject) ResultGuard.unguard(TestApi.await(calc.get("people.cultureInputs",
                    new JsonObject().put("person", row.getString("id")).put("period", "q:2026-Q3"))));

            for (Map.Entry<String, String> p : PARAMS.entrySet())
            {
                Object want = expected.getJsonObject(key).getValue(p.getKey());

                JsonObject got = inputs.getJsonObject(p.getValue());

                String what = key + " " + p.getKey() + ": expected " + want + " but was " + got;

                if (want == null)
                {
                    assertNull(got == null ? null : got.getValue("raw"), what);
                }
                else
                {
                    assertTrue(got != null && Math.abs(((Number) want).doubleValue() - ((Number) got.getValue("raw")).doubleValue()) < 1e-9, what);
                }

                compared++;
            }
        }

        assertTrue(compared >= 5 * 6, "compared " + compared);
    }

    @Test
    void noDisciplineOverrideOnTheSeed() throws Exception
    {
        String priya = api.queryOne("SELECT p.id::text AS id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = 'priya@pulse.demo'")
                .getString("id");

        Object override = ResultGuard.unguard(TestApi.await(calc.get("people.disciplineOverride", new JsonObject().put("person", priya))));

        assertNull(override);

        api.exec("INSERT INTO discipline_overrides (person_id, score, reason, set_by) SELECT '" + priya + "', 3.5, 'Covered on-call for two weeks.', "
                + "id FROM users WHERE email = 'rahul@pulse.demo'");

        calc.invalidate(CultureInputsFormulas.OVERRIDES);

        JsonObject set = (JsonObject) ResultGuard.unguard(TestApi.await(calc.get("people.disciplineOverride", new JsonObject().put("person", priya))));

        assertEquals(3.5, set.getDouble("score"));

        assertEquals("Covered on-call for two weeks.", set.getString("reason"));
    }
}
