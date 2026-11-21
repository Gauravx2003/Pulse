package com.pulse.testsupport;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 4.2: the oracle harness loads app.js, calls its functions and compares ports with them. */
class OracleTest
{
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

    @Test
    void indexForOnePersonIsAFiniteNumber()
    {
        Object index = oracle.call("index", Oracle.js("person('ps')"));

        assertTrue(index instanceof Number, "index returned " + index);

        double value = ((Number) index).doubleValue();

        assertTrue(Double.isFinite(value));
        assertTrue(value >= 1 && value <= 5, "index " + value + " is off the 1–5 scale");
    }

    @Test
    void comparePassesForAPortThatAgreesToTheDisplayedPrecision()
    {
        // The index formula with the prototype's own helper values: a stand-in port for task 4.3.
        double culture = number(oracle.call("cultureAvg", Oracle.js("person('ps')")));
        double okr = number(oracle.call("okrP", Oracle.js("person('ps')")));
        double growth = number(oracle.call("growthP", Oracle.js("person('ps')")));

        oracle.compare("index", Oracle.args(Oracle.js("person('ps')")), in ->
        {
            JsonObject pillars = in.getJsonObject(0).getJsonObject("pillars");

            return (35 * pillars.getDouble("delivery") + 15 * pillars.getDouble("quality") + 25 * culture
                    + 15 * okr + 10 * growth) / 100;
        });
    }

    @Test
    void compareFailsWhenThePortDisagrees()
    {
        AssertionError error = assertThrows(AssertionError.class,
                () -> oracle.compare("index", Oracle.args(Oracle.js("person('ps')")), in -> 1.0));

        assertTrue(error.getMessage().contains("index"), error.getMessage());
        assertTrue(error.getMessage().contains("port 1.0"), error.getMessage());
    }

    @Test
    void compareWalksObjectsAndArrays()
    {
        // The prototype's rule weights, compared with a Map port; a missing key and a wrong value fail.
        oracle.compare("(function(){return S.rules.weights})", new JsonArray(), 0,
                in -> Map.of("delivery", 35, "quality", 15, "culture", 25, "okr", 15, "growth", 10));

        AssertionError missing = assertThrows(AssertionError.class,
                () -> oracle.compare("(function(){return S.rules.weights})", new JsonArray(), 0,
                        in -> Map.of("delivery", 35, "quality", 15)));

        assertTrue(missing.getMessage().contains("$.culture: only in the prototype"), missing.getMessage());

        oracle.compare("(function(a){return [a, a * 2]})", Oracle.args(1.25), 1, in -> List.of(1.25, 2.5));
    }

    @Test
    void portReceivesTheResolvedArguments()
    {
        JsonArray resolved = oracle.resolve(Oracle.args(Oracle.js("person('ps')"), 7));

        assertEquals("ps", resolved.getJsonObject(0).getString("id"));
        assertTrue(resolved.getJsonObject(0).containsKey("pillars"));
        assertEquals(7, resolved.getInteger(1));
    }

    @Test
    void displayRoundsLikeJavaScriptToFixed()
    {
        double[] values = {3.75, 3.65, 1.005, 2.5, -0.04, 0.05, 3.9241607142857147, 1e-7, 1234.5678};

        for (double v : values)
        {
            for (int decimals = 0; decimals <= 2; decimals++)
            {
                Object js = oracle.eval("(" + v + ").toFixed(" + decimals + ")");

                assertEquals(((String) js).replaceFirst("^-(0(\\.0*)?)$", "$1"), Oracle.display(v, decimals),
                        v + ".toFixed(" + decimals + ")");
            }
        }
    }

    @Test
    void nonFiniteResultsFail()
    {
        assertTrue(Double.isNaN(number(oracle.call("Math.sqrt", -1))));

        AssertionError fromOracle = assertThrows(AssertionError.class,
                () -> oracle.compare("Math.sqrt", Oracle.args(-1), in -> 1));

        assertTrue(fromOracle.getMessage().contains("(oracle) is not finite"), fromOracle.getMessage());

        AssertionError fromPort = assertThrows(AssertionError.class,
                () -> oracle.compare("Math.sqrt", Oracle.args(4), in -> Double.NaN));

        assertTrue(fromPort.getMessage().contains("(port) is not finite"), fromPort.getMessage());

        assertThrows(AssertionError.class, () -> Oracle.assertFinite("undefined",
                oracle.eval("(function(){return {x: undefined}})()")));
    }

    @Test
    void evalChangesStateUntilReset()
    {
        double before = number(oracle.call("index", Oracle.js("person('ps')")));

        oracle.eval("S.rules.weights = {delivery: 100, quality: 0, culture: 0, okr: 0, growth: 0}");

        double deliveryOnly = number(oracle.call("index", Oracle.js("person('ps')")));

        assertEquals(number(oracle.eval("person('ps').pillars.delivery")), deliveryOnly, 1e-9);
        assertNotEquals(before, deliveryOnly);

        oracle.reset();

        assertEquals(before, number(oracle.call("index", Oracle.js("person('ps')"))), 1e-12);
    }

    @Test
    void prototypeErrorsAreReported()
    {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> oracle.call("noSuchFunction"));

        assertTrue(error.getMessage().contains("noSuchFunction"), error.getMessage());

        // The process survives an error.
        assertTrue(oracle.call("index", Oracle.js("person('am')")) instanceof Number);
    }

    private static double number(Object value)
    {
        return ((Number) value).doubleValue();
    }
}
