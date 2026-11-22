package com.pulse.calc.work;

import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Task 14.2: {@link OkrProgress} against spec growth/okrs and against the prototype's {@code krProg},
 * {@code okrProg}, {@code okrState} and {@code okrScore} (app.js ~579-583) for every seeded objective,
 * key result and person.
 */
class OkrProgressTest
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

    // ------------------------------------------------------------------ spec scenarios

    @Test
    void fallingTargetIsHalfWay()
    {
        assertEquals(0.5, OkrProgress.keyResult(900, 400, 650), 1e-9);
    }

    @Test
    void progressIsClampedAndAFlatTargetCountsOnlyWhenReached()
    {
        assertEquals(1.0, OkrProgress.keyResult(0, 100, 130), 1e-9);
        assertEquals(0.0, OkrProgress.keyResult(0, 100, -5), 1e-9);
        assertEquals(1.0, OkrProgress.keyResult(3, 3, 3), 1e-9);
        assertEquals(0.0, OkrProgress.keyResult(3, 3, 2), 1e-9);
    }

    @Test
    void anOnTrackAverageGivesAPillarOfFour()
    {
        assertEquals(4.0, OkrProgress.pillar(List.of(0.7)), 1e-9);
        assertEquals(5.0, OkrProgress.pillar(List.of(1.0, 1.0)), 1e-9);
        assertNull(OkrProgress.pillar(List.of()), "no OKRs: the pillar falls back to the manager's rating");
    }

    @Test
    void statusBandsFollowTheSpec()
    {
        assertEquals("On track", OkrProgress.status(0.7));
        assertEquals("At risk", OkrProgress.status(0.69));
        assertEquals("At risk", OkrProgress.status(0.4));
        assertEquals("Off track", OkrProgress.status(0.39));
    }

    // ------------------------------------------------------------------ oracle

    @Test
    void everyKeyResultMatchesKrProg()
    {
        JsonArray okrs = (JsonArray) oracle.eval("OKRS.map(function(o){return o.krs.length})");

        for (int i = 0; i < okrs.size(); i++)
        {
            for (int k = 0; k < okrs.getInteger(i); k++)
            {
                oracle.compare("krProg", Oracle.args(Oracle.js("OKRS[" + i + "].krs[" + k + "]")), 3,
                        in -> keyResult(in.getJsonObject(0)));
            }
        }
    }

    @Test
    void everyObjectiveMatchesOkrProg()
    {
        int count = ((Number) oracle.eval("OKRS.length")).intValue();

        for (int i = 0; i < count; i++)
        {
            oracle.compare("okrProg", Oracle.args(Oracle.js("OKRS[" + i + "]")), 3,
                    in -> objective(in.getJsonObject(0)));
        }
    }

    @Test
    void statusMatchesOkrStateAcrossTheBands()
    {
        for (double progress : new double[] {0, 0.2, 0.39, 0.4, 0.55, 0.69, 0.7, 0.85, 1})
        {
            oracle.compare("okrState", Oracle.args(progress), 3, in ->
            {
                String status = OkrProgress.status(in.getDouble(0));

                String tone = switch (status)
                {
                    case OkrProgress.STATUS_ON_TRACK -> "ok";
                    case OkrProgress.STATUS_AT_RISK -> "accent";
                    default -> "danger";
                };

                return new JsonArray().add(tone).add(status);
            });
        }
    }

    @Test
    void everyPersonsPillarMatchesOkrScore()
    {
        JsonArray people = (JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})");

        for (Object id : people)
        {
            JsonArray objectives = (JsonArray) oracle.eval(
                    "OKRS.filter(function(o){return o.owner==='" + id + "'&&o.q==='Q3 2026'})");

            oracle.compare("okrScore", Oracle.args(id), 1, in ->
            {
                List<Double> progress = new ArrayList<>();

                for (Object o : objectives)
                {
                    progress.add(objective((JsonObject) o));
                }

                return OkrProgress.pillar(progress);
            });
        }
    }

    // ------------------------------------------------------------------ prototype shape to the port

    private static double keyResult(JsonObject kr)
    {
        return OkrProgress.keyResult(kr.getDouble("s"), kr.getDouble("g"), kr.getDouble("c"));
    }

    private static double objective(JsonObject okr)
    {
        List<Double> progress = new ArrayList<>();

        for (Object kr : okr.getJsonArray("krs"))
        {
            progress.add(keyResult((JsonObject) kr));
        }

        return OkrProgress.objective(progress);
    }
}
