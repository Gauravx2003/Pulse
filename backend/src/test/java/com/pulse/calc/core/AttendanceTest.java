package com.pulse.calc.core;

import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4.6: attendance against spec time/attendance and against the oracle {@code attStats}
 * (app.js ~2301) for every person and every month, sprint and quarter in the seed, including a person
 * with sick leave only ({@code an}).
 *
 * <p>Fixture: the prototype's attendance records ({@code ATT}, with its half day {@code H} translated
 * to the spec's {@code HD}), visits, holidays and uploaded months; Track 3 will load the same rows into
 * {@code attendance_records} (task 17.1). Periods come from {@link PeriodCalendar}.
 */
class AttendanceTest
{
    private static final LocalDate TODAY = LocalDate.parse("2026-09-28");

    /** The prototype's attStats in the shape of the port's result. */
    private static final String STATS = "(function(id,f,t){var o=attStats(person(id),f,t);return {workingDays:o.wd,records:o.rec,"
            + "office:o.office,wfh:o.wfh,duty:o.duty,leave:o.leave,half:o.half,planned:o.planned,shortNotice:o.unplanned,"
            + "excused:o.excused,absent:o.absent,late:o.late,timed:o.timed,hours:o.hrs,hoursN:o.hrsN,lateDays:o.lateDays,"
            + "leaves:o.leaves.map(function(l){return {date:l.date,status:l.st==='H'?'HD':l.st,leaveType:l.lt,appliedOn:l.ap,"
            + "notice:l.notice,'class':l.cls==='unplanned'?'shortNotice':l.cls,amount:l.amt}}),noRecord:o.noRec,months:o.months,"
            + "uploaded:o.up,missing:o.missing,officeRequired:o.officeReq,officeAttended:o.officeGot,officePct:o.officePct,"
            + "noticePct:o.noticePct,allowance:o.graceN,lateExcess:o.lateExcess,punctualityPct:o.punctPct,avgHours:o.avgHrs,"
            + "score:o.score,band:attTone(o.score)[1]}})";

    private static final JsonObject RULES = new JsonObject().put("officeDays", 3).put("notice", 2).put("noticeLong", 7)
            .put("lateAfter", "10:30").put("grace", 3);

    private static Oracle oracle;

    private static JsonObject rules;

    private static Set<LocalDate> holidays;

    private static Set<String> uploaded;

    private static PeriodCalendar calendar;

    @BeforeAll
    static void start() throws Exception
    {
        oracle = Oracle.start();

        rules = (JsonObject) oracle.eval("S.rules.att");

        holidays = new HashSet<>();

        ((JsonArray) oracle.eval("Object.keys(HOLIDAYS)")).forEach(d -> holidays.add(LocalDate.parse((String) d)));

        uploaded = new HashSet<>();

        ((JsonArray) oracle.eval("Object.keys(ATT_UP)")).forEach(m -> uploaded.add((String) m));

        JsonArray sprints = (JsonArray) oracle.eval("SPRINTS.map(function(s){return {key:s.id,number:s.n,startsOn:s.from,"
                + "workEndsOn:s.end,endsOn:s.to}})");

        calendar = new PeriodCalendar(sprints, new JsonArray(), TODAY);
    }

    @AfterAll
    static void stop()
    {
        oracle.close();
    }

    // ------------------------------------------------------------------ oracle

    @Test
    void statsMatchTheOracleForEveryPersonAndPeriod()
    {
        JsonArray ids = (JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})");

        assertEquals(8, ids.size());

        List<String> keys = new ArrayList<>();

        for (String m : new String[] {"2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09"})
        {
            keys.add("m:" + m);
        }

        calendar.sprints().forEach(p -> keys.add(((JsonObject) p).getString("key")));

        keys.add("q:Q2");
        keys.add("q:Q3");

        assertEquals(6 + 13 + 2, keys.size());

        int compared = 0;

        for (Object o : ids)
        {
            String id = (String) o;

            JsonObject person = new JsonObject().put("joinedOn", oracle.eval("JOINED['" + id + "'] || '2000-01-01'"));

            JsonObject records = records(id);

            JsonArray visits = (JsonArray) oracle.eval("VISITS.filter(function(v){return v.pid==='" + id
                    + "'}).map(function(v){return {from:v.from,to:v.to}})");

            for (String key : keys)
            {
                JsonObject period = calendar.period(key);

                String from = period.getString("from");
                String to = period.getString("to");

                oracle.compare(STATS, Oracle.args(id, from, to), 2, in -> Attendance.stats(person, records, visits, holidays,
                        uploaded, LocalDate.parse(from), LocalDate.parse(to), TODAY, rules));

                compared++;
            }
        }

        assertEquals(8 * 21, compared);
    }

    @Test
    void personWithSickLeaveOnlyIsNeverPenalised()
    {
        JsonObject an = records("an");

        assertTrue(an.stream().map(e -> ((JsonObject) e.getValue()).getString("status")).anyMatch("L"::equals));

        JsonObject q3 = Attendance.stats(new JsonObject().put("joinedOn", oracle.eval("JOINED['an'] || '2000-01-01'")), an,
                new JsonArray(), holidays, uploaded, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-09-30"), TODAY, rules);

        assertTrue(((Number) q3.getValue("excused")).doubleValue() > 0);
        assertEquals(0, ((Number) q3.getValue("planned")).intValue() + ((Number) q3.getValue("shortNotice")).intValue());
        assertNull(q3.getValue("noticePct"), "excused leave is not a notice part");
    }

    // ------------------------------------------------------------------ spec scenarios

    @Test
    void sickOrEmergencyIsExcusedAndCasualOneDayAheadIsShortNotice()
    {
        LocalDate day = LocalDate.parse("2026-08-12");

        assertEquals("excused", Attendance.leaveClass(rec("S", null, null), day, RULES));
        assertEquals("excused", Attendance.leaveClass(rec("L", "Emergency", "2026-08-12"), day, RULES));
        assertEquals("shortNotice", Attendance.leaveClass(rec("L", "Casual", "2026-08-11"), day, RULES));
        assertEquals("planned", Attendance.leaveClass(rec("L", "Casual", "2026-08-10"), day, RULES));
        assertEquals("shortNotice", Attendance.leaveClass(rec("L", "Earned", "2026-08-06"), day, RULES));
        assertEquals("planned", Attendance.leaveClass(rec("L", "Earned", "2026-08-05"), day, RULES));
        assertEquals("absent", Attendance.leaveClass(rec("A", null, null), day, RULES));
    }

    @Test
    void weekWithTwoAvailableDaysAndTwoOfficeDaysScores100()
    {
        // Mon–Wed leave (planned), Thu and Fri in the office.
        JsonObject records = new JsonObject()
                .put("2026-08-10", rec("L", "Casual", "2026-08-01"))
                .put("2026-08-11", rec("L", "Casual", "2026-08-01"))
                .put("2026-08-12", rec("L", "Casual", "2026-08-01"))
                .put("2026-08-13", office("09:30"))
                .put("2026-08-14", office("09:30"));

        JsonObject s = week(records);

        assertEquals(2, s.getInteger("officeRequired"));
        assertEquals(100L, s.getLong("officePct"));
        assertEquals(100L, s.getLong("noticePct"));
    }

    @Test
    void threeLateDaysInAMonthArePunctual()
    {
        JsonObject records = new JsonObject();

        LocalDate d = LocalDate.parse("2026-08-01");

        int n = 0;

        for (; d.getMonthValue() == 8; d = d.plusDays(1))
        {
            if (d.getDayOfWeek().getValue() <= 5)
            {
                records.put(d.toString(), office(n++ < 3 ? "10:31" : "10:30"));
            }
        }

        JsonObject s = Attendance.stats(null, records, null, Set.of(), Set.of("2026-08"), LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-31"), TODAY, RULES);

        assertEquals(21, s.getInteger("workingDays"));
        assertEquals(3, s.getInteger("late"));
        assertEquals(3L, s.getLong("allowance"));
        assertEquals(100L, s.getLong("punctualityPct"));
        assertEquals(Attendance.GOOD, s.getString("band"));
    }

    @Test
    void withoutCheckInsTheScoreAveragesTheOtherTwoParts()
    {
        JsonObject records = new JsonObject()
                .put("2026-08-10", rec("O", null, null))
                .put("2026-08-11", rec("O", null, null))
                .put("2026-08-12", rec("W", null, null))
                .put("2026-08-13", rec("W", null, null))
                .put("2026-08-14", rec("L", "Casual", "2026-08-13"));

        JsonObject s = week(records);

        assertNull(s.getValue("punctualityPct"));
        assertEquals(67L, s.getLong("officePct"));    // 2 of min(3, 4 available)
        assertEquals(0L, s.getLong("noticePct"));     // one short-notice day
        assertEquals(Attendance.score(0L, 67L, null), s.getLong("score"));
        assertEquals(34L, s.getLong("score"));
        assertEquals(Attendance.TALK, s.getString("band"));
    }

    @Test
    void aDayWithNoRecordIsAbsentAndAVisitShowsAsOnDuty()
    {
        JsonObject records = new JsonObject()
                .put("2026-08-10", office("09:30"))
                .put("2026-08-11", office("09:30"))
                .put("2026-08-13", rec("W", null, null))
                .put("2026-08-14", rec("W", null, null));

        JsonArray visits = new JsonArray().add(new JsonObject().put("from", "2026-08-13").put("to", "2026-08-13"));

        JsonObject s = Attendance.stats(null, records, visits, Set.of(), Set.of("2026-08"), LocalDate.parse("2026-08-10"),
                LocalDate.parse("2026-08-14"), TODAY, RULES);

        assertEquals(List.of("2026-08-12"), s.getJsonArray("noRecord").getList());
        assertEquals(1L, s.getLong("absent"));
        assertEquals(1, s.getInteger("duty"));
        assertEquals(3, s.getInteger("officeAttended"));   // 2 office + 1 on duty
    }

    @Test
    void holidaysNotUploadedMonthsAndDaysBeforeJoiningAreNotWorkingDays()
    {
        JsonObject s = Attendance.stats(new JsonObject().put("joinedOn", "2026-08-12"), new JsonObject(), null,
                Set.of(LocalDate.parse("2026-08-14")), Set.of("2026-08"), LocalDate.parse("2026-08-10"),
                LocalDate.parse("2026-09-04"), TODAY, RULES);

        // 12, 13 Aug (14 Aug is the holiday); 17–21, 24–28, 31 Aug = 13 working days; September is not uploaded.
        assertEquals(13, s.getInteger("workingDays"));
        assertEquals(List.of("2026-09"), s.getJsonArray("missing").getList());
        assertEquals(13L, s.getLong("absent"));
    }

    @Test
    void collaborationPresenceBands()
    {
        assertEquals(5, Attendance.presenceScore(95L, true));
        assertEquals(4, Attendance.presenceScore(85L, true));
        assertEquals(3, Attendance.presenceScore(70L, true));
        assertEquals(2, Attendance.presenceScore(55L, true));
        assertEquals(1, Attendance.presenceScore(54L, true));
        assertNull(Attendance.presenceScore(95L, false));
        assertNull(Attendance.presenceScore(null, true));
        assertEquals(Attendance.NO_DATA, Attendance.band(null));
        assertEquals(Attendance.WATCH, Attendance.band(84L));
        assertEquals(Attendance.TALK, Attendance.band(69L));
    }

    // ------------------------------------------------------------------ fixture

    private static JsonObject records(String id)
    {
        return (JsonObject) oracle.eval("(function(id){var o={},A=ATT[id]||{};Object.keys(A).forEach(function(s){var a=A[s];"
                + "o[s]={status:a.st==='H'?'HD':a.st,leaveType:a.lt||null,appliedOn:a.ap||null,checkIn:a['in']||null,"
                + "checkOut:a.out||null}});return o})('" + id + "')");
    }

    private static JsonObject week(JsonObject records)
    {
        return Attendance.stats(null, records, null, Set.of(), Set.of("2026-08"), LocalDate.parse("2026-08-10"),
                LocalDate.parse("2026-08-14"), TODAY, RULES);
    }

    private static JsonObject rec(String status, String leaveType, String appliedOn)
    {
        return new JsonObject().put("status", status).put("leaveType", leaveType).put("appliedOn", appliedOn);
    }

    private static JsonObject office(String in)
    {
        return new JsonObject().put("status", "O").put("checkIn", in).put("checkOut", "18:30");
    }
}
