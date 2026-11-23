package com.pulse.calc.people;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.CultureParameters;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
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
 * Spec culture/culture-values "Reproduce": for every seeded person and every value, the detail's score equals
 * the value's score in core.culture, and applying the shown formula to the shown parts gives that score.
 */
class CultureDetailTest
{
    private static final int PORT = 18_372;

    private static TestApi api;

    private static CalcService calc;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress",
                "visits", "reviews", "training", "attendance", "culture");

        calc = new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), "2026-09-28", false), FormulaRegistry.discover());
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    @Test
    void theDetailReproducesEveryScore() throws Exception
    {
        int checked = 0;

        for (Object o : TestApi.await(api.sql().queryList("SELECT id::text AS id FROM people WHERE source = 'seed'")))
        {
            String person = ((JsonObject) o).getString("id");

            JsonObject pp = new JsonObject().put("person", person).put("period", "q:2026-Q3");

            Object culture = ResultGuard.unguard(TestApi.await(calc.get("core.culture", pp)));

            if (!(culture instanceof JsonArray scores))
            {
                continue;
            }

            for (int i = 0; i < CultureParameters.VALUES.size(); i++)
            {
                String value = CultureParameters.VALUES.get(i);

                JsonObject d = (JsonObject) ResultGuard.unguard(TestApi.await(calc.get("people.cultureDetail", pp.copy().put("value", value))));

                String what = person + " " + value + ": " + d.encode();

                assertEquals(scores.getDouble(i), d.getDouble("score"), 1e-9, what);

                JsonObject p = d.getJsonObject("parts");

                double reproduced;

                if (CultureParameters.DISCIPLINE.equals(value))
                {
                    reproduced = p.getDouble("discipline");
                }
                else
                {
                    double share = p.getDouble("dataShare") / 100;

                    double data = p.getValue("dataScore") == null ? 0 : p.getDouble("dataScore");

                    reproduced = Math.min(5, p.getDouble("rating360") * (1 - share) + data * share + p.getDouble("bonus"));
                }

                assertEquals(d.getDouble("score"), Math.round(reproduced * 100) / 100.0, 1e-9, "formula: " + what);

                checked++;
            }
        }

        assertTrue(checked >= 11 * 5, "checked " + checked);
    }
}
