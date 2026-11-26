package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4.1: one test (or more) per scenario of spec platform/period-calendar, on the demo seed's
 * calendar (S110__demo_org.sql: sprints S31–S43, releases, Gandhi Jayanti on 2 Oct).
 */
class PeriodCalendarTest
{
    private static final LocalDate TODAY = LocalDate.parse("2026-09-28");

    private static final JsonArray SPRINTS = new JsonArray()
            .add(sprint("S43", 43, "2026-09-21", "2026-10-09", "2026-10-11"))
            .add(sprint("S42", 42, "2026-09-07", "2026-09-18", "2026-09-20"))
            .add(sprint("S41", 41, "2026-08-24", "2026-09-04", "2026-09-06"))
            .add(sprint("S31", 31, "2026-04-06", "2026-04-17", "2026-04-19"))
            .add(sprint("S44", 44, "2026-10-12", "2026-10-23", "2026-10-25"));

    private static final JsonArray RELEASES = new JsonArray()
            .add(new JsonObject().put("key", "8.2").put("name", "NMS 8.2").put("startsOn", "2026-07-13")
                    .put("endsOn", "2026-09-20").put("releaseDate", "2026-09-18").put("status", "Released"))
            .add(new JsonObject().put("key", "8.3").put("name", "NMS 8.3").put("startsOn", "2026-09-21")
                    .put("endsOn", "2026-11-15").put("releaseDate", "2026-11-13").put("status", "In progress"));

    private static final JsonArray HOLIDAY_ROWS = new JsonArray()
            .add(new JsonObject().put("date", "2026-10-02").put("name", "Gandhi Jayanti"))
            .add(new JsonObject().put("date", "2026-09-25").put("name", "Site day").put("location", "Pune"));

    private static final Set<LocalDate> HOLIDAYS = WorkingDays.holidays(HOLIDAY_ROWS, null);

    private final PeriodCalendar calendar = new PeriodCalendar(SPRINTS, RELEASES, TODAY);

    // ------------------------------------------------------------------ Period keys

    @Test
    void threeWeekSprintRunsTwentyOneDaysAndIsActiveUntilItsEnd()
    {
        JsonObject s43 = calendar.period("s:S43");

        assertEquals("sprint", s43.getString("kind"));
        assertEquals("2026-09-21", s43.getString("from"));
        assertEquals("2026-10-11", s43.getString("to"));
        assertEquals("2026-10-09", s43.getString("end"));
        assertEquals(21, s43.getInteger("days"));
        assertTrue(s43.getBoolean("live"));
        assertEquals("Sprint 43 · 21 Sep – 9 Oct (active)", s43.getString("label"));

        assertEquals("s:S43", calendar.activeSprint().getString("key"));
        assertEquals("s:S43", at("2026-10-09").activeSprint().getString("key"));
        assertEquals("s:S44", at("2026-10-12").activeSprint().getString("key"));
        assertFalse(at("2026-10-12").period("s:S43").getBoolean("live"));
    }

    @Test
    void twoWeekSprintRunsToTheSundayBeforeTheNextOne()
    {
        JsonObject s42 = calendar.period("s:S42");

        assertEquals(14, s42.getInteger("days"));
        assertEquals("2026-09-20", s42.getString("to"));
        assertFalse(s42.getBoolean("live"));
        assertEquals("Sprint 42 · 7 Sep – 18 Sep", s42.getString("label"));
        assertEquals("s:S42", calendar.sprintOn(LocalDate.parse("2026-09-19")).getString("key"));
    }

    @Test
    void monthIsTheCalendarMonthAndTheCurrentOneIsToDate()
    {
        JsonObject sep = calendar.period("m:2026-09");

        assertEquals("2026-09-01", sep.getString("from"));
        assertEquals("2026-09-30", sep.getString("to"));
        assertEquals("Sep 2026 (to date)", sep.getString("label"));
        assertTrue(sep.getBoolean("live"));

        JsonObject aug = calendar.period("m:2026-08");

        assertEquals("Aug 2026", aug.getString("label"));
        assertEquals(31, aug.getInteger("days"));
        assertFalse(aug.getBoolean("live"));

        assertEquals(29, calendar.period("m:2028-02").getInteger("days"));
        assertEquals(List.of("m:2026-09", "m:2026-08", "m:2026-07"),
                calendar.months(3).stream().map(p -> ((JsonObject) p).getString("key")).toList());
    }

    @Test
    void releaseRunsFromStartToEndWithItsReleaseDateAndStatus()
    {
        JsonObject r82 = calendar.period("r:8.2");

        assertEquals("release", r82.getString("kind"));
        assertEquals("2026-07-13", r82.getString("from"));
        assertEquals("2026-09-20", r82.getString("to"));
        assertEquals("2026-09-18", r82.getString("end"));
        assertEquals("Release 8.2 · 13 Jul – 18 Sep (released)", r82.getString("label"));
        assertFalse(r82.getBoolean("live"));

        assertEquals("Release 8.3 · 21 Sep – 13 Nov (in progress)", calendar.period("r:8.3").getString("label"));
        assertTrue(calendar.period("r:8.3").getBoolean("live"));
    }

    @Test
    void quarterIsTheCalendarQuarter()
    {
        JsonObject q3 = calendar.period("q:Q3");

        assertEquals("q:Q3", q3.getString("key"));
        assertEquals("2026-07-01", q3.getString("from"));
        assertEquals("2026-09-30", q3.getString("to"));
        assertEquals(92, q3.getInteger("days"));
        assertEquals("Q3 2026 (to date)", q3.getString("label"));

        JsonObject q2 = calendar.period("q:Q2");

        assertEquals("2026-04-01", q2.getString("from"));
        assertEquals("2026-06-30", q2.getString("to"));
        assertFalse(q2.getBoolean("live"));

        JsonObject lastYear = calendar.period("q:2025-Q4");

        assertEquals("q:2025-Q4", lastYear.getString("key"));
        assertEquals("2025-12-31", lastYear.getString("to"));
    }

    @Test
    void allSpansEveryKnownSprintAndRelease()
    {
        JsonObject all = new PeriodCalendar(new JsonArray()
                .add(SPRINTS.getJsonObject(0))
                .add(SPRINTS.getJsonObject(3)), new JsonArray(), TODAY).period("all");

        assertEquals("all", all.getString("kind"));
        assertEquals("2026-04-01", all.getString("from"));
        assertEquals("2026-10-11", all.getString("to"));
        assertEquals("All · Apr – Sep 2026", all.getString("label"));
        assertTrue(PeriodCalendar.contains(all, LocalDate.parse("2020-01-01")));
    }

    @Test
    void unknownOrMalformedKeysAreRefused()
    {
        for (String key : new String[] {"s:S99", "r:9.9", "m:2026-13", "m:26-09", "q:Q5", "x:1", "", "ALL"})
        {
            assertThrows(IllegalArgumentException.class, () -> calendar.period(key), key);
        }

        assertThrows(IllegalArgumentException.class, () -> calendar.period(null));
    }

    @Test
    void containsIsInclusive()
    {
        JsonObject s43 = calendar.period("s:S43");

        assertTrue(PeriodCalendar.contains(s43, LocalDate.parse("2026-09-21")));
        assertTrue(PeriodCalendar.contains(s43, LocalDate.parse("2026-10-11")));
        assertFalse(PeriodCalendar.contains(s43, LocalDate.parse("2026-10-12")));
        assertNull(at("2027-01-01").activeSprint());
    }

    @Test
    void sameDatesGiveSameWorkingDays()
    {
        // S43 (21 Sep – 11 Oct) and the range 21 Sep – 11 Oct as two months' slices cover the same days.
        JsonObject person = new JsonObject().put("leave", new JsonArray().add("2026-09-29"));

        List<LocalDate> sprintDays = WorkingDays.inPeriod(calendar.period("s:S43"), HOLIDAYS, person);

        List<LocalDate> byMonth = new java.util.ArrayList<>(WorkingDays.list(LocalDate.parse("2026-09-21"),
                LocalDate.parse("2026-09-30"), HOLIDAYS, person));

        byMonth.addAll(WorkingDays.list(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-11"), HOLIDAYS, person));

        assertEquals(sprintDays, byMonth);
        assertEquals(13, sprintDays.size());   // 15 weekdays − Gandhi Jayanti − 1 leave day
    }

    // ------------------------------------------------------------------ Working-day definition

    @Test
    void leaveAndVisitDaysAreExcluded()
    {
        // S42: 7 – 18 Sep, 10 weekdays, no holiday.
        JsonObject person = new JsonObject()
                .put("leave", new JsonArray().add("2026-09-09"))
                .put("visits", new JsonArray().add(new JsonObject().put("from", "2026-09-15").put("to", "2026-09-16")));

        JsonObject s42 = calendar.period("s:S42");

        assertEquals(10, WorkingDays.inPeriod(s42, HOLIDAYS, null).size());
        assertEquals(7, WorkingDays.inPeriod(s42, HOLIDAYS, person).size());
        assertEquals(WorkingDays.LEAVE, WorkingDays.status(LocalDate.parse("2026-09-09"), HOLIDAYS, person));
        assertEquals(WorkingDays.VISIT, WorkingDays.status(LocalDate.parse("2026-09-16"), HOLIDAYS, person));
    }

    @Test
    void daysBeforeJoiningAreExcludedForAJoinerMidMonth()
    {
        JsonObject joiner = new JsonObject().put("joinedOn", "2026-09-08");

        assertEquals(WorkingDays.BEFORE_JOINING, WorkingDays.status(LocalDate.parse("2026-09-07"), HOLIDAYS, joiner));
        assertEquals(WorkingDays.BEFORE_JOINING, WorkingDays.status(LocalDate.parse("2026-09-05"), HOLIDAYS, joiner));
        assertEquals(WorkingDays.WORKING, WorkingDays.status(LocalDate.parse("2026-09-08"), HOLIDAYS, joiner));

        JsonObject sep = calendar.period("m:2026-09");

        assertEquals(22, WorkingDays.inPeriod(sep, HOLIDAYS, null).size());
        assertEquals(17, WorkingDays.inPeriod(sep, HOLIDAYS, joiner).size());
        assertEquals(LocalDate.parse("2026-09-08"), WorkingDays.inPeriod(sep, HOLIDAYS, joiner).get(0));
    }

    @Test
    void aWeekWithAHolidayHasFourWorkingDays()
    {
        LocalDate monday = LocalDate.parse("2026-09-28");
        LocalDate sunday = LocalDate.parse("2026-10-04");

        assertEquals(4, WorkingDays.count(monday, sunday, HOLIDAYS, null));
        assertEquals(WorkingDays.HOLIDAY, WorkingDays.status(LocalDate.parse("2026-10-02"), HOLIDAYS, null));
        assertEquals(WorkingDays.WEEKEND, WorkingDays.status(LocalDate.parse("2026-10-03"), HOLIDAYS, null));

        // S43 as the prototype counts it (workBetween(start, end)): 15 weekdays less Gandhi Jayanti.
        assertEquals(14, WorkingDays.count(LocalDate.parse("2026-09-21"), LocalDate.parse("2026-10-09"), HOLIDAYS, null));
    }

    @Test
    void siteHolidaysApplyOnlyAtTheirLocation()
    {
        Set<LocalDate> pune = WorkingDays.holidays(HOLIDAY_ROWS, "pune");

        assertTrue(pune.contains(LocalDate.parse("2026-09-25")));
        assertTrue(pune.contains(LocalDate.parse("2026-10-02")));
        assertFalse(HOLIDAYS.contains(LocalDate.parse("2026-09-25")));
        assertEquals(Set.of(), WorkingDays.holidays(null, "Pune"));
    }

    // ------------------------------------------------------------------ Clock and time zone

    @Test
    void pinnedTodayIsUsedOutsideProduction()
    {
        Clock real = Clock.fixed(Instant.parse("2027-03-15T20:00:00Z"), ZoneOffset.UTC);

        PulseClock pinned = PulseClock.of(real, "2026-09-28", false);

        assertTrue(pinned.pinned());
        assertEquals(LocalDate.parse("2026-09-28"), pinned.today());
        assertEquals(LocalDate.parse("2026-09-28"), pinned.now().toLocalDate());
        assertEquals(PulseClock.ZONE, pinned.now().getZone());
    }

    @Test
    void pinIsIgnoredInProduction()
    {
        Clock real = Clock.fixed(Instant.parse("2027-03-15T20:00:00Z"), ZoneOffset.UTC);

        PulseClock production = PulseClock.of(real, "2026-09-28", true);

        assertFalse(production.pinned());
        // 20:00 UTC is 01:30 the next day in Asia/Kolkata.
        assertEquals(LocalDate.parse("2027-03-16"), production.today());
    }

    @Test
    void malformedPinFailsAtStartUp()
    {
        Clock real = Clock.systemUTC();

        assertThrows(IllegalStateException.class, () -> PulseClock.of(real, "28/09/2026", false));
        assertFalse(PulseClock.of(real, "  ", false).pinned());
        assertFalse(PulseClock.of(real, null, false).pinned());
    }

    @Test
    void lateCheckInBoundaryUsesCompanyTime()
    {
        // 10:31 in Asia/Kolkata is 05:01 UTC, whatever zone the viewer's browser is in.
        Instant checkIn = Instant.parse("2026-09-28T05:01:00Z");

        LocalTime company = CalendarFormat.companyTime(checkIn);

        assertEquals(LocalTime.of(10, 31), company);
        assertTrue(company.isAfter(LocalTime.of(10, 30)));
        assertEquals("10:31", CalendarFormat.time(checkIn));
        assertEquals(LocalDate.parse("2026-09-29"), CalendarFormat.companyDate(Instant.parse("2026-09-28T19:00:00Z")));
    }

    @Test
    void datesAndTimesUseTheDisplayFormat()
    {
        assertEquals("28 Sep", CalendarFormat.date(LocalDate.parse("2026-09-28")));
        assertEquals("2 Oct", CalendarFormat.date(LocalDate.parse("2026-10-02")));
        assertEquals("28 Sep 2026", CalendarFormat.dateWithYear(LocalDate.parse("2026-09-28")));
        assertEquals("09:05", CalendarFormat.time(Instant.parse("2026-09-28T03:35:00Z")));
        assertEquals("19:35", CalendarFormat.time(Instant.parse("2026-09-28T14:05:00Z")));
        assertEquals("28 Sep, 15:30", CalendarFormat.dateTime(Instant.parse("2026-09-28T10:00:00Z")));
    }

    // ------------------------------------------------------------------ Sprint names (change jira-sprint-names)

    @Test
    void aLiveSprintIsLabelledByItsJiraName()
    {
        PeriodCalendar live = new PeriodCalendar(new JsonArray()
                .add(sprint("J142", 142, "2026-09-08", "2026-10-02", "2026-10-04").put("name", "Atlas 2.4.1")), RELEASES, TODAY);

        JsonObject atlas = live.period("s:J142");

        assertEquals("Atlas 2.4.1", atlas.getString("short"));
        assertEquals("Atlas 2.4.1 · 8 Sep – 2 Oct (active)", atlas.getString("label"));
        assertEquals("s:J142", atlas.getString("key"), "the key does not change with the name");

        assertEquals("Atlas 2.4.1", live.activeSprint().getString("short"));
        assertEquals("Atlas 2.4.1", live.sprints().getJsonObject(0).getString("short"));
    }

    @Test
    void aSprintWithoutANameKeepsSprintN()
    {
        JsonObject s43 = calendar.period("s:S43");

        assertEquals("Sprint 43", s43.getString("short"));
        assertEquals("Sprint 43 · 21 Sep – 9 Oct (active)", s43.getString("label"));

        // a null or blank name reads as none; a name is shown trimmed
        PeriodCalendar blank = new PeriodCalendar(new JsonArray()
                .add(sprint("J7", 7, "2026-09-07", "2026-09-18", "2026-09-20").put("name", "  "))
                .add(sprint("J8", 8, "2026-09-21", "2026-10-02", "2026-10-04").putNull("name"))
                .add(sprint("J9", 9, "2026-10-05", "2026-10-16", "2026-10-18").put("name", " Atlas 2.5 ")), RELEASES, TODAY);

        assertEquals("Sprint 7", blank.period("s:J7").getString("short"));
        assertEquals("Sprint 8", blank.period("s:J8").getString("short"));
        assertEquals("Atlas 2.5", blank.period("s:J9").getString("short"));
    }

    @Test
    void twoSprintsWithTheSameNameAreToldApartByTheirKeys()
    {
        PeriodCalendar boards = new PeriodCalendar(new JsonArray()
                .add(sprint("J140", 140, "2026-09-07", "2026-09-18", "2026-09-20").put("name", "Sprint 12"))
                .add(sprint("J141", 141, "2026-09-14", "2026-09-25", "2026-09-27").put("name", "Sprint 12"))
                .add(sprint("J142", 142, "2026-09-21", "2026-10-02", "2026-10-04").put("name", "Atlas 2.4.1")), RELEASES, TODAY);

        assertEquals("Sprint 12 (J140)", boards.period("s:J140").getString("short"));
        assertEquals("Sprint 12 (J141)", boards.period("s:J141").getString("short"));
        assertEquals("Sprint 12 (J140) · 7 Sep – 18 Sep", boards.period("s:J140").getString("label"));
        assertEquals("Atlas 2.4.1", boards.period("s:J142").getString("short"), "a name used once has no key");

        // a name that matches another sprint's "Sprint N" is a clash too
        PeriodCalendar mixed = new PeriodCalendar(new JsonArray()
                .add(sprint("S43", 43, "2026-09-21", "2026-10-09", "2026-10-11"))
                .add(sprint("J9", 9, "2026-10-12", "2026-10-23", "2026-10-25").put("name", "Sprint 43")), RELEASES, TODAY);

        assertEquals("Sprint 43 (S43)", mixed.period("s:S43").getString("short"));
        assertEquals("Sprint 43 (J9)", mixed.period("s:J9").getString("short"));
    }

    @Test
    void aRenameChangesOnlyTheLabel()
    {
        JsonObject before = new PeriodCalendar(new JsonArray()
                .add(sprint("J142", 142, "2026-09-08", "2026-10-02", "2026-10-04").put("name", "Atlas 2.4.1")), RELEASES, TODAY)
                .period("s:J142");

        JsonObject after = new PeriodCalendar(new JsonArray()
                .add(sprint("J142", 142, "2026-09-08", "2026-10-02", "2026-10-04").put("name", "Atlas 2.4.2")), RELEASES, TODAY)
                .period("s:J142");

        assertEquals("Atlas 2.4.2", after.getString("short"));
        assertEquals(before.copy().put("label", "").put("short", ""), after.copy().put("label", "").put("short", ""),
                "key, dates and state are the same");
    }

    // ------------------------------------------------------------------ helpers

    private static PeriodCalendar at(String today)
    {
        return new PeriodCalendar(SPRINTS, RELEASES, LocalDate.parse(today));
    }

    private static JsonObject sprint(String key, int number, String from, String workEnd, String to)
    {
        return new JsonObject().put("key", key).put("number", number).put("startsOn", from)
                .put("workEndsOn", workEnd).put("endsOn", to);
    }
}
