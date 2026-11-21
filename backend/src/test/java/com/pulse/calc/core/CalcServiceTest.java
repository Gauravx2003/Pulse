package com.pulse.calc.core;

import com.pulse.calc.work.ProbeFormulas;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4.10: CalcService on the migrated demo seed, as the application role: guarded results,
 * event-based invalidation, period close with snapshots and rule versions, the seeded index history
 * feeding the appraisal performance part, and the p95 of a period switch.
 */
class CalcServiceTest
{
    private static final String TODAY = "2026-09-28";

    private static TestDb db;

    private static SqlExecutor sql;

    private static SqlExecutor owner;

    private static FormulaRegistry registry;

    private static String ps;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(CalcServiceTest.class.getClassLoader(), List.of(ScriptCatalog.PLATFORM));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));
        await(MigrationRunner.seed(db.ownerPool(), catalog, true));

        sql = new SqlExecutor(db.pool(Db.DEFAULT_APP_ROLE));
        owner = new SqlExecutor(db.ownerPool());

        registry = FormulaRegistry.discover();

        ps = await(sql.queryOne("SELECT id FROM people WHERE external_id = 'ps'")).getString("id");
    }

    @AfterAll
    static void tearDown() throws Exception
    {
        if (db != null)
        {
            db.close();
        }
    }

    // ------------------------------------------------------------------ reads

    @Test
    void resultsAreGuardedAndBadRequestsRefused() throws Exception
    {
        CalcService calc = calc(TODAY);

        // No training port yet: the overall training rating is not applicable, never null.
        assertTrue(ResultGuard.isNotApplicable(await(calc.get("core.training", person(ps)))));

        JsonObject q3 = (JsonObject) await(calc.get("core.period", new JsonObject().put("period", "q:Q3")));

        assertEquals("2026-07-01", q3.getString("from"));
        assertTrue(q3.getBoolean("live"));

        Object index = await(calc.get("core.index", person(ps).put("period", "q:Q3")));

        assertInstanceOf(Number.class, index);

        double i = ((Number) index).doubleValue();

        assertTrue(i >= 1 && i <= 5, String.valueOf(i));

        // q:Q3 and q:2026-Q3 are the same period.
        assertEquals(index, await(calc.get("core.index", person(ps).put("period", "q:2026-Q3"))));

        assertFailsWith(IllegalArgumentException.class, calc.get("core.nothing", new JsonObject()));
        assertFailsWith(IllegalArgumentException.class, calc.get("core.period", new JsonObject().put("period", "x:1")));
        assertFailsWith(IllegalArgumentException.class, calc.get("core.index", person(ps).put("period", "s:S43")));
    }

    @Test
    void appraisalPerformanceReadsTheSeededQuarters() throws Exception
    {
        CalcService calc = calc(TODAY);

        JsonArray history = (JsonArray) await(calc.get("core.indexHistory", person(ps)));

        // ps: trend [3.4, 3.6, 3.7, 3.9]; the first three are the seeded 2025-Q4, 2026-Q1, 2026-Q2.
        assertEquals(List.of(3.4, 3.6, 3.7), history.stream().map(v -> ((Number) v).doubleValue()).toList());

        double live = ((Number) await(calc.get("core.index", person(ps).put("period", "q:Q3")))).doubleValue();

        double perf = ((Number) await(calc.get("core.appraisalPerformance", person(ps)))).doubleValue();

        assertEquals((3.4 + 3.6 + 3.7 + live) / 4, perf, 1e-12);

        JsonObject appraisal = (JsonObject) await(calc.get("core.appraisal", person(ps)));

        assertEquals(perf, appraisal.getJsonArray("parts").getJsonObject(0).getDouble("value"), 1e-12);
        assertTrue(appraisal.getJsonObject("recommendation").getBoolean("eligible"));
    }

    // ------------------------------------------------------------------ invalidation

    @Test
    void anInvalidationEventChangesTheNextRead() throws Exception
    {
        CalcService calc = calc(TODAY).listen(db.vertx());

        try
        {
            JsonObject params = person(ps).put("period", "q:Q3");

            double before = ((JsonObject) await(calc.get("core.pillars", params))).getDouble("delivery");
            Object indexBefore = await(calc.get("core.index", params));

            await(owner.execute("UPDATE pillars SET delivery = $1 WHERE person_id = $2 AND quarter = '2026-Q3'",
                    Tuple.of(before == 5 ? 1 : 5, UUID.fromString(ps))));

            // Cached until the data changes are announced.
            assertEquals(before, ((JsonObject) await(calc.get("core.pillars", params))).getDouble("delivery"));

            CalcService.publish(db.vertx(), CalcService.PERFORMANCE);

            double after = before;

            for (int i = 0; i < 100 && after == before; i++)
            {
                Thread.sleep(20);

                after = ((JsonObject) await(calc.get("core.pillars", params))).getDouble("delivery");
            }

            assertNotEquals(before, after);

            // The index read the pillars, so it was dropped too.
            assertNotEquals(indexBefore, await(calc.get("core.index", params)));

            await(owner.execute("UPDATE pillars SET delivery = $1 WHERE person_id = $2 AND quarter = '2026-Q3'",
                    Tuple.of(before, UUID.fromString(ps))));
        }
        finally
        {
            await(calc.close());
        }
    }

    @Test
    void aCsvImportCommitDropsWhatReadTheImportedTable() throws Exception
    {
        CalcService calc = calc(TODAY).listen(db.vertx());

        try
        {
            JsonObject june = new JsonObject().put("period", "m:2026-06");

            int before = ((Number) await(calc.get("work.workingDays", june))).intValue();

            await(owner.execute("INSERT INTO holidays (holiday_on, name, source) VALUES ('2026-06-10', 'Test holiday', 'test')",
                    Tuple.tuple()));

            assertEquals(before, ((Number) await(calc.get("work.workingDays", june))).intValue());

            // What ImportService publishes after a Holiday calendar import commits.
            db.vertx().eventBus().publish(CalcService.IMPORT_COMMITTED_ADDRESS,
                    new JsonObject().put("type", "holidays").put("importId", UUID.randomUUID().toString()));

            int after = before;

            for (int i = 0; i < 100 && after == before; i++)
            {
                Thread.sleep(20);

                after = ((Number) await(calc.get("work.workingDays", june))).intValue();
            }

            assertEquals(before - 1, after);

            await(owner.execute("DELETE FROM holidays WHERE source = 'test'", Tuple.tuple()));
        }
        finally
        {
            await(calc.close());
        }
    }

    // ------------------------------------------------------------------ period close

    @Test
    void closingAPeriodStoresItOnceAndALaterRuleEditLeavesItUnchanged() throws Exception
    {
        // On 5 Oct, Q3 and every sprint up to S42 have ended.
        CalcService october = calc("2026-10-05");

        JsonArray frozen = await(october.closeEndedPeriods());

        assertTrue(frozen.contains("q:2026-Q3"), frozen.encode());
        assertTrue(frozen.contains("s:S42"), frozen.encode());
        assertTrue(!frozen.contains("s:S43") && !frozen.contains("q:2026-Q2"), frozen.encode());

        assertEquals(new JsonArray(), await(october.closeEndedPeriods()));

        JsonObject snapshot = await(SnapshotStore.find(sql, SnapshotStore.PERIOD, "q:2026-Q3"));

        int version = snapshot.getInteger("ruleVersion");

        Object frozenIndex = await(october.get("core.index", person(ps).put("period", "q:2026-Q3")));

        assertEquals(snapshot.getJsonObject("result").getJsonObject("core.index").getValue(ps), frozenIndex);

        Object frozenSprint = await(october.get("work.probeSprint", new JsonObject().put("period", "s:S42")));

        // An Admin changes the index weights: a new rule version.
        editWeights();

        october.invalidate(CalcService.RULES, ProbeFormulas.PROBE_TOPIC);

        assertEquals(frozenIndex, await(october.get("core.index", person(ps).put("period", "q:2026-Q3"))));
        assertEquals(frozenSprint, await(october.get("work.probeSprint", new JsonObject().put("period", "s:S42"))));
        assertEquals(version, await(SnapshotStore.find(sql, SnapshotStore.PERIOD, "q:2026-Q3")).getInteger("ruleVersion"));

        // While Q3 was live (28 Sep) the same read follows the new weights.
        Object live = await(calc(TODAY).get("core.index", person(ps).put("period", "q:Q3")));

        assertNotEquals(frozenIndex, live);

        // Only an Admin recalculation with a reason replaces it: the new weights, the new rule version.
        JsonObject admin = new JsonObject().put("id", UUID.randomUUID().toString()).put("role", "Admin");

        assertFailsWith(IllegalArgumentException.class, october.recalculate("q:2026-Q3", admin, "short"));
        assertFailsWith(IllegalArgumentException.class, october.recalculate("s:S43", admin, "Sprint is still running"));

        JsonObject row = await(october.recalculate("q:2026-Q3", admin, "Index weights corrected after close"));

        assertEquals(version + 1, row.getInteger("ruleVersion"));
        assertEquals(live, await(october.get("core.index", person(ps).put("period", "q:2026-Q3"))));
        assertEquals(2, await(SnapshotStore.history(sql, SnapshotStore.PERIOD, "q:2026-Q3")).size());

        // A recalculation recomputes the whole chain, not only the top formula: corrected Q3 data shows.
        JsonObject q3 = person(ps).put("period", "q:2026-Q3");

        double delivery = ((JsonObject) await(october.get("core.pillars", q3))).getDouble("delivery");

        Object indexBefore = await(october.get("core.index", q3));

        await(owner.execute("UPDATE pillars SET delivery = $1 WHERE person_id = $2 AND quarter = '2026-Q3'",
                Tuple.of(delivery == 5 ? 1 : 5, UUID.fromString(ps))));

        october.invalidate(CalcService.PERFORMANCE);

        assertEquals(delivery, ((JsonObject) await(october.get("core.pillars", q3))).getDouble("delivery"));

        await(october.recalculate("q:2026-Q3", admin, "Q3 delivery data corrected"));

        assertNotEquals(delivery, ((JsonObject) await(october.get("core.pillars", q3))).getDouble("delivery"));

        // core.index reads core.pillars of the same closed quarter: it must not take the old snapshot.
        assertNotEquals(indexBefore, await(october.get("core.index", q3)));

        await(owner.execute("UPDATE pillars SET delivery = $1 WHERE person_id = $2 AND quarter = '2026-Q3'",
                Tuple.of(delivery, UUID.fromString(ps))));
    }

    // ------------------------------------------------------------------ performance

    @Test
    void aPeriodSwitchStaysUnder500MsAtP95() throws Exception
    {
        CalcService calc = calc(TODAY);

        JsonArray people = (JsonArray) await(calc.get("core.people", new JsonObject()));

        List<String> keys = new ArrayList<>();

        for (Object s : (JsonArray) await(calcCalendarSprints(calc)))
        {
            keys.add(((JsonObject) s).getString("key"));
        }

        for (String m : new String[] {"m:2026-04", "m:2026-05", "m:2026-06", "m:2026-07", "m:2026-08", "m:2026-09"})
        {
            keys.add(m);
        }

        keys.addAll(List.of("q:Q2", "q:Q3", "all"));

        List<Long> times = new ArrayList<>();

        for (int round = 0; round < 2; round++)
        {
            for (String key : keys)
            {
                long t0 = System.nanoTime();

                await(periodView(calc, key, people));

                times.add((System.nanoTime() - t0) / 1_000_000);
            }
        }

        Collections.sort(times);

        long p95 = times.get((int) Math.ceil(times.size() * 0.95) - 1);

        assertTrue(p95 < 500, "p95 " + p95 + " ms over " + times.size() + " switches: " + times);
    }

    /** What a screen loads on a period switch: the period, its working days and, per person, the quarterly numbers. */
    private static Future<Void> periodView(CalcService calc, String key, JsonArray people)
    {
        JsonObject period = new JsonObject().put("period", key);

        List<Future<?>> all = new ArrayList<>();

        all.add(calc.get("core.period", period));
        all.add(calc.get("work.workingDays", period));

        if (key.startsWith("q:"))
        {
            for (Object p : people)
            {
                String id = ((JsonObject) p).getString("id");

                all.add(calc.get("core.index", period.copy().put("person", id)));
                all.add(calc.get("core.culture", period.copy().put("person", id)));
            }
        }

        return Future.all(all).mapEmpty();
    }

    private static Future<JsonArray> calcCalendarSprints(CalcService calc)
    {
        return sql.queryList("SELECT 's:' || key AS key FROM sprints ORDER BY starts_on");
    }

    // ------------------------------------------------------------------ helpers

    private static CalcService calc(String today)
    {
        return new CalcService(sql, PulseClock.of(Clock.systemUTC(), today, false), registry);
    }

    private static JsonObject person(String id)
    {
        return new JsonObject().put("person", id);
    }

    /** A rules save (task 5.2) that moves index weight from Delivery to Culture. */
    private static void editWeights() throws Exception
    {
        await(sql.inTransaction(tx -> SnapshotStore.currentRules(tx).compose(cur ->
        {
            JsonObject set = cur.getJsonObject("rules").copy();

            set.put("weights", new JsonObject().put("delivery", 5).put("quality", 15).put("culture", 55).put("okr", 15)
                    .put("growth", 10));

            return tx.execute("INSERT INTO rule_versions (version, rule_set, reason) VALUES ($1, $2, 'test')",
                    Tuple.of(cur.getInteger("version") + 1, set));
        })));
    }

    private static void assertFailsWith(Class<? extends Throwable> type, Future<?> future)
    {
        ExecutionException e = assertThrows(ExecutionException.class, () -> await(future));

        assertInstanceOf(type, e.getCause(), String.valueOf(e.getCause()));
    }
}
