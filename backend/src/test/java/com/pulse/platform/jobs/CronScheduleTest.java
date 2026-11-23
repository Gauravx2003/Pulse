package com.pulse.platform.jobs;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 5.5: the five-field cron of the scheduled jobs. */
class CronScheduleTest
{
    private static LocalDateTime at(String iso)
    {
        return LocalDateTime.parse(iso);
    }

    @Test
    void fridayAtFourMatchesOnlyThatMinute()
    {
        CronSchedule friday = CronSchedule.parse("0 16 * * 5");

        assertTrue(friday.matches(at("2026-11-27T16:00:00")));      // a Friday
        assertTrue(friday.matches(at("2026-11-27T16:00:59")));      // seconds ignored
        assertFalse(friday.matches(at("2026-11-27T16:01:00")));
        assertFalse(friday.matches(at("2026-11-26T16:00:00")));     // Thursday
        assertEquals(at("2026-11-27T16:00:00"), friday.next(at("2026-11-23T10:00:00")));
        assertEquals(at("2026-12-04T16:00:00"), friday.next(at("2026-11-27T16:00:00")));
    }

    @Test
    void lastDayOfTheMonthFollowsMonthLength()
    {
        CronSchedule monthEnd = CronSchedule.parse("0 10 L * *");

        assertTrue(monthEnd.matches(at("2026-11-30T10:00:00")));
        assertFalse(monthEnd.matches(at("2026-11-29T10:00:00")));
        assertTrue(monthEnd.matches(at("2028-02-29T10:00:00")));    // leap year
        assertTrue(monthEnd.matches(at("2027-02-28T10:00:00")));
        assertEquals(at("2026-12-31T10:00:00"), monthEnd.next(at("2026-11-30T10:00:00")));
    }

    @Test
    void sundayIsZeroOrSeven()
    {
        LocalDateTime sunday = at("2026-11-29T09:00:00");

        assertTrue(CronSchedule.parse("0 9 * * 0").matches(sunday));
        assertTrue(CronSchedule.parse("0 9 * * 7").matches(sunday));
        assertFalse(CronSchedule.parse("0 9 * * 1-5").matches(sunday));
    }

    @Test
    void listsRangesAndSteps()
    {
        CronSchedule c = CronSchedule.parse("*/15 8-10,14 * * *");

        assertTrue(c.matches(at("2026-11-23T08:45:00")));
        assertTrue(c.matches(at("2026-11-23T14:30:00")));
        assertFalse(c.matches(at("2026-11-23T11:00:00")));
        assertFalse(c.matches(at("2026-11-23T08:10:00")));
    }

    @Test
    void bothDayFieldsRestrictedMatchEither()
    {
        CronSchedule c = CronSchedule.parse("0 0 1 * 1");

        assertTrue(c.matches(at("2026-12-01T00:00:00")));           // the 1st, a Tuesday
        assertTrue(c.matches(at("2026-11-23T00:00:00")));           // a Monday
        assertFalse(c.matches(at("2026-11-24T00:00:00")));
    }

    @Test
    void neverDueGivesNoNextRun()
    {
        assertEquals(null, CronSchedule.parse("0 0 31 2 *").next(at("2026-01-01T00:00:00")));
    }

    @Test
    void malformedExpressionsAreRefused()
    {
        for (String bad : new String[] {null, "", "0 16 * *", "60 * * * *", "* 24 * * *", "* * 0 * *", "* * * 13 *",
                "* * * * 8", "*/0 * * * *", "5-1 * * * *", "a * * * *", "0 0 L L *"})
        {
            assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse(bad), String.valueOf(bad));
        }
    }
}
