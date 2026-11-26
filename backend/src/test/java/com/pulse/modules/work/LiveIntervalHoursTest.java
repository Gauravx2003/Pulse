package com.pulse.modules.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.work.EffortFormulas;
import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.platform.module.ModuleRegistry;
import com.pulse.platform.module.PulseModule;
import com.pulse.testsupport.TestApi;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Task 3.2 (change jira-derived-hours): hours from Jira work intervals reach the same per-person, per-day hours
 * as worklogs. In the closed Sprint 42, on a working day D on which Priya already logs seed hours, three live
 * issues hold intervals credited to her Jira account:
 *
 * <ul>
 *   <li>IV-1, Dev D 10:00-18:00 (8 h);</li>
 *   <li>IV-2, QA D 12:00-18:00, overlapping IV-1 (6 h of its own, nothing more for the day), and a cleared Dev
 *       interval (no start);</li>
 *   <li>IV-3, Dev D 10:00-18:00, but IV-3 has a Jira worklog, so time tracking wins and its interval counts
 *       nothing.</li>
 * </ul>
 *
 * <p>Hours logged, days worked and closed-period utilization are the seed's plus 8 h: the sum of both sources,
 * with the overlapping intervals merged, so no day gets 16 or 24 interval hours. On Effort, each issue taken
 * over keeps its own interval hours (IV-1 8 h, IV-2 6 h) and IV-3 its worklog hours for Priya (none).
 */
class LiveIntervalHoursTest
{

    private static final int PORT = 18_298;

    private static final String TODAY = "2026-09-28";

    private static final String SPRINT = "s:S42";

    private static TestApi api;

    private static String priya;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", TODAY).put("calc.closePeriods", false), productModules());

        priya = api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");
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
    void intervalHoursAddToWorklogHoursAndEffortItemsKeepTheirOwnHours() throws Exception
    {
        LocalDate day = workingDayWithSeedHours();

        JsonObject before = periodWork();

        assertNotNull(before);

        api.exec("INSERT INTO external_accounts (tool, tool_account_id, login, person_id) VALUES ('jira', 'priya.iv', 'priya.iv', '"
                + priya + "')");

        issue("IV-1");
        issue("IV-2");
        issue("IV-3");

        interval("IV-1|dev", "IV-1", "dev", day + "T10:00:00+05:30", day + "T18:00:00+05:30");
        interval("IV-2|qa", "IV-2", "qa", day + "T12:00:00+05:30", day + "T18:00:00+05:30");
        interval("IV-2|dev", "IV-2", "dev", null, null);
        interval("IV-3|dev", "IV-3", "dev", day + "T10:00:00+05:30", day + "T18:00:00+05:30");

        // IV-3 tracks time in Jira: a worklog by someone else makes the issue take worklogs only
        api.exec("INSERT INTO worklogs (issue_id, work_date, hours, source, external_id, synced_at, account_id)"
                + " SELECT id, '" + day + "', 2, 'jira', 'IV-3-wl', now(), 'someone.else' FROM issues WHERE key = 'IV-3'");

        for (String key : new String[] {"IV-1", "IV-2", "IV-3"})
        {
            takeover(key, day);
        }

        JsonObject after = periodWork();

        double logged = before.getDouble("logged") + 8;

        assertEquals(logged, after.getDouble("logged"), 0.001, "seed hours plus 8 interval hours, merged and capped");
        assertEquals(logged / 8, after.getDouble("daysWorked"), 0.001);
        assertEquals((int) Math.round(logged / (after.getInteger("work") * 8.0) * 100), after.getInteger("cover"));
        assertEquals(after.getInteger("cover"), ((Number) get(WorkloadFormulas.UTILIZATION, params())).intValue(),
                "closed-period utilization reads the same hours");

        JsonArray found = (JsonArray) get(EffortFormulas.DETECTED, params());

        assertEquals(8.0, hoursOf(found, "IV-1"), 0.001, "IV-1's own interval hours");
        assertEquals(6.0, hoursOf(found, "IV-2"), 0.001, "IV-2's own interval hours, before the merge with IV-1");
        assertEquals(0.0, hoursOf(found, "IV-3"), 0.001, "IV-3 tracks time in Jira: Priya has no worklog on it");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A weekday of Sprint 42 (7-18 Sep) on which Priya has seed worklog hours and is neither on leave nor on a visit.
     */
    private static LocalDate workingDayWithSeedHours() throws Exception
    {
        JsonArray leave = (JsonArray) get("people.leaveDays", new JsonObject().put("person", priya));

        JsonArray visits = (JsonArray) get("people.visitDays", new JsonObject().put("person", priya));

        JsonArray days = api.sql().queryList("SELECT DISTINCT work_date::text AS day FROM worklogs WHERE person_id = '" + priya
                + "'::uuid AND work_date BETWEEN '2026-09-08' AND '2026-09-18' AND hours > 0 ORDER BY 1").toCompletionStage()
                .toCompletableFuture().get();

        for (Object o : days)
        {
            LocalDate day = LocalDate.parse(((JsonObject) o).getString("day"));

            boolean weekday = day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;

            boolean onVisit = visits != null && visits.stream().map(JsonObject.class::cast).anyMatch(v ->
                    !day.isBefore(LocalDate.parse(v.getString("from"))) && !day.isAfter(LocalDate.parse(v.getString("to"))));

            if (weekday && (leave == null || !leave.contains(day.toString())) && !onVisit)
            {
                return day;
            }
        }

        throw new AssertionError("no working day with seed hours in Sprint 42: " + days.encode());
    }

    private static JsonObject periodWork() throws Exception
    {
        return (JsonObject) get(WorkloadFormulas.PERIOD_WORK, params());
    }

    private static JsonObject params()
    {
        return new JsonObject().put("person", priya).put("period", SPRINT);
    }

    /**
     * A formula's value from a fresh {@link CalcService}, so nothing is cached.
     */
    private static Object get(String formula, JsonObject params) throws Exception
    {
        CalcService calc = new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), TODAY, false), FormulaRegistry.discover());

        return ResultGuard.unguard(TestApi.await(calc.get(formula, params)));
    }

    private static double hoursOf(JsonArray items, String key)
    {
        return items.stream().map(JsonObject.class::cast).filter(i -> key.equals(i.getString("ref"))).findFirst()
                .orElseThrow(() -> new AssertionError(key + " not detected: " + items.encode())).getDouble("hours");
    }

    private static void issue(String key) throws Exception
    {
        api.exec("INSERT INTO issues (key, summary, status, source, external_id, synced_at, assignee_account_id) VALUES ('" + key
                + "', 'Live issue " + key + "', 'In Progress', 'jira', '" + key + "', now(), 'priya.iv')");
    }

    private static void interval(String externalId, String key, String kind, String start, String end) throws Exception
    {
        api.exec("INSERT INTO issue_work_intervals (issue_id, kind, starts_at, ends_at, end_source, account_id, source,"
                + " external_id, synced_at) SELECT id, '" + kind + "', " + quoted(start) + ", " + quoted(end) + ", "
                + (start == null ? "NULL" : "'field'") + ", 'priya.iv', 'jira', '" + externalId + "', now() FROM issues WHERE key = '"
                + key + "'");
    }

    /** The assignee changed to Priya's Jira account on {@code day} at 09:00, after the sprint started. */
    private static void takeover(String key, LocalDate day) throws Exception
    {
        api.exec("INSERT INTO issue_events (issue_id, occurred_at, kind, field, from_value, to_value, actor_name, summary, source,"
                + " external_id, synced_at, actor_account_id) SELECT id, '" + day + " 09:00+05:30', 'assignee_change', 'assignee',"
                + " 'arjun.m', 'priya.iv', 'Rahul Verma', 'Assignee → Priya', 'jira', '" + key + ":takeover', now(), 'rahul.v'"
                + " FROM issues WHERE key = '" + key + "'");
    }

    private static String quoted(String value)
    {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static String[] productModules()
    {
        return ModuleRegistry.discover().modules().stream()
                .filter(m -> Files.isDirectory(Path.of("modules", m.id())))
                .map(PulseModule::id)
                .toArray(String[]::new);
    }
}
