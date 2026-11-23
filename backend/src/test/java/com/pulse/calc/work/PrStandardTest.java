package com.pulse.calc.work;

import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 10.1: {@link PrStandard} against spec work/pull-requests and against the prototype oracle for
 * all 177 seeded pull requests, every person and every period (each month, each sprint and All),
 * under the default rules and an altered rule set: {@code prChecks}, {@code prScore}, {@code cmtClass},
 * {@code prStats} and the team aggregation of {@code prTeam}.
 */
class PrStandardTest
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
    void aTitleOfFixedStuffFails()
    {
        JsonObject rules = new JsonObject();

        assertFalse(PrStandard.titleOk("fixed stuff.", rules));
        assertTrue(PrStandard.titleOk("PLAT-2231 feat(ingestion): add per-tenant rate limit", rules));
        assertFalse(PrStandard.titleOk("PLAT-2231 feat(ingestion): add per-tenant rate limit.", rules), "no full stop");
    }

    @Test
    void anOpenPrWithoutABuildIsPendingAndLeftOutOfTheScore()
    {
        JsonObject pr = new JsonObject().put("id", "#1").put("status", "Active").put("title", "PLAT-1 fix(x): handle empty tenants")
                .put("workItemKey", "PLAT-1").put("hasSummary", true).put("hasTesting", true).put("hasRisk", true)
                .put("linesChanged", 120).put("reviews", new JsonArray());

        JsonArray checks = PrStandard.checks(pr, new JsonObject());

        JsonObject build = checks.stream().map(c -> (JsonObject) c).filter(c -> "build".equals(c.getString("k"))).findFirst().orElseThrow();

        assertNull(build.getValue("ok"), "Pending");

        // Title, item, description and size pass; approvals, build and threads are Pending.
        assertEquals(1.0, PrStandard.score(pr, new JsonObject()), 1e-9);
    }

    @Test
    void aSilentApprovalOfABigPrIsARubberStamp()
    {
        JsonObject pr = new JsonObject().put("id", "#2").put("authorId", "a").put("status", "Completed").put("title", "x")
                .put("openedAt", "2026-09-10").put("linesChanged", 350)
                .put("reviews", new JsonArray().add(new JsonObject().put("reviewerId", "r").put("vote", "Approved")
                        .put("comments", new JsonArray())));

        assertEquals(1, PrStandard.person("r", List.of(pr), null, null, new JsonObject()).getInteger("stamps"));

        assertEquals(0, PrStandard.person("r", List.of(pr.copy().put("linesChanged", 150)), null, null, new JsonObject())
                .getInteger("stamps"), "200 lines or fewer is not a rubber-stamp");
    }

    @Test
    void indianGroupingMatchesToLocaleString()
    {
        assertEquals("950", PrStandard.indian(950));
        assertEquals("1,234", PrStandard.indian(1234));
        assertEquals("12,345", PrStandard.indian(12345));
        assertEquals("1,23,456", PrStandard.indian(123456));
    }

    // ------------------------------------------------------------------ oracle

    @Test
    void everyPrsChecksAndScoreMatch()
    {
        JsonObject rules = rules();

        JsonArray prs = prs();

        for (int i = 0; i < prs.size(); i++)
        {
            JsonObject port = prs.getJsonObject(i);

            assertEquals(oracle.eval("prChecks(PRS[" + i + "])"), PrStandard.checks(port, rules), port.getString("id"));

            oracle.compare("prScore", Oracle.args(Oracle.js("PRS[" + i + "]")), 3, in -> PrStandard.score(port, rules));
        }

        assertEquals(177, prs.size());
    }

    @Test
    void everyCommentsClassMatches()
    {
        JsonObject rules = rules();

        JsonArray texts = (JsonArray) oracle.eval("PRS.reduce(function(a,x){x.reviewers.forEach(function(r){"
                + "r.comments.forEach(function(c){a.push(c.text)})});return a},[])"
                + ".concat(['issue: x','issue: wrong approach','LGTM','question: why?','nitpick (non-blocking): rename it',"
                + "'issue: abcdefghijklmnopqrs','issue: abcdefghijklmnopqrst','todo: abcdefghijklmnopqrstu',"
                + "'abcdefghijklmnopqrs','abcdefghijklmnopqrst'])");

        for (Object text : texts)
        {
            JsonArray expected = (JsonArray) oracle.eval("cmtClass(" + new JsonArray().add(text).encode().replaceAll("^\\[|]$", "") + ")");

            // decided deviation (spec "Short issue comment"): the prototype's "Needs a reason" is "Too short"
            if ("Needs a reason".equals(expected.getString(1)))
            {
                expected = new JsonArray().add(expected.getString(0)).add(PrStandard.TOO_SHORT);
            }

            assertEquals(expected, PrStandard.commentClass((String) text, rules), (String) text);
        }

        assertTrue(texts.size() > 300, "every seeded comment: " + texts.size());
    }

    @Test
    void personStatsMatchForEveryPersonAndPeriod()
    {
        comparePeople(rules());
    }

    @Test
    void teamStatsMatchForEveryPeriod()
    {
        compareTeam(rules());
    }

    @Test
    void everythingStillMatchesUnderAlteredRules()
    {
        oracle.eval("S.rules.prMaxLines = 300; S.rules.prMinAppr = 1; S.rules.prThreads = false; S.rules.prCmtMin = 30");

        JsonObject rules = rules();

        assertEquals(300, rules.getInteger("prMaxLines"));

        JsonArray prs = prs();

        for (int i = 0; i < prs.size(); i++)
        {
            assertEquals(oracle.eval("prChecks(PRS[" + i + "])"), PrStandard.checks(prs.getJsonObject(i), rules));
        }

        comparePeople(rules);

        compareTeam(rules);
    }

    // ------------------------------------------------------------------ comparisons

    private static void comparePeople(JsonObject rules)
    {
        List<JsonObject> prs = list(prs());

        int compared = 0;

        for (Object id : (JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})"))
        {
            for (String[] period : periods())
            {
                Object prototype = oracle.eval("(function(s){var k=function(x){return x.id};return {mine:s.mine.map(k),"
                        + "merged:s.merged,active:s.active,comp:s.comp,title:s.title,size:s.size,big:s.big,rv:s.rv.map(k),"
                        + "cm:s.cm,std:s.std,stdPct:s.stdPct,stamps:s.stamps,firstRev:s.firstRev}})(prStats('" + id + "'," + period[0] + "))");

                JsonObject port = PrStandard.person((String) id, prs, date(period[1]), date(period[2]), rules);

                assertSame(id + " " + period[0], prototype, port);

                compared++;
            }
        }

        assertTrue(compared > 100, "every person and period: " + compared);
    }

    private static void compareTeam(JsonObject rules)
    {
        List<JsonObject> prs = list(prs());

        List<String> people = new ArrayList<>();

        ((JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})")).forEach(id -> people.add((String) id));

        String ids = new JsonArray(new ArrayList<>(people)).encode();

        for (String[] period : periods())
        {
            // The aggregation of prTeam(), checkBars() and cmtBreak() (app.js ~1579-1590) over everyone.
            Object prototype = oracle.eval("(function(ids,m){var rows=ids.map(function(id){return prStats(id,m)})"
                    + ".filter(function(s){return s.mine.length||s.rv.length});var all=[],cm=[],st=0;"
                    + "rows.forEach(function(s){all=all.concat(s.mine);cm=cm.concat(s.cm);st+=s.stamps});"
                    + "var comp=all.length?Math.round(all.reduce(function(a,x){return a+prScore(x)},0)/all.length*100):null,"
                    + "tt=all.filter(function(x){return titleRe().test(x.title)}).length,"
                    + "sp=cm.length?Math.round(cm.filter(function(t){return cmtClass(t)[1]==='Standard'}).length/cm.length*100):null;"
                    + "var keys={},ord=[];all.forEach(function(x){prChecks(x).forEach(function(c){if(c.ok===null)return;"
                    + "if(!keys[c.k]){keys[c.k]={k:c.k,l:c.l,p:0,n:0};ord.push(c.k)}keys[c.k].n++;if(c.ok)keys[c.k].p++})});"
                    + "var g={};cm.forEach(function(t){var c=cmtClass(t)[1];if(c==='Needs a reason')c='Too short';g[c]=(g[c]||0)+1});"
                    + "var bad=all.filter(function(x){return x.status==='Completed'&&prChecks(x).some(function(c){return c.ok===false})})"
                    + ".sort(function(a,b){return prScore(a)-prScore(b)}).slice(0,8);"
                    + "return {prs:all.length,merged:all.filter(function(x){return x.status==='Completed'}).length,comp:comp,"
                    + "titles:tt,titlePct:all.length?Math.round(tt/all.length*100):null,comments:cm.length,stdPct:sp,stamps:st,"
                    + "checks:ord.map(function(k){return keys[k]}),commentClasses:g,belowStandard:bad.map(function(x){return x.id})}})"
                    + "(" + ids + "," + period[0] + ")");

            JsonObject port = PrStandard.team(people, prs, date(period[1]), date(period[2]), rules);

            port.remove("people");

            assertSame("team " + period[0], prototype, port);
        }
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Every period the prototype filters PRs by: [JS argument, from, to]: each month, each sprint and All.
     */
    private static List<String[]> periods()
    {
        List<String[]> out = new ArrayList<>();

        out.add(new String[] {"null", null, null});

        for (Object month : (JsonArray) oracle.eval("PR_MONTHS.map(function(m){return m[0]})"))
        {
            YearMonth ym = YearMonth.parse((String) month);

            out.add(new String[] {"'" + month + "'", ym.atDay(1).toString(), ym.atEndOfMonth().toString()});
        }

        for (Object item : (JsonArray) oracle.eval("SPRINTS.map(function(s){var p=perOf('s:'+s.id);return [p.key,p.from,p.to]})"))
        {
            JsonArray p = (JsonArray) item;

            out.add(new String[] {"perOf('" + p.getString(0) + "')", p.getString(1), p.getString(2)});
        }

        return out;
    }

    private static JsonObject rules()
    {
        return (JsonObject) oracle.eval("S.rules");
    }

    /**
     * The prototype's PRS in the shape of the pull_requests, pr_reviews and pr_comments rows.
     */
    private static JsonArray prs()
    {
        JsonArray out = new JsonArray();

        for (Object item : (JsonArray) oracle.eval("PRS"))
        {
            JsonObject x = (JsonObject) item;

            JsonArray reviews = new JsonArray();

            for (Object r : x.getJsonArray("reviewers"))
            {
                JsonObject reviewer = (JsonObject) r;

                JsonArray comments = new JsonArray();

                for (Object c : reviewer.getJsonArray("comments"))
                {
                    JsonObject comment = (JsonObject) c;

                    comments.add(new JsonObject().put("body", comment.getString("text")).put("isResolved", comment.getBoolean("resolved")));
                }

                reviews.add(new JsonObject().put("reviewerId", reviewer.getString("pid")).put("vote", reviewer.getString("vote"))
                        .put("comments", comments));
            }

            JsonObject desc = x.getJsonObject("desc");

            out.add(new JsonObject()
                    .put("id", x.getString("id"))
                    .put("authorId", x.getString("pid"))
                    .put("title", x.getString("title"))
                    .put("suggestedTitle", x.getString("sugg"))
                    .put("workItemKey", x.getString("item"))
                    .put("status", x.getString("status"))
                    .put("openedAt", x.getString("created"))
                    .put("linesChanged", x.getInteger("lines"))
                    .put("hasSummary", desc.getBoolean("summary"))
                    .put("hasTesting", desc.getBoolean("tested"))
                    .put("hasRisk", desc.getBoolean("risk"))
                    .put("buildPassed", x.getBoolean("build"))
                    .put("firstReviewHours", x.getValue("firstRev"))
                    .put("reviews", reviews));
        }

        return out;
    }

    private static List<JsonObject> list(JsonArray rows)
    {
        List<JsonObject> out = new ArrayList<>();

        rows.forEach(r -> out.add((JsonObject) r));

        return out;
    }

    private static LocalDate date(String value)
    {
        return value == null ? null : LocalDate.parse(value);
    }

    /**
     * Equal member by member, numbers as shown with 2 decimals.
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
        if (expected instanceof Number e && actual instanceof Number a)
        {
            if (!Oracle.display(e, 2).equals(Oracle.display(a, 2)))
            {
                diffs.add(path + ": prototype " + e + ", port " + a);
            }

            return;
        }

        if (expected instanceof JsonObject e && actual instanceof JsonObject a)
        {
            if (!e.fieldNames().equals(a.fieldNames()))
            {
                diffs.add(path + ": keys " + e.fieldNames() + " vs " + a.fieldNames());

                return;
            }

            for (String key : e.fieldNames())
            {
                diff(path + "." + key, e.getValue(key), a.getValue(key), diffs);
            }

            return;
        }

        if (expected instanceof JsonArray e && actual instanceof JsonArray a)
        {
            if (e.size() != a.size())
            {
                diffs.add(path + ": " + e.encode() + " vs " + a.encode());

                return;
            }

            for (int i = 0; i < e.size(); i++)
            {
                diff(path + "[" + i + "]", e.getValue(i), a.getValue(i), diffs);
            }

            return;
        }

        if (expected == null ? actual != null : !expected.equals(actual))
        {
            diffs.add(path + ": prototype " + expected + ", port " + actual);
        }
    }
}
