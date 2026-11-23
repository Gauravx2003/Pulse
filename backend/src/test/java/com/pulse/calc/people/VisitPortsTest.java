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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track 2's visit ports (task 18.1): {@code people.visitDays} matches the prototype's visits with the
 * hours Track 2's capacity reads, and {@code people.visitPointsByRef} matches the oracle {@code itemPts}
 * of every visit extra-mile item (app.js ~429) on the seeded data.
 */
class VisitPortsTest
{
    private static final int PORT = 18_321;

    private static TestApi api;

    private static Oracle oracle;

    private static CalcService calc;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "visits");

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

    private static Object port(String name, String person) throws Exception
    {
        return ResultGuard.unguard(TestApi.await(calc.get(name, new JsonObject().put("person", person))));
    }

    @Test
    void visitDaysMatchThePrototype() throws Exception
    {
        JsonArray people = TestApi.await(api.sql().queryList("SELECT id::text AS id, external_id AS key FROM people WHERE source = 'seed'"));

        int visits = 0;

        for (Object o : people)
        {
            JsonObject p = (JsonObject) o;

            JsonArray expected = (JsonArray) oracle.eval("VISITS.filter(function(v){return v.pid==='" + p.getString("key") + "'})"
                    + ".sort(function(a,b){return a.from<b.from?-1:a.from>b.from?1:0}).map(function(v){return {from:v.from,to:v.to,"
                    + "hours:v.report?Math.round(v.report.onsite/vDays(v).length*2)/2:8}})");

            JsonArray actual = (JsonArray) port("people.visitDays", p.getString("id"));

            assertEquals(expected.size(), actual.size(), p.getString("key"));

            for (int i = 0; i < expected.size(); i++)
            {
                JsonObject e = expected.getJsonObject(i);

                JsonObject a = actual.getJsonObject(i);

                assertEquals(e.getString("from"), a.getString("from"), p.getString("key"));

                assertEquals(e.getString("to"), a.getString("to"), p.getString("key"));

                assertEquals(e.getNumber("hours").doubleValue(), a.getNumber("hours").doubleValue(), 1e-9, p.getString("key") + " " + e.getString("from"));
            }

            visits += expected.size();
        }

        assertTrue(visits > 0);
    }

    @Test
    void visitPointsByRefMatchTheOracleItemPoints() throws Exception
    {
        JsonObject expected = (JsonObject) oracle.eval("(function(){var o={};EXTRA.forEach(function(it){if(it.kind==='visit')"
                + "o[it.ref]={pid:it.pid,pts:itemPts(it)}});return o})()");

        assertTrue(!expected.isEmpty());

        for (String ref : expected.fieldNames())
        {
            JsonObject item = expected.getJsonObject(ref);

            String person = api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = '" + item.getString("pid") + "'")
                    .getString("id");

            JsonObject byRef = (JsonObject) port("people.visitPointsByRef", person);

            assertTrue(byRef.containsKey(ref), ref + " in " + byRef.encode());

            assertEquals(item.getNumber("pts").doubleValue(), byRef.getNumber(ref).doubleValue(), 1e-9, ref);
        }
    }
}
