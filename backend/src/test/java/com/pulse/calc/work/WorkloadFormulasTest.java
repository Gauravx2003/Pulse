package com.pulse.calc.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.Formula;
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
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final Map<String, String> PEOPLE = new HashMap<>();

    private static final Map<String, JsonArray> LEAVE = new HashMap<>();

    private static final Map<String, JsonArray> VISITS = new HashMap<>();

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

        for (Object item : await(owner.queryList("SELECT id, external_id FROM people WHERE source = 'seed'")))
        {
            JsonObject p = (JsonObject) item;

            PEOPLE.put(p.getString("external_id"), p.getString("id"));
        }

        loadPrototypeWork();

        List<FormulaModule> modules = new ArrayList<>(FormulaRegistry.discover().modules());

        modules.add(peoplePorts());

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

            JsonObject port = without((JsonObject) get(WorkloadFormulas.LIVE_SPRINT, person(id)), "sprint", "elapsedPct",
                    "hoursPerPoint");

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

            JsonObject port = without((JsonObject) get(WorkloadFormulas.BUG_STATS, person(id).put("period", "s:S43")), "period");

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

    private static void loadPrototypeWork() throws Exception
    {
        JsonArray issues = new JsonArray();

        JsonArray events = new JsonArray();

        JsonArray worklogs = new JsonArray();

        JsonArray commits = new JsonArray();

        for (String id : PEOPLE.keySet())
        {
            String person = PEOPLE.get(id);

            for (Object item : (JsonArray) oracle.eval("person('" + id + "').tasks"))
            {
                JsonObject t = (JsonObject) item;

                boolean done = "Done".equals(t.getString("status"));

                issues.add(new JsonObject()
                        .put("key", t.getString("key")).put("summary", t.getString("summary")).put("issue_type", t.getString("type"))
                        .put("status", t.getString("status")).put("assignee_id", person).put("sprint", "S43").put("release", "8.3")
                        .put("story_points", t.getValue("sp")).put("estimate_hours", t.getValue("orig"))
                        .put("spent_hours", t.getValue("spent")).put("remaining_hours", t.getValue("rem"))
                        .put("due_on", t.getString("due")).put("started_on", t.getString("started"))
                        .put("resolved_on", done ? t.getString("last").substring(0, 10) : null)
                        .put("carried_over", false).put("was_stale", false).put("reopened", false));

                events.add(new JsonObject().put("key", t.getString("key")).put("actor_id", person)
                        .put("occurred_at", t.getString("last") + ":00+05:30").put("summary", t.getString("lastWhat")));
            }

            for (Object item : (JsonArray) oracle.eval("HIST.filter(function(h){return h.pid==='" + id + "'})"))
            {
                JsonObject h = (JsonObject) item;

                boolean carried = "Carried over".equals(h.getString("status"));

                issues.add(new JsonObject()
                        .put("key", h.getString("key")).put("summary", h.getString("summary")).put("issue_type", h.getString("type"))
                        .put("status", carried ? "In Progress" : "Done").put("assignee_id", person)
                        .put("sprint", h.getString("sprint")).put("release", h.getString("rel"))
                        .put("story_points", h.getValue("sp")).put("estimate_hours", h.getValue("orig"))
                        .put("spent_hours", h.getValue("spent")).put("remaining_hours", 0)
                        .put("started_on", h.getString("started")).put("resolved_on", h.getString("resolved"))
                        .put("carried_over", carried).put("was_stale", h.getBoolean("stale", false))
                        .put("reopened", h.getBoolean("reopened", false)));
            }

            for (Object item : (JsonArray) oracle.eval("(function(p){return days('2026-04-01','2026-10-11')"
                    + ".filter(function(s){return !visitOn(p.id,s)}).map(function(s){var x=dayInfo(p,s);"
                    + "return {day:s,h:x.h,gh:!!x.gh}})})(person('" + id + "'))"))
            {
                JsonObject day = (JsonObject) item;

                if (day.getDouble("h") > 0)
                {
                    worklogs.add(new JsonObject().put("person_id", person).put("work_date", day.getString("day"))
                            .put("hours", day.getValue("h")));
                }

                if (day.getBoolean("gh"))
                {
                    commits.add(new JsonObject().put("person_id", person).put("commit_date", day.getString("day")));
                }
            }

            JsonArray leave = new JsonArray();

            ((JsonArray) oracle.eval("(function(p){return days(" + RANGE + ").filter(function(s){return onLeave(p,s)})})"
                    + "(person('" + id + "'))")).forEach(leave::add);

            LEAVE.put(person, leave);

            VISITS.put(person, (JsonArray) oracle.eval("VISITS.filter(function(v){return v.pid==='" + id + "'}).map(function(v){"
                    + "return {from:v.from,to:v.to,hours:v.report?Math.round(v.report.onsite/vDays(v).length*2)/2:8}})"));
        }

        await(owner.execute("""
                INSERT INTO issues (key, summary, issue_type, status, assignee_id, sprint_id, release_id, story_points,
                                    estimate_hours, spent_hours, remaining_hours, due_on, started_on, resolved_on,
                                    carried_over, was_stale, reopened, source, external_id)
                SELECT x.key, x.summary, x.issue_type, x.status, x.assignee_id,
                       (SELECT id FROM sprints WHERE key = x.sprint), (SELECT id FROM releases WHERE key = x.release),
                       x.story_points, x.estimate_hours, x.spent_hours, x.remaining_hours, x.due_on, x.started_on,
                       x.resolved_on, x.carried_over, x.was_stale, x.reopened, 'test', x.key
                FROM json_to_recordset($1::json) AS x(key text, summary text, issue_type text, status text, assignee_id uuid,
                     sprint text, release text, story_points numeric, estimate_hours numeric, spent_hours numeric,
                     remaining_hours numeric, due_on date, started_on date, resolved_on date, carried_over boolean,
                     was_stale boolean, reopened boolean)""", Tuple.of(issues)));

        await(owner.execute("""
                INSERT INTO issue_events (issue_id, occurred_at, kind, actor_id, actor_name, summary)
                SELECT (SELECT id FROM issues WHERE key = x.key AND source = 'test'), x.occurred_at, 'worklog', x.actor_id,
                       'Assignee', x.summary
                FROM json_to_recordset($1::json) AS x(key text, occurred_at timestamptz, actor_id uuid, summary text)""",
                Tuple.of(events)));

        await(owner.execute("""
                INSERT INTO worklogs (person_id, work_date, hours)
                SELECT x.person_id, x.work_date, x.hours
                FROM json_to_recordset($1::json) AS x(person_id uuid, work_date date, hours numeric)""", Tuple.of(worklogs)));

        await(owner.execute("""
                INSERT INTO commit_days (person_id, commit_date, commit_count)
                SELECT x.person_id, x.commit_date, 1
                FROM json_to_recordset($1::json) AS x(person_id uuid, commit_date date)""", Tuple.of(commits)));

        JsonArray bugs = new JsonArray();

        for (Object item : (JsonArray) oracle.eval("BUGS"))
        {
            JsonObject b = (JsonObject) item;

            bugs.add(new JsonObject().put("key", b.getString("key")).put("title", b.getString("title"))
                    .put("severity", b.getString("sev")).put("status", b.getString("status")).put("environment", b.getString("env"))
                    .put("origin", PEOPLE.get(b.getString("origin"))).put("assignee", PEOPLE.get(b.getString("assignee")))
                    .put("fixed_by", PEOPLE.get(b.getString("fixedBy"))).put("proactive", b.getBoolean("proactive", false))
                    .put("reported_on", b.getString("created")).put("resolved_on", b.getString("resolved")));
        }

        await(owner.execute("""
                INSERT INTO bugs (key, title, severity, status, environment, origin_person_id, assignee_id, fixed_by_id,
                                  proactive, reported_on, resolved_on, source, external_id)
                SELECT x.key, x.title, x.severity, x.status, x.environment, x.origin, x.assignee, x.fixed_by, x.proactive,
                       x.reported_on, x.resolved_on, 'test', x.key
                FROM json_to_recordset($1::json) AS x(key text, title text, severity text, status text, environment text,
                     origin uuid, assignee uuid, fixed_by uuid, proactive boolean, reported_on date, resolved_on date)""",
                Tuple.of(bugs)));

        JsonArray tickets = new JsonArray();

        for (Object item : (JsonArray) oracle.eval("SUPPORT"))
        {
            JsonObject t = (JsonObject) item;

            tickets.add(new JsonObject().put("key", t.getString("key")).put("title", t.getString("title"))
                    .put("priority", t.getString("pri")).put("person_id", PEOPLE.get(t.getString("pid")))
                    .put("hours", t.getValue("hours")).put("status", t.getString("status"))
                    .put("opened_on", t.getString("date")).put("csat", t.getValue("csat")));
        }

        await(owner.execute("""
                INSERT INTO support_tickets (key, title, priority, person_id, hours, status, opened_on, csat, source, external_id)
                SELECT x.key, x.title, x.priority, x.person_id, x.hours, x.status, x.opened_on, x.csat, 'test', x.key
                FROM json_to_recordset($1::json) AS x(key text, title text, priority text, person_id uuid, hours numeric,
                     status text, opened_on date, csat smallint)""", Tuple.of(tickets)));
    }

    /**
     * A stand-in for Track 3's optional ports, answering from the prototype's leave and visits.
     */
    private static FormulaModule peoplePorts()
    {
        return new FormulaModule()
        {
            @Override
            public String id()
            {
                return "people";
            }

            @Override
            public List<Formula> formulas()
            {
                return List.of(
                        Formula.of("people.leaveDays", Set.of("person"), Set.of(),
                                (ctx, params) -> Future.succeededFuture(LEAVE.getOrDefault(params.getString("person"), new JsonArray()))),
                        Formula.of("people.visitDays", Set.of("person"), Set.of(),
                                (ctx, params) -> Future.succeededFuture(VISITS.getOrDefault(params.getString("person"), new JsonArray()))));
            }
        };
    }

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
