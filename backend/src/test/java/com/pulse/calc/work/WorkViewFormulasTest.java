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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 8.2: the Overview and Work items formulas through CalcService on the prototype's work data, compared
 * with what the prototype's screens compute for every seeded person and period: the daily load of
 * {@code pSprint}, {@code utilTrendCard}, {@code sprintBreak}, {@code releaseCard}, the alerts of
 * {@code pOverview} and the issue lists of {@code metrics} / {@code issuesIn}.
 */
class WorkViewFormulasTest
{

    private static TestDb db;

    private static SqlExecutor owner;

    private static CalcService calc;

    private static Oracle oracle;

    private static Map<String, String> people;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(WorkViewFormulasTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule())));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));

        await(MigrationRunner.seed(db.ownerPool(), catalog, true));

        owner = new SqlExecutor(db.ownerPool());

        oracle = Oracle.start();

        PrototypeWork prototype = new PrototypeWork(oracle, owner);

        prototype.loadWork();

        prototype.loadOnboarding();

        people = prototype.people;

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

    @Test
    void dailyLoadMatchesPSprint() throws Exception
    {
        for (String id : people.keySet())
        {
            // The bars of pSprint() (app.js ~291-294). Recorded deviation: the prototype's card reads past days from
            // p.logged, its 5-day sample (empty for a new joiner), while every other hours figure of the prototype
            // (monthStats, Hours logged, utilization) reads the day-by-day hours of dayInfo. The port reads the
            // worklogs, which are dayInfo's hours, so the card agrees with Hours logged.
            Object prototype = oracle.eval("(function(p){var m=metrics(p);return workBetween(SPRINT.start,SPRINT.end).map(function(s){"
                    + "var past=s<TODAY,v=past?dayInfo(p,s).h:(isWork(s,p)?m.rem/Math.max(m.cap,1):0),"
                    + "lv=p.leave.indexOf(s)>=0?'Leave':(visitOn(p.id,s)?'Customer visit':'');"
                    + "return {date:s,kind:past?'logged':'planned',hours:lv?null:v,note:lv||null,today:s===TODAY}})})(person('" + id + "'))");

            JsonObject port = (JsonObject) get(WorkViewFormulas.DAILY_LOAD, person(id));

            assertSame(id, prototype, port.getJsonArray("days"));
        }
    }

    @Test
    void utilizationTrendMatchesUtilTrendCard() throws Exception
    {
        for (String id : people.keySet())
        {
            Object prototype = oracle.eval("(function(p){var vals=SPRINTS.slice(0,8).slice().reverse().map(function(sp){var pp=perOf('s:'+sp.id);"
                    + "if(JOINED[p.id]&&sp.end<JOINED[p.id])return null;return {period:pp.key,short:pp.short,util:utilOf(p,pp),live:pp.live,band:band(utilOf(p,pp))}})"
                    + ".filter(Boolean),past=vals.filter(function(v){return !v.live}),"
                    + "avg=past.length?Math.round(past.reduce(function(a,v){return a+v.util},0)/past.length):null;"
                    + "return {sprints:vals,overall:avg,band:avg===null?null:band(avg),finished:past.length}})(person('" + id + "'))");

            assertSame(id, prototype, get(WorkViewFormulas.UTILIZATION_TREND, person(id)));
        }
    }

    @Test
    void sprintBreakdownMatchesSprintBreakForEveryPeriod() throws Exception
    {
        for (String id : people.keySet())
        {
            for (String period : periods())
            {
                Object prototype = oracle.eval("(function(p,per){var sids=per.kind==='sprint'?[per.id]:per.kind==='release'?"
                        + "per.rel.sprints.filter(function(s){return SPRINTS.some(function(z){return z.id===s})}):"
                        + "SPRINTS.filter(function(z){return z.from<=per.to&&z.to>=per.from}).map(function(z){return z.id});"
                        + "return sids.map(function(sid){var pp=perOf('s:'+sid),m=perMetrics(p,pp);return {period:pp.key,short:pp.short,"
                        + "from:pp.from,end:pp.end,live:pp.live,release:relOf(sid),issues:m.is.length,committed:m.com,done:m.dsp,"
                        + "commitment:m.commit,carried:m.carry.length,carriedPoints:m.carry.reduce(function(a,x){return a+x.sp},0),"
                        + "logged:m.logged,util:utilOf(p,pp)}}).sort(function(a,b){return a.from<b.from?1:-1})})"
                        + "(person('" + id + "'),perOf('" + period + "'))");

                JsonObject port = (JsonObject) get(WorkViewFormulas.SPRINT_BREAKDOWN, person(id).put("period", period));

                assertSame(id + " " + period, prototype, port.getJsonArray("sprints"));
            }
        }
    }

    @Test
    void releaseCardMatchesForEveryRelease() throws Exception
    {
        for (String id : people.keySet())
        {
            for (Object release : (JsonArray) oracle.eval("RELEASES.map(function(r){return r.id})"))
            {
                Object prototype = oracle.eval("(function(p,per){var r=per.rel,team=PEOPLE.map(function(q){return perMetrics(q,per)}),"
                        + "tsp=team.reduce(function(a,m){return a+m.dsp},0),m=perMetrics(p,per),"
                        + "left=r.status==='Released'?0:workBetween(TODAY,r.rel).length;return {key:r.id,name:r.name,from:r.from,to:r.to,"
                        + "releaseDate:r.rel,status:r.status,workingDaysLeft:left,sprints:r.sprints.filter(function(s){"
                        + "return SPRINTS.some(function(z){return z.id===s})}).map(function(s){return 's:'+s}),"
                        + "personDone:m.dsp,teamDone:tsp,share:tsp?Math.round(m.dsp/tsp*100):0}})"
                        + "(person('" + id + "'),perOf('r:" + release + "'))");

                assertSame(id + " r:" + release, prototype, get(WorkViewFormulas.RELEASE_CARD, person(id).put("period", "r:" + release)));
            }
        }
    }

    @Test
    void liveAlertMatchesPOverview() throws Exception
    {
        int alerts = 0;

        for (String id : people.keySet())
        {
            // The alerts of pOverview() (app.js ~249-252), with onboarding first as the spec asks (the prototype
            // checks overload first; see WorkViews.alert).
            Object prototype = oracle.eval("(function(p){var m=metrics(p);if(onboarding(p.id)){var oa=trFor(p.id).filter(function(a){"
                    + "return a.status==='Active'})[0];return {kind:'onboarding',plan:{plan:oa.plan.name}}}"
                    + "if(m.util>100){var big=m.open.filter(function(t){return t.status==='To Do'}).sort(function(a,b){return b.rem-a.rem})[0];"
                    + "return {kind:'overloaded',band:m.band,critical:m.util>S.rules.over,workDays:m.work,capacity:m.cap,daysOver:m.work-m.cap,"
                    + "leave:p.leave.filter(function(x){return x>=TODAY}).sort(),"
                    + "suggestMove:big?{key:big.key,storyPoints:big.sp,remainingHours:big.rem}:null}}"
                    + "if(m.util<S.rules.under)return {kind:'under',band:m.band,spareDays:m.cap-m.work};return null})(person('" + id + "'))");

            Object port = get(WorkViewFormulas.LIVE_ALERT, person(id));

            assertSame(id, prototype, port);

            if (prototype != null)
            {
                alerts++;
            }
        }

        assertTrue(alerts > 0, "the seed has people with an alert");
    }

    @Test
    void liveAndPeriodIssuesMatchTheirPrototypeLists() throws Exception
    {
        for (String id : people.keySet())
        {
            Object prototype = oracle.eval("(function(p){return p.tasks.map(function(t){return {key:t.key,flags:flags(t,p),"
                    + "remainingHours:t.rem,idleDays:idle(t,p),lastActivity:t.last}}).sort(function(a,b){return a.key<b.key?-1:1})})"
                    + "(person('" + id + "'))");

            JsonArray port = new JsonArray();

            for (Object item : ((JsonObject) get(WorkViewFormulas.LIVE_ISSUES, person(id))).getJsonArray("items"))
            {
                JsonObject issue = (JsonObject) item;

                port.add(new JsonObject().put("key", issue.getString("key")).put("flags", issue.getJsonArray("flags"))
                        .put("remainingHours", issue.getValue("remainingHours")).put("idleDays", issue.getValue("idleDays"))
                        .put("lastActivity", issue.getString("lastActivity")));
            }

            assertSame(id + " live", prototype, port);

            for (String period : periods())
            {
                Object expected = oracle.eval("issuesIn(person('" + id + "'),perOf('" + period + "')).map(function(x){"
                        + "return {key:x.key,cycle:x.cycle||null,live:!!x.live,wasStale:!!x.stale}}).sort(function(a,b){return a.key<b.key?-1:1})");

                JsonArray actual = new JsonArray();

                List<JsonObject> rows = new ArrayList<>();

                ((JsonObject) get(WorkViewFormulas.PERIOD_ISSUES, person(id).put("period", period))).getJsonArray("items")
                        .forEach(x -> rows.add((JsonObject) x));

                rows.sort((a, b) -> a.getString("key").compareTo(b.getString("key")));

                for (JsonObject x : rows)
                {
                    Object cycle = x.getValue("cycle");

                    actual.add(new JsonObject().put("key", x.getString("key"))
                            .put("cycle", cycle instanceof Number n && n.intValue() != 0 ? cycle : null)
                            .put("live", x.getBoolean("live")).put("wasStale", x.getBoolean("wasStale")));
                }

                assertSame(id + " " + period, expected, actual);
            }
        }
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

        for (Object release : (JsonArray) oracle.eval("RELEASES.map(function(r){return r.id})"))
        {
            out.add("r:" + release);
        }

        return out;
    }

    private static JsonObject person(String id)
    {
        return new JsonObject().put("person", people.get(id));
    }

    private static Object get(String formula, JsonObject params) throws Exception
    {
        return await(calc.get(formula, params));
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
            fail(where + ": " + String.join("; ", diffs.subList(0, Math.min(8, diffs.size()))));
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
