package com.pulse.calc.work;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 8.1: the workload rules of spec work/work-items and reference section 12 that the prototype has
 * no function for: the remaining-hours fallback chain, "no estimate", the drawer's date and time checks,
 * the reopen rate and staleness across a holiday.
 */
class WorkloadTest
{

    private static final JsonObject RULES = new JsonObject()
            .put("staleDays", 2).put("notStartedPct", 40).put("focus", 6)
            .put("under", 60).put("healthy", 85).put("over", 120).put("dayHours", 8);

    private static final LocalDate GANDHI_JAYANTI = LocalDate.parse("2026-10-02");

    /**
     * Weekdays except Gandhi Jayanti.
     */
    private static final Predicate<LocalDate> WORKING = day -> day.getDayOfWeek() != DayOfWeek.SATURDAY
            && day.getDayOfWeek() != DayOfWeek.SUNDAY && !day.equals(GANDHI_JAYANTI);

    @Test
    void remainingHoursFallBackFromJiraToOriginalMinusSpentToStoryPoints()
    {
        assertEquals(9, Workload.remainingHours(new JsonObject().put("remainingHours", 9).put("estimateHours", 16), 4), 1e-9);
        assertEquals(7, Workload.remainingHours(new JsonObject().put("estimateHours", 16).put("spentHours", 9), 4), 1e-9);
        assertEquals(0, Workload.remainingHours(new JsonObject().put("estimateHours", 8).put("spentHours", 12), 4), 1e-9);
        assertEquals(20, Workload.remainingHours(new JsonObject().put("storyPoints", 5), 4), 1e-9);
        assertEquals(0, Workload.remainingHours(new JsonObject(), 4), 1e-9);
    }

    @Test
    void noEstimateMeansNeitherAnEstimateNorStoryPoints()
    {
        assertTrue(Workload.noEstimate(new JsonObject()));
        assertTrue(Workload.noEstimate(new JsonObject().put("estimateHours", 0).put("storyPoints", 0)));
        assertFalse(Workload.noEstimate(new JsonObject().put("storyPoints", 3)));
        assertFalse(Workload.noEstimate(new JsonObject().put("estimateHours", 4)));
    }

    @Test
    void idleAcrossAHolidayIsNotStale()
    {
        // Last touched Thursday 1 Oct; Friday 2 Oct is a holiday, so on Monday 5 Oct only Monday counts.
        JsonObject issue = new JsonObject().put("key", "PLAT-1").put("status", "In Progress")
                .put("lastActivity", "2026-10-01T16:00").put("spentHours", 4).put("remainingHours", 4)
                .put("estimateHours", 8).put("startedOn", "2026-10-01");

        LocalDate monday = LocalDate.parse("2026-10-05");

        assertEquals(1, Workload.idleDays(LocalDate.parse("2026-10-01"), monday, WORKING));

        JsonArray flags = Workload.flags(issue, monday, RULES, 10, WORKING);

        assertFalse(flags.stream().anyMatch(f -> "Stale".equals(((JsonArray) f).getString(1))), flags.encode());

        // Two more working days and it is stale (more than 2 idle working days).
        assertTrue(Workload.flags(issue, LocalDate.parse("2026-10-07"), RULES, 10, WORKING).stream()
                .anyMatch(f -> "Stale".equals(((JsonArray) f).getString(1))));
    }

    @Test
    void drawerChecksFollowTheSpec()
    {
        // In progress for 6 working days at 6 h focus: expected 6 x 6 x 0.75 = 27 h; 5 h logged is 18.5%.
        JsonObject issue = new JsonObject().put("key", "PLAT-2").put("status", "In Progress")
                .put("lastActivity", "2026-09-28").put("startedOn", "2026-09-21").put("estimateHours", 4)
                .put("spentHours", 5).put("remainingHours", 3);

        JsonObject checks = Workload.checks(issue, LocalDate.parse("2026-09-28"), RULES, WORKING);

        assertEquals(6, checks.getInteger("workingDaysInProgress"));
        assertEquals(27, checks.getDouble("expectedHours"), 1e-9);
        assertEquals(5.0 / 27, checks.getDouble("loggedShare"), 1e-9);
        assertTrue(checks.getBoolean("lowLogged"));
        assertEquals(2.0, checks.getDouble("estimateDrift"), 1e-9);
        assertTrue(checks.getBoolean("estimateGrew"));
        // 6 working days in progress against an estimate of 4 h = 0.67 d: more than twice as long.
        assertTrue(checks.getBoolean("longerThanEstimate"));
        assertEquals(0.5, checks.getDouble("daysLeft"), 1e-9);
        assertFalse(checks.getBoolean("stale"));

        JsonObject unstarted = Workload.checks(new JsonObject().put("status", "To Do").put("lastActivity", "2026-09-20"),
                LocalDate.parse("2026-09-28"), RULES, WORKING);

        assertNull(unstarted.getValue("loggedShare"));
        assertNull(unstarted.getValue("estimateDrift"));
        assertFalse(unstarted.getBoolean("stale"), "To Do is judged as Not started, not as stale");
    }

    @Test
    void anOverloadedSprintIsCritical()
    {
        // Spec scenario: 10.3 days of work against 6 days of capacity.
        double utilization = Math.round(10.3 / 6 * 100);

        assertEquals("Critical", Workload.band(utilization, RULES).getString(1));
        assertEquals("At capacity", Workload.band(100, RULES).getString(1));
        assertEquals("Over", Workload.band(100.1, RULES).getString(1));
    }

    @Test
    void reopenRateIsReopenedDoneIssuesOverDoneIssues()
    {
        List<JsonObject> issues = List.of(
                new JsonObject().put("status", "Done").put("reopened", true),
                new JsonObject().put("status", "Done"),
                new JsonObject().put("status", "Done"),
                new JsonObject().put("status", "Done"),
                new JsonObject().put("status", "In Progress").put("reopened", true));

        assertEquals(25, SprintMetrics.reopenRate(issues));
        assertNull(SprintMetrics.reopenRate(List.of(new JsonObject().put("status", "To Do"))));
    }

    @Test
    void medianOfAnEvenCountIsTheMeanOfTheMiddleTwo()
    {
        assertEquals(2.5, SprintMetrics.median(List.of(4.0, 1.0, 2.0, 3.0)), 1e-9);
        assertEquals(0, SprintMetrics.median(List.of()), 1e-9);
    }
}
