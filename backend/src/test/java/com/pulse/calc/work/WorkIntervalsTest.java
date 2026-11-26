package com.pulse.calc.work;

import com.pulse.calc.core.RuleCatalogue;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3.1 (change jira-derived-hours): {@link WorkIntervals} against every scenario of spec
 * work/effort-extra-mile "Hours from work intervals", plus a window change. The week is Monday 21 Sep 2026 to
 * Friday 25 Sep 2026; Monday 28 Sep is a company holiday in the weekend-and-holiday case. Times are Asia/Kolkata.
 */
class WorkIntervalsTest
{

    private static final LocalDate MON = LocalDate.parse("2026-09-21");

    private static final LocalDate TUE = MON.plusDays(1);

    private static final LocalDate WED = MON.plusDays(2);

    private static final LocalDate FRI = MON.plusDays(4);

    private static final LocalDate NEXT_MON = MON.plusDays(7);

    /** Weekdays are working days; nothing else is off. */
    private static final Predicate<LocalDate> WEEKDAYS = day -> day.getDayOfWeek() != DayOfWeek.SATURDAY
            && day.getDayOfWeek() != DayOfWeek.SUNDAY;

    /** Long after every interval: closed intervals are not clipped by now. */
    private static final LocalDate LATER = LocalDate.parse("2026-11-26");

    @Test
    void intervalOverTwoWorkingDays()
    {
        JsonObject hours = hours(list(interval("2026-09-21T14:00+05:30", "2026-09-22T12:00+05:30")), LATER, at(LATER, "12:00"));

        assertEquals(new JsonObject().put(MON.toString(), 4.0).put(TUE.toString(), 2.0), hours);
    }

    @Test
    void weekendAndHolidaySkipped()
    {
        Set<LocalDate> holidays = Set.of(NEXT_MON);

        JsonObject hours = WorkIntervals.hours(list(interval("2026-09-25T10:00+05:30", "2026-09-28T18:00+05:30")),
                MON, NEXT_MON, day -> WEEKDAYS.test(day) && !holidays.contains(day), defaults(), LATER, at(LATER, "12:00"));

        assertEquals(new JsonObject().put(FRI.toString(), 8.0), hours, "only Friday counts, with 8 hours");
    }

    @Test
    void leaveAndVisitDaysAreNotWorkingDays()
    {
        // the loader passes the person's working days: a leave or visit day is not one
        JsonObject hours = WorkIntervals.hours(list(interval("2026-09-21T10:00+05:30", "2026-09-23T18:00+05:30")),
                MON, FRI, day -> WEEKDAYS.test(day) && !day.equals(TUE), defaults(), LATER, at(LATER, "12:00"));

        assertEquals(new JsonObject().put(MON.toString(), 8.0).put(WED.toString(), 8.0), hours);
    }

    @Test
    void openIntervalCountsUpToNowAndNeverAfterToday()
    {
        // started yesterday (Tuesday) 10:00, no end; today (Wednesday) at 13:00 the window has 3 hours behind it
        JsonObject hours = hours(list(interval("2026-09-22T10:00+05:30", null)), WED, at(WED, "13:00"));

        assertEquals(new JsonObject().put(TUE.toString(), 8.0).put(WED.toString(), 3.0), hours);

        // a "now" past today (a pinned today) still stops at the end of today
        JsonObject pinned = hours(list(interval("2026-09-22T10:00+05:30", null)), WED, at(FRI, "13:00"));

        assertEquals(new JsonObject().put(TUE.toString(), 8.0).put(WED.toString(), 8.0), pinned);

        // an End set in the future is clipped the same way
        JsonObject future = hours(list(interval("2026-09-22T10:00+05:30", "2026-09-25T18:00+05:30")), WED, at(WED, "13:00"));

        assertEquals(new JsonObject().put(TUE.toString(), 8.0).put(WED.toString(), 3.0), future);
    }

    @Test
    void openIntervalOfAResolvedIssueEndsAtItsResolution()
    {
        // the connector stores the resolution time (yesterday 12:00) as the end of an interval with no End field
        JsonObject hours = hours(list(interval("2026-09-21T10:00+05:30", "2026-09-22T12:00+05:30")), WED, at(WED, "15:00"));

        assertEquals(new JsonObject().put(MON.toString(), 8.0).put(TUE.toString(), 2.0), hours, "no hours today");
    }

    @Test
    void devAndQaOverlapForOnePersonCountEachHourOnceAndTheDayIsCapped()
    {
        JsonArray overlap = list(interval("2026-09-21T10:00+05:30", "2026-09-21T16:00+05:30"),
                interval("2026-09-21T12:00+05:30", "2026-09-21T18:00+05:30"));

        assertEquals(new JsonObject().put(MON.toString(), 8.0), hours(overlap, LATER, at(LATER, "12:00")),
                "10:00-18:00 once, not 6 + 6");

        // a window longer than dayHours still gives at most dayHours
        JsonObject wide = defaults().put("workday", new JsonObject().put("start", "08:00").put("end", "20:00"));

        JsonObject capped = WorkIntervals.hours(list(interval("2026-09-21T08:00+05:30", "2026-09-21T20:00+05:30")), MON, FRI,
                WEEKDAYS, wide, LATER, at(LATER, "12:00"));

        assertEquals(new JsonObject().put(MON.toString(), 8.0), capped);

        // two pieces that do not overlap add up
        JsonArray apart = list(interval("2026-09-21T10:00+05:30", "2026-09-21T11:00+05:30"),
                interval("2026-09-21T15:00+05:30", "2026-09-21T17:30+05:30"));

        assertEquals(new JsonObject().put(MON.toString(), 3.5), hours(apart, LATER, at(LATER, "12:00")));
    }

    @Test
    void invalidAndClearedIntervalsCountNothing()
    {
        JsonArray rows = list(interval("2026-09-22T12:00+05:30", "2026-09-21T14:00+05:30"),
                interval(null, "2026-09-22T12:00+05:30"),
                interval("2026-09-21T19:00+05:30", "2026-09-21T23:00+05:30"),
                interval("2026-09-26T10:00+05:30", "2026-09-27T18:00+05:30"));

        assertTrue(hours(rows, LATER, at(LATER, "12:00")).isEmpty(),
                "end before start, no start, outside the window, a weekend: nothing");
    }

    @Test
    void aWindowChangeChangesTheHours()
    {
        JsonObject later = defaults().put("workday", new JsonObject().put("start", "09:30").put("end", "18:30"));

        JsonObject hours = WorkIntervals.hours(list(interval("2026-09-21T14:00+05:30", "2026-09-22T12:00+05:30")), MON, FRI,
                WEEKDAYS, later, LATER, at(LATER, "12:00"));

        assertEquals(new JsonObject().put(MON.toString(), 4.5).put(TUE.toString(), 2.5), hours);
    }

    @Test
    void onlyDaysOfThePeriodAreReturnedAndOffsetsAreReadInKolkata()
    {
        // 08:30Z is 14:00 in Kolkata; the period is Tuesday only
        JsonObject hours = WorkIntervals.hours(list(interval("2026-09-21T08:30:00Z", "2026-09-22T06:30:00Z")), TUE, TUE,
                WEEKDAYS, defaults(), LATER, at(LATER, "12:00"));

        assertEquals(new JsonObject().put(TUE.toString(), 2.0), hours);
        assertEquals(2.0, WorkIntervals.total(hours));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject hours(JsonArray intervals, LocalDate today, Instant now)
    {
        return WorkIntervals.hours(intervals, MON, NEXT_MON, WEEKDAYS, defaults(), today, now);
    }

    private static JsonObject defaults()
    {
        return RuleCatalogue.defaults();
    }

    private static JsonObject interval(String start, String end)
    {
        return new JsonObject().put("startsAt", start).put("endsAt", end);
    }

    private static JsonArray list(JsonObject... intervals)
    {
        JsonArray out = new JsonArray();

        for (JsonObject interval : intervals)
        {
            out.add(interval);
        }

        return out;
    }

    private static Instant at(LocalDate day, String time)
    {
        return OffsetDateTime.parse(day + "T" + time + "+05:30").toInstant();
    }
}
