package com.pulse.calc.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
import com.pulse.modules.work.WorkModule;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 8.1: the workload formulas through CalcService, on a database holding the prototype's own work
 * data (live tasks, history issues, day-by-day worklogs and commits, bugs and tickets) with "today"
 * pinned to 28 Sep 2026, compared with the oracle for every seeded person, sprint and month. Leave and
 * customer visits come from a stand-in for Track 3's optional {@code people.leaveDays} and
 * {@code people.visitDays} ports, filled from the prototype.
 */
class WorkloadFormulasTest
{

    private static final String RANGE = "'2026-03-01','2026-12-31'";

    private static TestDb db;

    private static SqlExecutor owner;

    private static CalcService calc;

    private static Oracle oracle;

    /**
     * Prototype id ("ps") to people.id.
     */
    private static Map<String, String> PEOPLE;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(WorkloadFormulasTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule())));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));

        await(MigrationRunner.seed(db.ownerPool(), catalog, true));

        owner = new SqlExecutor(db.ownerPool());

        oracle = Oracle.start();

        PrototypeWork prototype = new PrototypeWork(oracle, owner);

        prototype.loadWork();

        PEOPLE = prototype.people;

        List<FormulaModule> modules = new ArrayList<>(FormulaRegistry.discover().modules());

        prototype.replacePorts(modules);

        calc = new CalcService(new SqlExecutor(db.pool(Db.DEFAULT_APP_ROLE)),
                PulseClock.of(Clock.systemUTC(), "2026-09-28", false), FormulaRegistry.of(modules));
    }

    @AfterAll
    static void tearDown() throws Exception
    {
        if (oracle != null)
        {
            oracle.close();
        }

        if (db != null)
        {
            db.close();
        }
    }

    // ------------------------------------------------------------------ comparisons

    @Test
    void liveSprintMatchesMetricsForEveryPerson() throws Exception
    {
        for (String id : PEOPLE.keySet())
        {
            Object prototype = oracle.eval("(function(m){var k=function(t){return t.key};"
                    + "return {open:m.open.map(k).sort(),rem:m.rem,work:m.work,cap:m.cap,util:m.util,band:m.band,"
                    + "stale:m.stale.map(k).sort(),sp:m.sp,done:m.done.map(k).sort(),oldest:m.oldest}})(metrics(person('" + id + "')))");

            JsonObject port = without((JsonObject) get(WorkloadFormulas.LIVE_SPRINT, person(id)), "sprint", "openIssues", "elapsedPct",
                    "hoursPerPoint", "focus");

            assertSame(id, prototype, port);
        }
    }

    @Test
    void periodWorkAndUtilizationMatchForEveryPersonSprintAndMonth() throws Exception
    {
        int compared = 0;

        for (String id : PEOPLE.keySet())
        {
            for (String period : periods())
            {
                Object prototype = oracle.eval("(function(r){return {work:r.work,workAll:r.workAll,logged:r.logged,"
                        + "active:r.active,codeOnly:r.codeOnly,leave:r.leave,weekend:r.weekend,daysWorked:r.daysWorked,"
                        + "cover:r.cover}})(monthStats(person('" + id + "'),perOf('" + period + "')))");

                JsonObject params = person(id).put("period", period);

                assertSame(id + " " + period, prototype, without((JsonObject) get(WorkloadFormulas.PERIOD_WORK, params), "period"));

                Object utilization = oracle.eval("utilOf(person('" + id + "'),perOf('" + period + "'))");

                assertSame(id + " " + period + " utilization", utilization, get(WorkloadFormulas.UTILIZATION, params));

                compared++;
            }
        }

        assertTrue(compared > 100, "every person, sprint and month: " + compared);
    }

    @Test
    void sprintMetricsMatchPerMetricsForEveryPersonSprintAndMonth() throws Exception
    {
        for (String id : PEOPLE.keySet())
        {
            for (String period : periods())
            {
                Object prototype = oracle.eval("(function(m){var k=function(x){return x.key};"
                        + "return {com:m.com,dsp:m.dsp,commit:m.commit,carry:m.carry.map(k).sort(),bugs:m.bugs.map(k).sort(),"
                        + "open:m.open.map(k).sort(),cyc:m.cyc,logged:m.logged,work:m.work,cover:m.cover,est:m.est,stale:m.stale}})"
                        + "(perMetrics(person('" + id + "'),perOf('" + period + "')))");

                JsonObject port = without((JsonObject) get(WorkloadFormulas.SPRINT_METRICS, person(id).put("period", period)),
                        "reopenRate", "period");

                assertSame(id + " " + period, prototype, port);
            }
        }
    }

    @Test
    void bugStatsMatchQStatsForEveryPerson() throws Exception
    {
        for (String id : PEOPLE.keySet())
        {
            Object prototype = oracle.eval("(function(q){return {mine:q.mine.length,open:q.open.length,high:q.high,"
                    + "q3:q.q3.length,prod:q.prod,fixedOthers:q.fixedOthers.length,proactive:q.proactive.length,"
                    + "sup:q.sup.length,supM:q.supM.length,supH:q.supH,csat:q.csat}})(qStats('" + id + "'))");

            JsonObject port = without((JsonObject) get(WorkloadFormulas.BUG_STATS, person(id).put("period", "s:S43")), "supResolved", "period");

            assertSame(id, prototype, port);
        }
    }

    @Test
    void everyLiveIssuesFlagsMatch() throws Exception
    {
        int compared = 0;

        for (String id : PEOPLE.keySet())
        {
            JsonArray tasks = (JsonArray) oracle.eval("person('" + id + "').tasks");

            for (int i = 0; i < tasks.size(); i++)
            {
                String key = tasks.getJsonObject(i).getString("key");

                String issue = await(owner.queryOne("SELECT id FROM issues WHERE key = $1 AND source = 'test'", Tuple.of(key)))
                        .getString("id");

                JsonObject port = (JsonObject) get(WorkloadFormulas.ISSUE, new JsonObject().put("issue", issue));

                assertEquals(oracle.eval("flags(person('" + id + "').tasks[" + i + "], person('" + id + "'))"),
                        port.getJsonArray("flags"), id + " " + key);

                compared++;
            }
        }

        assertTrue(compared > 20, "every live issue: " + compared);
    }

    @Test
    void badParametersAreRefused()
    {
        ExecutionException noPerson = assertThrows(ExecutionException.class,
                () -> await(calc.get(WorkloadFormulas.LIVE_SPRINT, new JsonObject())));

        assertInstanceOf(IllegalArgumentException.class, noPerson.getCause());

        ExecutionException badPeriod = assertThrows(ExecutionException.class,
                () -> await(calc.get(WorkloadFormulas.PERIOD_WORK, person("ps").put("period", "x:1"))));

        assertInstanceOf(IllegalArgumentException.class, badPeriod.getCause());
    }

    // ------------------------------------------------------------------ prototype data into the tables

    // ------------------------------------------------------------------ helpers

    private static List<String> periods()
    {
        List<String> out = new ArrayList<>();

        for (Object id : (JsonArray) oracle.eval("SPRINTS.map(function(s){return s.id})"))
        {
            out.add("s:" + id);
        }

        for (Object month : (JsonArray) oracle.eval("PER_MONTHS.map(function(m){return m[0]})"))
        {
            out.add("m:" + month);
        }

        return out;
    }

    private static JsonObject person(String id)
    {
        return new JsonObject().put("person", PEOPLE.get(id));
    }

    private static Object get(String formula, JsonObject params) throws Exception
    {
        return await(calc.get(formula, params));
    }

    private static JsonObject without(JsonObject json, String... keys)
    {
        JsonObject copy = json.copy();

        for (String key : keys)
        {
            copy.remove(key);
        }

        // Issue keys are compared as sets: the order is presentation (the prototype keeps task order).
        for (String key : List.of("carry", "bugs", "open", "stale", "done"))
        {
            if (copy.getValue(key) instanceof JsonArray list)
            {
                List<String> sorted = new ArrayList<>();

                list.forEach(v -> sorted.add(String.valueOf(v)));

                sorted.sort(null);

                copy.put(key, new JsonArray(new ArrayList<Object>(sorted)));
            }
        }

        return copy;
    }

    /**
     * Equal member by member, numbers as shown with 2 decimals; a guarded "not applicable" equals null.
     */
    private static void assertSame(String where, Object prototype, Object port)
    {
        List<String> diffs = new ArrayList<>();

        diff("$", prototype, port, diffs);

        if (!diffs.isEmpty())
        {
            fail(where + ": " + String.join("; ", diffs));
        }
    }

    private static void diff(String path, Object expected, Object actual, List<String> diffs)
    {
        Object a = ResultGuard.isNotApplicable(actual) ? null : actual;

        if (expected instanceof Number e && a instanceof Number n)
        {
            if (!Oracle.display(e, 2).equals(Oracle.display(n, 2)))
            {
                diffs.add(path + ": prototype " + e + ", port " + n);
            }

            return;
        }

        if (expected instanceof JsonObject e && a instanceof JsonObject o)
        {
            if (!e.fieldNames().equals(o.fieldNames()))
            {
                diffs.add(path + ": keys " + e.fieldNames() + " vs " + o.fieldNames());

                return;
            }

            for (String key : e.fieldNames())
            {
                diff(path + "." + key, e.getValue(key), o.getValue(key), diffs);
            }

            return;
        }

        if (expected instanceof JsonArray e && a instanceof JsonArray o)
        {
            if (e.size() != o.size())
            {
                diffs.add(path + ": " + e.encode() + " vs " + o.encode());

                return;
            }

            for (int i = 0; i < e.size(); i++)
            {
                diff(path + "[" + i + "]", e.getValue(i), o.getValue(i), diffs);
            }

            return;
        }

        if (expected == null ? a != null : !expected.equals(a))
        {
            diffs.add(path + ": prototype " + expected + ", port " + a);
        }
    }
}
