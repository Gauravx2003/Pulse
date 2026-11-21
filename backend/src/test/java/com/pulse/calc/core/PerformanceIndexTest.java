package com.pulse.calc.core;

import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 4.3: the performance index against spec platform/calculation-engine and against the oracle
 * {@code index} (app.js line ~190) for every seeded person, under the default weights and two altered
 * weight sets (one that does not total 100).
 *
 * <p>The culture scores, the OKR score and the training rating are taken from the prototype here, so
 * this test checks the index and its helpers only; {@link CultureScoreTest} checks the culture scores
 * themselves, and the OKR pillar (calc/work) and training rating (task 4.7) have their own tests.
 */
class PerformanceIndexTest
{
    private static final JsonObject DEFAULT = weights(35, 15, 25, 15, 10);

    private static final JsonObject DELIVERY_HEAVY = weights(50, 10, 20, 10, 10);

    private static final JsonObject TOTAL_110 = weights(40, 20, 25, 15, 10);

    private static Oracle oracle;

    @BeforeAll
    static void start() throws Exception
    {
        oracle = Oracle.start();
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

    // ------------------------------------------------------------------ spec scenarios

    @Test
    void indexUnderDefaultWeights()
    {
        JsonObject pillars = pillars(4, 3, 4, 3, 4);

        // (35×4 + 15×3 + 25×4 + 15×3 + 10×4) ÷ 100 = 3.7
        assertEquals(3.7, PerformanceIndex.index(pillars, DEFAULT), 1e-12);
    }

    @Test
    void weightsNotTotalling100AreNormalised()
    {
        JsonObject pillars = pillars(4, 3, 4, 3, 4);

        double expected = (40 * 4 + 20 * 3 + 25 * 4 + 15 * 3 + 10 * 4) / 110.0;

        assertEquals(expected, PerformanceIndex.index(pillars, TOTAL_110), 1e-12);
        assertEquals(4.0, PerformanceIndex.index(pillars(4, 4, 4, 4, 4), TOTAL_110), 1e-12);
        assertEquals(0.0, PerformanceIndex.index(pillars(4, 4, 4, 4, 4), weights(0, 0, 0, 0, 0)), 1e-12);
    }

    @Test
    void growthIsAveragedWithAFinalTrainingRating()
    {
        assertEquals(3.9, PerformanceIndex.growth(3.8, new JsonObject().put("final", true).put("score", 4.0)), 1e-12);
        assertEquals(3.8, PerformanceIndex.growth(3.8, new JsonObject().put("final", false).put("score", 4.0)), 1e-12);
        assertEquals(3.8, PerformanceIndex.growth(3.8, new JsonObject().put("final", true).putNull("score")), 1e-12);
        assertEquals(3.8, PerformanceIndex.growth(3.8, null), 1e-12);
    }

    @Test
    void seededDeliveryAndQualityCalculatedCultureAndOkr()
    {
        JsonObject seeded = new JsonObject().put("delivery", 4.1).put("quality", 3.8).put("okr", 3.7).put("growth", 4);

        JsonArray culture = new JsonArray().add(4).add(4).add(4).add(4).add(4).add(4).add(4).add(4).add(4).add(4).add(2);

        JsonArray vw = new JsonArray().add(15).add(10).add(10).add(10).add(5).add(5).add(10).add(10).add(5).add(10).add(10);

        JsonObject p = PerformanceIndex.pillars(seeded, culture, vw, 3.2, null);

        assertEquals(4.1, p.getDouble("delivery"));
        assertEquals(3.8, p.getDouble("quality"));
        assertEquals((4 * 90 + 2 * 10) / 100.0, p.getDouble("culture"), 1e-12);
        assertEquals(3.2, p.getDouble("okr"));
        assertEquals(3.7, PerformanceIndex.pillars(seeded, culture, vw, null, null).getDouble("okr"));
        assertEquals(4.0, p.getDouble("growth"));
    }

    // ------------------------------------------------------------------ oracle

    @Test
    void indexMatchesTheOracleForEverySeededPersonUnderDefaultWeights()
    {
        compareEveryPerson(DEFAULT);
    }

    @Test
    void indexMatchesTheOracleUnderADeliveryHeavyWeightSet()
    {
        oracle.eval("S.rules.weights = " + DELIVERY_HEAVY.encode());

        compareEveryPerson(DELIVERY_HEAVY);
    }

    @Test
    void indexMatchesTheOracleWhenWeightsTotal110()
    {
        oracle.eval("S.rules.weights = " + TOTAL_110.encode());

        compareEveryPerson(TOTAL_110);
    }

    private static void compareEveryPerson(JsonObject weights)
    {
        JsonArray ids = (JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})");

        JsonArray vw = (JsonArray) oracle.eval("S.rules.vw");

        assertEquals(8, ids.size());

        for (Object o : ids)
        {
            String id = (String) o;

            String person = "person('" + id + "')";

            JsonArray culture = (JsonArray) oracle.call("cult", Oracle.js(person));

            Object okr = oracle.call("okrScore", id);

            JsonObject training = (JsonObject) oracle.eval("(function(t){return {final: t.final, score: t.score}})(trPerson('" + id + "'))");

            double[] port = new double[1];

            oracle.compare("index", Oracle.args(Oracle.js(person)), 1, in ->
            {
                JsonObject p = PerformanceIndex.pillars(in.getJsonObject(0).getJsonObject("pillars"), culture, vw,
                        okr == null ? null : ((Number) okr).doubleValue(), training);

                port[0] = PerformanceIndex.index(p, weights);

                return port[0];
            });

            // Beyond the displayed precision: the same arithmetic gives the same double.
            assertEquals(((Number) oracle.call("index", Oracle.js(person))).doubleValue(), port[0], 1e-12, id);
        }
    }

    private static JsonObject weights(int d, int q, int c, int o, int g)
    {
        return new JsonObject().put("delivery", d).put("quality", q).put("culture", c).put("okr", o).put("growth", g);
    }

    private static JsonObject pillars(double d, double q, double c, double o, double g)
    {
        return new JsonObject().put("delivery", d).put("quality", q).put("culture", c).put("okr", o).put("growth", g);
    }
}
