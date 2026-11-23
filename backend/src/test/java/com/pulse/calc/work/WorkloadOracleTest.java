package com.pulse.calc.work;

import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 8.1: the workload ports of {@code calc/work} against the prototype oracle for every seeded
 * person, every sprint (S31-S43) and every month (Apr-Sep 2026): {@code band}, {@code sprintElapsedPct},
 * {@code flags}, {@code metrics}, {@code monthStats} (time), {@code perMetrics} and {@code qStats}.
 * Each port gets the prototype's own fixture (its tasks, day-by-day {@code dayInfo}, leave, visits,
 * bugs and tickets), so the comparison checks the formulas, not the data.
 */
class WorkloadOracleTest
{

    private static final LocalDate TODAY = LocalDate.parse("2026-09-28");

    private static final String RANGE = "'2026-03-01','2026-12-31'";

    private static Oracle oracle;

    private static JsonObject rules;

    private static List<String> people;

    @BeforeAll
    static void start() throws Exception
    {
        oracle = Oracle.start();

        rules = (JsonObject) oracle.eval("S.rules");

        people = strings((JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})"));
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

    // ------------------------------------------------------------------ live sprint

    @Test
    void bandMatchesAcrossTheEdges()
    {
        for (double utilization : new double[] {0, 59, 60, 84, 85, 99, 100, 100.1, 101, 120, 121, 999})
        {
            oracle.compare("band", Oracle.args(utilization), 0, in -> Workload.band(in.getDouble(0), rules));
        }
    }

    @Test
    void sprintElapsedMatches()
    {
        JsonObject sprint = (JsonObject) oracle.eval("SPRINT");

        int port = Workload.sprintElapsedPct(LocalDate.parse(sprint.getString("start")), LocalDate.parse(sprint.getString("end")),
                TODAY, company());

        assertEquals(((Number) oracle.eval("sprintElapsedPct()")).intValue(), port);
    }

    @Test
    void flagsMatchForEveryLiveIssueOfEveryPerson()
    {
        int elapsed = ((Number) oracle.eval("sprintElapsedPct()")).intValue();

        int compared = 0;

        for (String id : people)
        {
            Predicate<LocalDate> working = working(id);

            JsonArray tasks = (JsonArray) oracle.eval("person('" + id + "').tasks");

            for (int i = 0; i < tasks.size(); i++)
            {
                JsonArray prototype = (JsonArray) oracle.eval("flags(person('" + id + "').tasks[" + i + "], person('" + id + "'))");

                JsonArray port = Workload.flags(issue(tasks.getJsonObject(i)), TODAY, rules, elapsed, working);

                assertEquals(prototype, port, id + " " + tasks.getJsonObject(i).getString("key"));

                compared++;
            }
        }

        assertTrue(compared > 20, "every live issue of every person: " + compared);
    }

    @Test
    void liveMetricsMatchForEveryPerson()
    {
        int elapsed = ((Number) oracle.eval("sprintElapsedPct()")).intValue();

        LocalDate workEnd = LocalDate.parse(((JsonObject) oracle.eval("SPRINT")).getString("end"));

        for (String id : people)
        {
            Object prototype = oracle.eval("(function(m){var k=function(t){return t.key};"
                    + "return {open:m.open.map(k),rem:m.rem,work:m.work,cap:m.cap,util:m.util,band:m.band,"
                    + "stale:m.stale.map(k),sp:m.sp,done:m.done.map(k),oldest:m.oldest}})(metrics(person('" + id + "')))");

            List<JsonObject> issues = new ArrayList<>();

            for (Object task : (JsonArray) oracle.eval("person('" + id + "').tasks"))
            {
                issues.add(issue((JsonObject) task));
            }

            Object port = Workload.live(issues, TODAY, workEnd, rules, elapsed, working(id), 0);

            assertSame(id, prototype, port, 2);
        }
    }

    // ------------------------------------------------------------------ periods

    @Test
    void periodWorkMatchesMonthStatsForEveryPersonSprintAndMonth()
    {
        int compared = 0;

        for (String id : people)
        {
            for (String period : periods())
            {
                Object prototype = oracle.eval("(function(r){return {work:r.work,workAll:r.workAll,logged:r.logged,"
                        + "active:r.active,codeOnly:r.codeOnly,leave:r.leave,weekend:r.weekend,daysWorked:r.daysWorked,"
                        + "cover:r.cover}})(monthStats(person('" + id + "'),perOf('" + period + "')))");

                assertSame(id + " " + period, prototype, PeriodWork.stats(days(id, period), TODAY, rules), 2);

                compared++;
            }
        }

        assertTrue(compared > 100, "every person, sprint and month: " + compared);
    }

    @Test
    void sprintMetricsMatchPerMetricsForEveryPersonSprintAndMonth()
    {
        for (String id : people)
        {
            for (String period : periods())
            {
                Object prototype = oracle.eval("(function(m){var k=function(x){return x.key};"
                        + "return {com:m.com,dsp:m.dsp,commit:m.commit,carry:m.carry.map(k),bugs:m.bugs.map(k),"
                        + "open:m.open.map(k),cyc:m.cyc,logged:m.logged,work:m.work,cover:m.cover,est:m.est,stale:m.stale}})"
                        + "(perMetrics(person('" + id + "'),perOf('" + period + "')))");

                List<JsonObject> issues = new ArrayList<>();

                for (Object item : (JsonArray) oracle.eval("issuesIn(person('" + id + "'),perOf('" + period + "'))"))
                {
                    JsonObject x = (JsonObject) item;

                    issues.add(new JsonObject()
                            .put("key", x.getString("key"))
                            .put("issueType", x.getString("type"))
                            .put("status", x.getString("status"))
                            .put("storyPoints", x.getValue("sp"))
                            .put("estimateHours", x.getValue("orig"))
                            .put("spentHours", x.getValue("spent"))
                            .put("cycle", x.getValue("cycle"))
                            .put("carriedOver", "Carried over".equals(x.getString("status")))
                            .put("wasStale", x.getBoolean("stale", false))
                            .put("live", x.getBoolean("live", false)));
                }

                JsonObject port = SprintMetrics.of(issues, PeriodWork.stats(days(id, period), TODAY, rules));

                assertSame(id + " " + period, prototype, port, 2);
            }
        }
    }

    @Test
    void cycleTimeMatchesTheHistory()
    {
        JsonArray history = (JsonArray) oracle.eval("HIST.filter(function(h){return h.resolved})");

        for (Object item : history)
        {
            JsonObject h = (JsonObject) item;

            Integer port = SprintMetrics.cycleDays(LocalDate.parse(h.getString("started")), LocalDate.parse(h.getString("resolved")),
                    company());

            assertEquals(h.getInteger("cycle"), port, h.getString("key"));
        }

        assertTrue(history.size() > 100, "every resolved history issue: " + history.size());
    }

    @Test
    void bugStatsMatchQStatsForEveryPerson()
    {
        List<JsonObject> bugs = new ArrayList<>();

        for (Object item : (JsonArray) oracle.eval("BUGS"))
        {
            JsonObject b = (JsonObject) item;

            bugs.add(new JsonObject()
                    .put("key", b.getString("key"))
                    .put("originPersonId", b.getString("origin"))
                    .put("status", b.getString("status"))
                    .put("severity", b.getString("sev"))
                    .put("environment", b.getString("env"))
                    .put("reportedOn", b.getString("created"))
                    .put("fixedById", b.getString("fixedBy"))
                    .put("proactive", b.getBoolean("proactive", false)));
        }

        List<JsonObject> tickets = new ArrayList<>();

        for (Object item : (JsonArray) oracle.eval("SUPPORT"))
        {
            JsonObject t = (JsonObject) item;

            tickets.add(new JsonObject()
                    .put("key", t.getString("key"))
                    .put("personId", t.getString("pid"))
                    .put("openedOn", t.getString("date"))
                    .put("hours", t.getValue("hours"))
                    .put("csat", t.getValue("csat")));
        }

        JsonObject sprint = (JsonObject) oracle.eval("perOf('s:S43')");

        for (String id : people)
        {
            Object prototype = oracle.eval("(function(q){return {mine:q.mine.length,open:q.open.length,high:q.high,"
                    + "q3:q.q3.length,prod:q.prod,fixedOthers:q.fixedOthers.length,proactive:q.proactive.length,"
                    + "sup:q.sup.length,supM:q.supM.length,supH:q.supH,csat:q.csat}})(qStats('" + id + "'))");

            JsonObject port = BugStats.of(id, bugs, tickets, LocalDate.parse("2026-07-01"),
                    LocalDate.parse(sprint.getString("from")), LocalDate.parse(sprint.getString("to")));

            assertSame(id, prototype, port, 2);
        }
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Every sprint and every month of the seed.
     */
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

    /**
     * The prototype's facts for each day of a period, as {@link PeriodWork} reads them.
     */
    private static JsonArray days(String id, String period)
    {
        return (JsonArray) oracle.eval("(function(p,per){return days(per.from,per.to).map(function(s){"
                + "var x=dayInfo(p,s);return {date:s,weekday:d(s).getDay()%6!==0,holiday:!!HOLIDAYS[s],"
                + "leave:onLeave(p,s),visit:!!visitOn(p.id,s),beforeJoining:!!x.pre,hours:x.h,commits:!!x.gh}})})"
                + "(person('" + id + "'),perOf('" + period + "'))");
    }

    /**
     * A prototype task in the shape of {@code IssueRepository} rows.
     */
    private static JsonObject issue(JsonObject task)
    {
        return new JsonObject()
                .put("key", task.getString("key"))
                .put("status", task.getString("status"))
                .put("storyPoints", task.getValue("sp"))
                .put("estimateHours", task.getValue("orig"))
                .put("spentHours", task.getValue("spent"))
                .put("remainingHours", task.getValue("rem"))
                .put("dueOn", task.getString("due"))
                .put("startedOn", task.getString("started"))
                .put("lastActivity", task.getString("last"));
    }

    /**
     * The person's working days (the prototype's {@code workBetween} with the person).
     */
    private static Predicate<LocalDate> working(String id)
    {
        Set<LocalDate> days = dates((JsonArray) oracle.eval("workBetween(" + RANGE + ",person('" + id + "'))"));

        return days::contains;
    }

    private static Predicate<LocalDate> company()
    {
        Set<LocalDate> days = dates((JsonArray) oracle.eval("workBetween(" + RANGE + ")"));

        return days::contains;
    }

    private static Set<LocalDate> dates(JsonArray values)
    {
        Set<LocalDate> out = new HashSet<>();

        for (Object value : values)
        {
            out.add(LocalDate.parse((String) value));
        }

        return out;
    }

    private static List<String> strings(JsonArray values)
    {
        List<String> out = new ArrayList<>();

        values.forEach(v -> out.add(String.valueOf(v)));

        return out;
    }

    /**
     * Fails unless both values are equal member by member, numbers as shown with {@code decimals}.
     */
    private static void assertSame(String where, Object prototype, Object port, int decimals)
    {
        List<String> diffs = new ArrayList<>();

        diff("$", prototype, port, decimals, diffs);

        if (!diffs.isEmpty())
        {
            fail(where + ": " + String.join("; ", diffs));
        }
    }

    private static void diff(String path, Object expected, Object actual, int decimals, List<String> diffs)
    {
        if (expected instanceof Number e && actual instanceof Number a)
        {
            if (!Oracle.display(e, decimals).equals(Oracle.display(a, decimals)))
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
                diff(path + "." + key, e.getValue(key), a.getValue(key), decimals, diffs);
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
                diff(path + "[" + i + "]", e.getValue(i), a.getValue(i), decimals, diffs);
            }

            return;
        }

        if (expected == null ? actual != null : !expected.equals(actual))
        {
            diffs.add(path + ": prototype " + expected + ", port " + actual);
        }
    }
}
