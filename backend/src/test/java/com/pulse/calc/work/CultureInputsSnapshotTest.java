package com.pulse.calc.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.testsupport.TestApi;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code work.cultureInputs} after the closed sprints are frozen. A closed sprint's
 * {@code work.sprintMetrics} answers from its {@code period} snapshot, whose shape is fixed when it is
 * frozen; the culture inputs that are not in that shape ({@code median_cycle_time},
 * {@code bugs_fixed_without_reopening}) come from {@code work.sprintCultureFacts}, which is never frozen.
 * Regression: a database whose sprints were frozen before those inputs existed answered
 * {@code GET /culture/people/:id} with 500 (a null {@code cycles}).
 */
class CultureInputsSnapshotTest
{

    private static final int PORT = 18_274;

    private static final String TODAY = "2026-09-28";

    private static final List<String> FROM_FACTS = List.of("median_cycle_time", "bugs_fixed_without_reopening");

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", TODAY), "work", "pull_requests", "bugs", "effort", "progress", "okrs");
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
    void theInputsDoNotDependOnTheShapeOfFrozenSprints() throws Exception
    {
        JsonObject params = new JsonObject()
                .put("person", api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id"))
                .put("period", "q:2026-Q3");

        JsonObject before = (JsonObject) TestApi.await(calc().get(WorkCultureFormulas.CULTURE_INPUTS, params));

        for (String key : FROM_FACTS)
        {
            assertNotNull(before.getJsonObject(key), key);
        }

        assertNotNull(before.getJsonObject("median_cycle_time").getValue("raw"), "Priya has done issues with a cycle time");

        // freeze the closed sprints and quarters, as the server does every hour
        TestApi.await(calc().closeEndedPeriods());

        long frozen = api.count("SELECT count(*) FROM calc_snapshots WHERE kind = 'period' AND result ? 'work.sprintMetrics'");

        assertFalse(frozen == 0, "closed sprints are frozen");

        // sprints frozen before the culture inputs existed: their sprint metrics carry no cycles or done bugs
        api.exec("""
                UPDATE calc_snapshots
                SET result = jsonb_set(result, '{work.sprintMetrics}',
                        (SELECT jsonb_object_agg(e.key, e.value - 'cycles' - 'doneBugs')
                         FROM jsonb_each(result -> 'work.sprintMetrics') AS e))
                WHERE kind = 'period' AND result ? 'work.sprintMetrics'""");

        // a fresh service, as after a restart, reads the older snapshots
        JsonObject after = (JsonObject) TestApi.await(calc().get(WorkCultureFormulas.CULTURE_INPUTS, params));

        assertEquals(before, after);
    }

    private static CalcService calc()
    {
        return new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), TODAY, false), FormulaRegistry.discover());
    }
}
