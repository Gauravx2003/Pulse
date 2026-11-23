package com.pulse.calc.work;

import com.pulse.calc.core.CalcService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 10.1: the PR standard through CalcService on a database holding the prototype's 177 pull
 * requests with their reviews and comments, compared with the oracle's {@code prStats}, the team
 * aggregation of {@code prTeam} and {@code prChecks} for every person and period (each month, each
 * sprint and All). Same-day PRs come back from the database in another order than the prototype's
 * list, so ids, comments and the "checks across the team" are compared without their order.
 */
class PrFormulasTest
{

    private static TestDb db;

    private static SqlExecutor owner;

    private static CalcService calc;

    private static Oracle oracle;

    private static final Map<String, String> PEOPLE = new HashMap<>();

    /**
     * pull_requests.id to the prototype PR id ("#761", "!1401").
     */
    private static final Map<String, String> NUMBERS = new HashMap<>();

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(PrFormulasTest.class.getClassLoader(),
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

        new PrototypeWork(oracle, owner).loadPrs();

        for (Object item : await(owner.queryList("SELECT id, number FROM pull_requests")))
        {
            JsonObject p = (JsonObject) item;

            NUMBERS.put(p.getString("id"), p.getString("number"));
        }

        calc = new CalcService(new SqlExecutor(db.pool(Db.DEFAULT_APP_ROLE)),
                PulseClock.of(Clock.systemUTC(), "2026-09-28", false), FormulaRegistry.discover());
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
    void personStatsMatchForEveryPersonAndPeriod() throws Exception
    {
        int compared = 0;

        for (String id : PEOPLE.keySet())
        {
            for (String[] period : periods())
            {
                Object prototype = oracle.eval("(function(s){var k=function(x){return x.id};return {mine:s.mine.map(k).sort(),"
                        + "merged:s.merged,active:s.active,comp:s.comp,title:s.title,size:s.size,big:s.big,rv:s.rv.map(k).sort(),"
                        + "cm:s.cm.slice().sort(),std:s.std,stdPct:s.stdPct,stamps:s.stamps,firstRev:s.firstRev}})"
                        + "(prStats('" + id + "'," + period[1] + "))");

                JsonObject port = (JsonObject) await(calc.get(PrFormulas.PERSON,
                        new JsonObject().put("person", PEOPLE.get(id)).put("period", period[0])));

                port.remove("period");

                port.put("mine", numbers(port.getJsonArray("mine")));

                port.put("rv", numbers(port.getJsonArray("rv")));

                port.put("cm", sorted(port.getJsonArray("cm")));

                assertSame(id + " " + period[0], prototype, port);

                compared++;
            }
        }

        assertTrue(compared > 100, "every person and period: " + compared);
    }

    @Test
    void teamStatsMatchForEveryPeriod() throws Exception
    {
        String ids = new JsonArray(new ArrayList<>(PEOPLE.keySet())).encode();

        for (String[] period : periods())
        {
            // The aggregation of prTeam(), checkBars() and cmtBreak() (app.js ~1579-1590); the checks keyed by
            // check and the PRs below the standard as their scores, both independent of the PRs' order.
            JsonObject prototype = (JsonObject) oracle.eval("(function(ids,m){var rows=ids.map(function(id){return prStats(id,m)})"
                    + ".filter(function(s){return s.mine.length||s.rv.length});var all=[],cm=[],st=0;"
                    + "rows.forEach(function(s){all=all.concat(s.mine);cm=cm.concat(s.cm);st+=s.stamps});"
                    + "var comp=all.length?Math.round(all.reduce(function(a,x){return a+prScore(x)},0)/all.length*100):null,"
                    + "tt=all.filter(function(x){return titleRe().test(x.title)}).length,"
                    + "sp=cm.length?Math.round(cm.filter(function(t){return cmtClass(t)[1]==='Standard'}).length/cm.length*100):null;"
                    + "var keys={};all.forEach(function(x){prChecks(x).forEach(function(c){if(c.ok===null)return;"
                    + "if(!keys[c.k])keys[c.k]={k:c.k,l:c.l,p:0,n:0};keys[c.k].n++;if(c.ok)keys[c.k].p++})});"
                    + "var g={};cm.forEach(function(t){var c=cmtClass(t)[1];if(c==='Needs a reason')c='Too short';g[c]=(g[c]||0)+1});"
                    + "var bad=all.filter(function(x){return x.status==='Completed'&&prChecks(x).some(function(c){return c.ok===false})})"
                    + ".map(prScore).sort(function(a,b){return a-b}).slice(0,8);"
                    + "return {prs:all.length,merged:all.filter(function(x){return x.status==='Completed'}).length,comp:comp,"
                    + "titles:tt,titlePct:all.length?Math.round(tt/all.length*100):null,comments:cm.length,stdPct:sp,stamps:st,"
                    + "checks:keys,commentClasses:g,belowStandard:bad}})(" + ids + "," + period[1] + ")");

            JsonObject port = (JsonObject) await(calc.get(PrFormulas.TEAM,
                    new JsonObject().put("team", "Platform").put("period", period[0])));

            JsonObject checks = new JsonObject();

            port.getJsonArray("checks").forEach(c -> checks.put(((JsonObject) c).getString("k"), c));

            JsonArray scores = new JsonArray();

            for (Object pr : port.getJsonArray("belowStandard"))
            {
                scores.add(((JsonObject) await(calc.get(PrFormulas.DETAIL, new JsonObject().put("pr", pr)))).getDouble("score"));
            }

            port.put("checks", checks).put("belowStandard", scores);

            port.remove("people");

            port.remove("team");

            port.remove("period");

            assertSame("team " + period[0], prototype, port);
        }
    }

    @Test
    void everyPrsDetailMatchesPrChecks() throws Exception
    {
        JsonArray prototype = (JsonArray) oracle.eval("PRS.map(function(x){return {id:x.id,checks:prChecks(x),score:prScore(x)}})");

        Map<String, JsonObject> byNumber = new HashMap<>();

        prototype.forEach(x -> byNumber.put(((JsonObject) x).getString("id"), (JsonObject) x));

        for (Map.Entry<String, String> pr : NUMBERS.entrySet())
        {
            JsonObject port = (JsonObject) await(calc.get(PrFormulas.DETAIL, new JsonObject().put("pr", pr.getKey())));

            JsonObject expected = byNumber.get(pr.getValue());

            JsonArray checks = new JsonArray();

            for (Object item : port.getJsonArray("checks"))
            {
                JsonObject check = ((JsonObject) item).copy();

                String state = (String) check.remove("state");

                assertEquals(ResultGuard.isNotApplicable(check.getValue("ok")) ? "pending" : check.getBoolean("ok") ? "pass" : "fail",
                        state, pr.getValue() + " " + check.getString("k"));

                checks.add(check);
            }

            // A pending check's ok is null in the prototype and "not applicable" once guarded.
            assertSame(pr.getValue(), expected.getJsonArray("checks"), checks);

            assertEquals(Oracle.display(expected.getDouble("score"), 3), Oracle.display(port.getDouble("score"), 3), pr.getValue());
        }

        assertEquals(177, NUMBERS.size());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * [period key, the prototype's period argument]: All, each month and each sprint.
     */
    private static List<String[]> periods()
    {
        List<String[]> out = new ArrayList<>();

        out.add(new String[] {"all", "null"});

        for (Object month : (JsonArray) oracle.eval("PR_MONTHS.map(function(m){return m[0]})"))
        {
            out.add(new String[] {"m:" + month, "'" + month + "'"});
        }

        for (Object id : (JsonArray) oracle.eval("SPRINTS.map(function(s){return s.id})"))
        {
            out.add(new String[] {"s:" + id, "perOf('s:" + id + "')"});
        }

        return out;
    }

    private static JsonArray numbers(JsonArray ids)
    {
        List<String> out = new ArrayList<>();

        ids.forEach(id -> out.add(NUMBERS.get((String) id)));

        out.sort(null);

        return new JsonArray(new ArrayList<Object>(out));
    }

    private static JsonArray sorted(JsonArray values)
    {
        List<String> out = new ArrayList<>();

        values.forEach(v -> out.add((String) v));

        out.sort(null);

        return new JsonArray(new ArrayList<Object>(out));
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
            fail(where + ": " + String.join("; ", diffs.subList(0, Math.min(6, diffs.size()))));
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
