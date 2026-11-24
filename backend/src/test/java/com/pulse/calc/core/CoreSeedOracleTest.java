package com.pulse.calc.core;

import com.pulse.testsupport.Oracle;
import com.pulse.testsupport.TestApi;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tasks 4.3 and 4.4 end to end: {@link CalcService} on the seeded tables, with the input ports of
 * Tracks 2 and 3 in place, against the oracle for every seeded person in the live quarter.
 *
 * <ul>
 *   <li>{@code core.culture} equals the prototype's later {@code cult} (app.js ~2251) exactly, all 11 values;</li>
 *   <li>{@code core.cultureData} matches {@code valData} (app.js ~2202) value by value and parameter by
 *       parameter, and the 12 reported parameters come from {@code culture_signals} with their source
 *       label and {@code is_simulated = true};</li>
 *   <li>{@code core.index} matches {@code index} (app.js ~190) under the default weights and two altered
 *       weight sets, one of which does not total 100.</li>
 * </ul>
 *
 * <p>{@link CultureScoreTest} and {@link PerformanceIndexTest} check the pure ports with the prototype's
 * own inputs; this test checks that the seeded tables and ports feed them the same inputs.
 */
class CoreSeedOracleTest
{
    private static final int PORT = 18_376;

    private static final String QUARTER = "q:2026-Q3";

    private static final JsonObject DELIVERY_HEAVY = weights(50, 10, 20, 10, 10);

    private static final JsonObject TOTAL_110 = weights(40, 20, 25, 15, 10);

    private static TestApi api;

    private static Oracle oracle;

    private static CalcService calc;

    /** Oracle person id to database person id, for every seeded person. */
    private static final Map<String, String> PEOPLE = new LinkedHashMap<>();

    private static String defaultWeights;

    /** When set, {@link #assertNumber} collects mismatches here instead of failing on the first. */
    private static List<String> DIFFS;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort",
                "progress", "okrs", "visits", "reviews", "training", "attendance", "culture");

        calc = new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), "2026-09-28", false), FormulaRegistry.discover());

        oracle = Oracle.start();

        for (Object o : (JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})"))
        {
            String key = (String) o;

            JsonObject row = api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = '" + key + "'");

            assertNotNull(row, "seeded person " + key);

            PEOPLE.put(key, row.getString("id"));
        }

        assertEquals(8, PEOPLE.size());

        defaultWeights = api.queryOne("SELECT (rule_set -> 'weights')::text AS v FROM rule_versions ORDER BY version DESC LIMIT 1")
                .getString("v");
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

    @AfterEach
    void reset() throws Exception
    {
        oracle.reset();

        setWeights(new JsonObject(defaultWeights));
    }

    // ------------------------------------------------------------------ culture (task 4.4)

    @Test
    void cultureEqualsThePrototypeExactlyForEveryPerson() throws Exception
    {
        for (Map.Entry<String, String> p : PEOPLE.entrySet())
        {
            JsonArray expected = (JsonArray) oracle.call("cult", Oracle.js("person('" + p.getKey() + "')"));

            JsonArray port = (JsonArray) get("core.culture", p.getValue());

            assertEquals(11, port.size(), p.getKey());

            for (int i = 0; i < 11; i++)
            {
                assertEquals(((Number) expected.getValue(i)).doubleValue(), port.getDouble(i), 0.0,
                        p.getKey() + " " + CultureParameters.VALUES.get(i));
            }
        }
    }

    @Test
    void cultureDataMatchesValDataForEveryPersonValueAndParameter() throws Exception
    {
        DIFFS = new ArrayList<>();

        try
        {
            cultureDataDiffs();
        }
        finally
        {
            List<String> diffs = DIFFS;

            DIFFS = null;

            assertTrue(diffs.isEmpty(), diffs.size() + " differences:\n" + String.join("\n", diffs));
        }
    }

    private static void cultureDataDiffs() throws Exception
    {
        for (Map.Entry<String, String> p : PEOPLE.entrySet())
        {
            JsonArray port = (JsonArray) get("core.cultureData", p.getValue());

            for (int i = 0; i < 11; i++)
            {
                String value = CultureParameters.VALUES.get(i);

                String what = p.getKey() + " " + value;

                JsonObject expected = (JsonObject) oracle.eval("(function(r){return {score:r.score,w:r.w,params:r.params.map(function(x){"
                        + "return {name:x.name,raw:x.raw===undefined?null:x.raw,score:x.score===undefined?null:x.score,w:x.w,disp:x.disp,na:!!x.na}})}})"
                        + "(valData(person('" + p.getKey() + "'),'" + value.replace("'", "\\'") + "'))");

                JsonObject got = port.getJsonObject(i);

                assertNumber(expected.getValue("score"), got.getValue("score"), what + " score");

                JsonArray want = expected.getJsonArray("params");

                JsonArray have = got.getJsonArray("parameters");

                if (CultureParameters.MORAL_AND_ETHICS.equals(value))
                {
                    assertTrue(have.isEmpty(), what);

                    continue;
                }

                assertEquals(want.size(), have.size(), what + " parameters");

                for (int k = 0; k < want.size(); k++)
                {
                    JsonObject w = want.getJsonObject(k);

                    JsonObject h = have.getJsonObject(k);

                    String param = what + " / " + w.getString("name");

                    assertEquals(w.getString("name"), h.getString("name"), param);

                    differ(w.getBoolean("na"), h.getBoolean("notApplicable"), param + " no opportunity");
                    differ(w.getString("disp"), h.getString("display"), param + " display");

                    assertNumber(w.getValue("raw"), h.getValue("raw"), param + " raw");
                    assertNumber(w.getValue("score"), h.getValue("score"), param + " score");
                    assertNumber(w.getValue("w"), h.getValue("weight"), param + " weight");
                }
            }
        }
    }

    @Test
    void theTwelveReportedParametersComeFromCultureSignals() throws Exception
    {
        for (Map.Entry<String, String> p : PEOPLE.entrySet())
        {
            JsonArray port = (JsonArray) get("core.cultureData", p.getValue());

            int seen = 0;

            for (Object v : port)
            {
                for (Object o : ((JsonObject) v).getJsonArray("parameters"))
                {
                    JsonObject x = (JsonObject) o;

                    if (!CultureParameters.signalKeys().contains(x.getString("key")))
                    {
                        assertFalse(x.getBoolean("isSimulated"), p.getKey() + " " + x.getString("key"));

                        continue;
                    }

                    seen++;

                    JsonObject row = api.queryOne("SELECT source_label AS label, raw_value::float8 AS raw FROM culture_signals "
                            + "WHERE person_id = '" + p.getValue() + "' AND quarter = '2026-Q3' AND param_key = '" + x.getString("key") + "'");

                    assertNotNull(row, p.getKey() + " " + x.getString("key"));
                    assertTrue(x.getBoolean("isSimulated"), p.getKey() + " " + x.getString("key"));
                    assertEquals(row.getString("label"), x.getString("sourceLabel"), p.getKey() + " " + x.getString("key"));
                    assertNumber(row.getValue("raw"), x.getValue("raw"), p.getKey() + " " + x.getString("key") + " raw");
                }
            }

            assertEquals(12, seen, p.getKey());
        }
    }

    // ------------------------------------------------------------------ index (task 4.3)

    @Test
    void pillarsMatchThePrototypeForEveryPerson() throws Exception
    {
        for (Map.Entry<String, String> p : PEOPLE.entrySet())
        {
            JsonObject expected = (JsonObject) oracle.eval("(function(p){return {delivery:p.pillars.delivery,quality:p.pillars.quality,"
                    + "okr:okrP(p),growth:growthP(p),culture:cultureAvg(p)}})(person('" + p.getKey() + "'))");

            JsonObject port = (JsonObject) get("core.pillars", p.getValue());

            assertNotNull(port, p.getKey());

            for (String pillar : expected.fieldNames())
            {
                assertEquals(expected.getDouble(pillar), port.getDouble(pillar), 1e-9, p.getKey() + " " + pillar);
            }
        }
    }

    @Test
    void indexMatchesThePrototypeUnderDefaultWeights() throws Exception
    {
        compareIndex();
    }

    @Test
    void indexMatchesThePrototypeUnderADeliveryHeavyWeightSet() throws Exception
    {
        setWeights(DELIVERY_HEAVY);

        oracle.eval("S.rules.weights = " + DELIVERY_HEAVY.encode());

        compareIndex();
    }

    @Test
    void indexMatchesThePrototypeWhenWeightsTotal110() throws Exception
    {
        setWeights(TOTAL_110);

        oracle.eval("S.rules.weights = " + TOTAL_110.encode());

        compareIndex();
    }

    private static void compareIndex() throws Exception
    {
        for (Map.Entry<String, String> p : PEOPLE.entrySet())
        {
            double expected = ((Number) oracle.call("index", Oracle.js("person('" + p.getKey() + "')"))).doubleValue();

            Object port = get("core.index", p.getValue());

            assertNotNull(port, p.getKey());
            assertEquals(expected, ((Number) port).doubleValue(), 1e-9, p.getKey());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Object get(String formula, String person) throws Exception
    {
        return ResultGuard.unguard(TestApi.await(calc.get(formula, new JsonObject().put("person", person).put("period", QUARTER))));
    }

    /** A new rule version with these index weights (rule versions are insert-only). */
    private static void setWeights(JsonObject weights) throws Exception
    {
        api.exec("INSERT INTO rule_versions (version, rule_set, reason) SELECT version + 1, jsonb_set(rule_set, '{weights}', '"
                + weights.encode() + "'::jsonb), 'test' FROM rule_versions ORDER BY version DESC LIMIT 1");

        calc.invalidate(CalcService.RULES);
    }

    private static void differ(Object expected, Object actual, String what)
    {
        if (expected == null ? actual != null : !expected.equals(actual))
        {
            DIFFS.add(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void assertNumber(Object expected, Object actual, String what)
    {
        if (DIFFS != null)
        {
            boolean same = expected == null || actual == null ? expected == actual
                    : Math.abs(((Number) expected).doubleValue() - ((Number) actual).doubleValue()) < 1e-9;

            if (!same)
            {
                DIFFS.add(what + ": expected " + expected + " but was " + actual);
            }

            return;
        }

        if (expected == null || actual == null)
        {
            assertEquals(expected, actual, what);

            return;
        }

        assertEquals(((Number) expected).doubleValue(), ((Number) actual).doubleValue(), 1e-9, what);
    }

    private static JsonObject weights(int d, int q, int c, int o, int g)
    {
        return new JsonObject().put("delivery", d).put("quality", q).put("culture", c).put("okr", o).put("growth", g);
    }
}
