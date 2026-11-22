package com.pulse.calc.work;

import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 11.1: {@link Effort} against spec work/effort-extra-mile and against the prototype oracle: each
 * seeded item's points ({@code itemPts}), the effort totals of {@code monthStats} for every person and
 * every sprint and month, the requester ranking of {@code pEffortTeam}, all under the default and an
 * altered rule set; and the detection evidence text, which must read like the seeded items'.
 */
class EffortTest
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

    // ------------------------------------------------------------------ oracle

    @Test
    void everyItemsPointsMatchItemPts()
    {
        JsonObject rules = rules();

        JsonArray extra = (JsonArray) oracle.eval("EXTRA");

        JsonObject visits = visitPoints();

        for (int i = 0; i < extra.size(); i++)
        {
            JsonObject item = item(extra.getJsonObject(i));

            oracle.compare("itemPts", Oracle.args(Oracle.js("EXTRA[" + i + "]")), 2, in -> Effort.points(item, rules, visits));
        }

        assertTrue(extra.size() > 30, "every seeded item: " + extra.size());
    }

    @Test
    void totalsMatchMonthStatsForEveryPersonAndPeriod()
    {
        compareTotals(rules());
    }

    @Test
    void requestersMatchTheTeamViewForEveryPeriod()
    {
        String ids = ((JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})")).encode();

        for (String period : periods())
        {
            // The ranking of pEffortTeam() (app.js ~501-503) over everyone.
            JsonArray prototype = (JsonArray) oracle.eval("(function(ids,per){var all=[];ids.forEach(function(id){"
                    + "all=all.concat(monthStats(person(id),per).items)});var src={};all.forEach(function(it){"
                    + "if(it.kind==='untracked'||it.kind==='reviews')return;var k=it.by;src[k]=src[k]||{n:0,h:0};src[k].n++;"
                    + "src[k].h+=it.hours});return Object.keys(src).sort(function(a,b){return src[b].h-src[a].h})"
                    + ".map(function(k){return {by:k,items:src[k].n,hours:src[k].h}})})(" + ids + ",perOf('" + period + "'))");

            List<JsonObject> all = new ArrayList<>();

            for (Object id : (JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})"))
            {
                all.addAll(items("monthStats(person('" + id + "'),perOf('" + period + "')).items"));
            }

            assertSame("requesters " + period, prototype, Effort.requesters(all));
        }
    }

    @Test
    void totalsStillMatchUnderAlteredRules()
    {
        oracle.eval("S.rules.monthCap = 3; S.rules.pts.midsprint = 2; S.rules.pts.reviews = 1; S.rules.visitCap = 2");

        JsonObject rules = rules();

        assertEquals(3, rules.getInteger("monthCap"));

        compareTotals(rules);
    }

    // ------------------------------------------------------------------ spec scenarios

    @Test
    void confirmedPointsAboveTheMonthlyCapDoNotScore()
    {
        JsonObject rules = new JsonObject().put("monthCap", 6).put("visitCap", 8).put("pts", new JsonObject().put("incident", 2));

        List<JsonObject> items = new ArrayList<>();

        for (int i = 0; i < 4; i++)
        {
            items.add(new JsonObject().put("kind", "incident").put("ref", "INC-" + i).put("isDone", true).put("state", "confirmed")
                    .put("hours", 2));
        }

        JsonObject totals = Effort.totals(items, 40, rules, null);

        assertEquals(6.0, totals.getDouble("ptsConf"), 1e-9, "8 confirmed points, capped at 6");
        assertTrue(totals.getBoolean("capped"));
    }

    @Test
    void aHighUnplannedShareIsAPlanningSignal()
    {
        JsonObject rules = new JsonObject().put("unplannedAlert", 30);

        List<JsonObject> items = List.of(new JsonObject().put("kind", "midsprint").put("hours", 14).put("state", "pending"));

        JsonObject totals = Effort.totals(items, 40, rules, null);

        assertEquals(35, totals.getInteger("share"));
        assertTrue(Effort.planningSignal(totals.getInteger("share"), rules));
        assertFalse(Effort.planningSignal(29, rules));
        assertEquals("This is a planning signal for the manager, not a performance problem", Effort.PLANNING_MESSAGE);
    }

    @Test
    void aMidSprintAdditionEarnsOnePointWithItsChangelogEvidence()
    {
        JsonObject issue = new JsonObject().put("key", "PLAT-2271").put("summary", "Investigate tenant 42 dropped traps")
                .put("status", "Done");

        JsonObject item = Effort.midSprint(issue, "Sprint 43", LocalDate.parse("2026-09-21"),
                Instant.parse("2026-09-22T05:34:00Z"), "Rahul Nair · manager", 8);

        assertEquals(seededNote("x1"), item.getString("detectionNote"));
        assertEquals(List.of("Adaptability", "Agility in Execution"), item.getJsonArray("valuesCredited").getList());
        assertEquals(1.0, Effort.points(item, rules(), null), 1e-9);
    }

    @Test
    void detectionEvidenceReadsLikeTheSeededItems()
    {
        JsonObject takeover = Effort.takeover(new JsonObject().put("key", "PLAT-2190").put("summary", "Collector config reload")
                .put("status", "Done"), "Kiran Rao", "Priya Shah", Instant.parse("2026-09-10T06:00:00Z"), "Rahul Nair · manager", 7);

        assertEquals(seededNote("x4"), takeover.getString("detectionNote"));
        assertEquals("Collector config reload (from Kiran Rao)", takeover.getString("title"));

        JsonObject untracked = Effort.untracked(new JsonObject().put("number", "#805").put("title", "CI cache script").put("tool", "GitHub"),
                LocalDate.parse("2026-09-17"), 4);

        assertEquals(seededNote("x5"), untracked.getString("detectionNote"));
        assertEquals("PR #805", untracked.getString("ref"));

        assertEquals(seededNote("x6"), Effort.reviews(14, 3, LocalDate.parse("2026-09-30"), 5).getString("detectionNote"));

        assertEquals(seededNote("x8"), Effort.help(new JsonObject().put("key", "PLAT-2141").put("summary", "Trap filter bug"),
                "Priya", "Neha", 3, LocalDate.parse("2026-08-19"), 3).getString("detectionNote"));

        JsonObject reviews = Effort.reviews(14, 3, LocalDate.parse("2026-09-30"), 5);

        assertEquals(1.0, Effort.points(reviews, new JsonObject().put("pts", new JsonObject().put("reviews", 0.5)), null), 1e-9,
                "0.5 per 5 reviews: 14 reviews earn 1 point");
    }

    // ------------------------------------------------------------------ fixtures

    private static void compareTotals(JsonObject rules)
    {
        JsonObject visits = visitPoints();

        int compared = 0;

        for (Object id : (JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})"))
        {
            for (String period : periods())
            {
                String stats = "monthStats(person('" + id + "'),perOf('" + period + "'))";

                Object prototype = oracle.eval("(function(r){return {unplanned:r.unplanned,untracked:r.untracked,reviews:r.reviews,"
                        + "visitH:r.visitH,planned:r.planned,ptsConf:r.ptsConf,ptsPend:r.ptsPend,capped:r.capped,"
                        + "visitPts:r.visitPts,share:r.share}})(" + stats + ")");

                double logged = ((Number) oracle.eval(stats + ".logged")).doubleValue();

                assertSame(id + " " + period, prototype, Effort.totals(items(stats + ".items"), logged, rules, visits));

                compared++;
            }
        }

        assertTrue(compared > 100, "every person and period: " + compared);
    }

    private static List<JsonObject> items(String expression)
    {
        List<JsonObject> out = new ArrayList<>();

        for (Object x : (JsonArray) oracle.eval(expression))
        {
            out.add(item((JsonObject) x));
        }

        return out;
    }

    /**
     * A prototype EXTRA item in the shape of the extra_items rows.
     */
    private static JsonObject item(JsonObject x)
    {
        return new JsonObject()
                .put("id", x.getString("id"))
                .put("kind", x.getString("kind"))
                .put("ref", x.getString("ref"))
                .put("title", x.getString("title"))
                .put("requestedBy", x.getString("by"))
                .put("detectionNote", x.getString("how"))
                .put("occurredOn", x.getString("date"))
                .put("hours", x.getValue("hours"))
                .put("isDone", x.getBoolean("done", false))
                .put("valuesCredited", x.getJsonArray("values"))
                .put("state", x.getString("state"));
    }

    /**
     * Track 3's visit points, as the prototype gives them: visit item ref to points.
     */
    private static JsonObject visitPoints()
    {
        return (JsonObject) oracle.eval("(function(){var o={};EXTRA.forEach(function(it){if(it.kind==='visit')o[it.ref]=itemPts(it)});"
                + "return o})()");
    }

    private static String seededNote(String id)
    {
        return (String) oracle.eval("EXTRA.filter(function(it){return it.id==='" + id + "'})[0].how");
    }

    private static JsonObject rules()
    {
        return (JsonObject) oracle.eval("S.rules");
    }

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
