package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Attendance statistics and score (task 4.6, spec time/attendance), ported from the prototype's
 * {@code attStats} and helpers (app.js ~2266–2320: {@code attNotice}, {@code attLeaveClass},
 * {@code attRec}, {@code attMonths}, {@code attTone}).
 *
 * <p>Per working day of the period (weekday, not a company holiday, not before joining, in an uploaded
 * month, up to today) the person's record is one of:
 * {@code O} Office, {@code W} WFH, {@code D} On duty (counts as office; a planned customer visit shows
 * as D unless the day is leave or absent), {@code L} Leave, {@code S} Sick or Emergency leave,
 * {@code HD} Half day (0.5 leave day), {@code H} Holiday (not a working day) or {@code A} Absent.
 * A day with no record is Absent (spec "Notice classification"; the prototype leaves it out).
 *
 * <ul>
 *   <li>Notice: leave applied at least {@code notice} days ahead ({@code noticeLong} for Earned) is
 *       planned, later is short notice; Sick and Emergency are excused and never penalise.
 *       Leave with notice % = planned ÷ (planned + short notice + absent).</li>
 *   <li>Office policy: per week, required = min({@code officeDays}, available days); office % =
 *       Σ min(office + duty, required) ÷ Σ required.</li>
 *   <li>Punctuality: late = check-in after {@code lateAfter}; allowance = round(grace × working days ÷ 21);
 *       punctuality % = (check-ins − max(0, late − allowance)) ÷ check-ins.</li>
 *   <li>Score: the rounded average of the parts with data; Good ≥ 85, Watch ≥ 70, else Talk.</li>
 *   <li>Collaboration "Office and on-site presence": office % in the bands 95/85/70/55.</li>
 * </ul>
 *
 * <p>Pure functions over plain data; see {@link #stats} for the inputs.
 */
public final class Attendance
{
    public static final String GOOD = "Good";

    public static final String WATCH = "Watch";

    public static final String TALK = "Talk";

    public static final String NO_DATA = "No data";

    /** Bands of the Collaboration "Office and on-site presence" parameter. */
    public static final JsonArray PRESENCE_BANDS = new JsonArray().add(95).add(85).add(70).add(55);

    private static final Set<String> EXCUSED_TYPES = Set.of("Sick", "Emergency");

    private Attendance()
    {
    }

    /**
     * {@code attStats}: a person's attendance from {@code from} to {@code to} (inclusive, cut at today).
     *
     * @param person   {@code {joinedOn?}}
     * @param records  the person's records by date: {@code {"2026-09-01": {status, leaveType?, appliedOn?,
     *                 checkIn?, checkOut?}}} (times "HH:mm", company time zone)
     * @param visits   the person's customer visits {@code [{from, to}]}
     * @param holidays company holidays at the person's site
     * @param uploaded uploaded months ("2026-08"); only those count
     * @param rules    rule {@code att}: {@code {officeDays, notice, noticeLong, lateAfter, grace}}
     * @return {@code {workingDays, records, office, wfh, duty, leave, half, planned, shortNotice, excused,
     *         absent, late, timed, hours, hoursN, lateDays, leaves, noRecord, months, uploaded, missing,
     *         officeRequired, officeAttended, officePct, noticePct, allowance, lateExcess, punctualityPct,
     *         avgHours, score, band}}; percentages and the score are null when they have no data
     */
    public static JsonObject stats(JsonObject person, JsonObject records, JsonArray visits, Set<LocalDate> holidays,
                                   Set<String> uploaded, LocalDate from, LocalDate to, LocalDate today, JsonObject rules)
    {
        LocalDate joined = person == null || person.getString("joinedOn") == null
                ? LocalDate.MIN : LocalDate.parse(person.getString("joinedOn"));

        LocalDate end = to.isAfter(today) ? today : to;

        int lateAfter = minutes(rules.getString("lateAfter", "10:30"));

        int officeDays = rules.getInteger("officeDays", 3);

        int wd = 0;
        int rec = 0;
        int office = 0;
        int wfh = 0;
        int duty = 0;
        int half = 0;
        int late = 0;
        int timed = 0;
        int hoursN = 0;

        double leave = 0;
        double hours = 0;

        Map<String, Double> byClass = new LinkedHashMap<>();

        byClass.put("planned", 0d);
        byClass.put("shortNotice", 0d);
        byClass.put("excused", 0d);
        byClass.put("absent", 0d);

        JsonArray lateDays = new JsonArray();
        JsonArray leaves = new JsonArray();
        JsonArray noRecord = new JsonArray();

        Map<LocalDate, int[]> weeks = new LinkedHashMap<>();   // week Monday -> {available, office}

        for (LocalDate s = from; !s.isAfter(end); s = s.plusDays(1))
        {
            if (s.isBefore(joined) || !uploaded.contains(YearMonth.from(s).toString()))
            {
                continue;
            }

            DayOfWeek w = s.getDayOfWeek();

            JsonObject a = record(records, visits, s);

            if (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY || holidays.contains(s)
                    || a != null && "H".equals(a.getString("status")))
            {
                continue;
            }

            wd++;

            int[] week = weeks.computeIfAbsent(s.with(DayOfWeek.MONDAY), k -> new int[2]);

            if (a == null)
            {
                noRecord.add(s.toString());

                a = new JsonObject().put("status", "A");
            }
            else
            {
                rec++;
            }

            String st = a.getString("status");

            if (st.equals("L") || st.equals("S") || st.equals("A") || st.equals("HD"))
            {
                double amount = st.equals("HD") ? 0.5 : 1;

                String cls = leaveClass(a, s, rules);

                leave += amount;

                if (st.equals("HD"))
                {
                    half++;
                }

                byClass.merge(cls, amount, Double::sum);

                String applied = a.getString("appliedOn");

                leaves.add(new JsonObject()
                        .put("date", s.toString())
                        .put("status", st)
                        .put("leaveType", a.getString("leaveType") == null ? "—" : a.getString("leaveType"))
                        .put("appliedOn", applied == null ? "" : applied)
                        .put("notice", applied == null ? null : ChronoUnit.DAYS.between(LocalDate.parse(applied), s))
                        .put("class", cls)
                        .put("amount", amount == 1 ? (Number) 1 : (Number) amount));

                if (!st.equals("HD"))
                {
                    continue;
                }
            }
            else
            {
                week[0]++;
            }

            if (st.equals("O"))
            {
                office++;
                week[1]++;
            }
            else if (st.equals("W"))
            {
                wfh++;
            }
            else if (st.equals("D"))
            {
                duty++;
                week[1]++;
            }

            String in = a.getString("checkIn");

            if (in != null && !st.equals("D") && !st.equals("HD"))
            {
                timed++;

                if (minutes(in) > lateAfter)
                {
                    late++;
                    lateDays.add(new JsonArray().add(s.toString()).add(in));
                }

                String out = a.getString("checkOut");

                if (out != null)
                {
                    hours += (minutes(out) - minutes(in)) / 60.0;
                    hoursN++;
                }
            }
        }

        int req = 0;
        int got = 0;

        for (int[] week : weeks.values())
        {
            int rq = Math.min(officeDays, week[0]);

            req += rq;
            got += Math.min(week[1], rq);
        }

        Long officePct = req == 0 ? null : Num.round((double) got / req * 100);

        double nl = byClass.get("planned") + byClass.get("shortNotice") + byClass.get("absent");

        Long noticePct = nl == 0 ? null : Num.round(byClass.get("planned") / nl * 100);

        long allowance = Num.round(rules.getDouble("grace", 3d) * wd / 21);

        long lateExcess = Math.max(0, late - allowance);

        Long punctualityPct = timed == 0 ? null : Num.round((timed - lateExcess) / (double) timed * 100);

        Long score = score(noticePct, officePct, punctualityPct);

        JsonArray months = new JsonArray();
        JsonArray up = new JsonArray();
        JsonArray missing = new JsonArray();

        // Months of the period up to today: a future month is not "missing".
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1))
        {
            months.add(m.toString());

            (uploaded.contains(m.toString()) ? up : missing).add(m.toString());
        }

        return new JsonObject()
                .put("workingDays", wd)
                .put("records", rec)
                .put("office", office)
                .put("wfh", wfh)
                .put("duty", duty)
                .put("leave", number(leave))
                .put("half", half)
                .put("planned", number(byClass.get("planned")))
                .put("shortNotice", number(byClass.get("shortNotice")))
                .put("excused", number(byClass.get("excused")))
                .put("absent", number(byClass.get("absent")))
                .put("late", late)
                .put("timed", timed)
                .put("hours", number(hours))
                .put("hoursN", hoursN)
                .put("lateDays", lateDays)
                .put("leaves", leaves)
                .put("noRecord", noRecord)
                .put("months", months)
                .put("uploaded", up)
                .put("missing", missing)
                .put("officeRequired", req)
                .put("officeAttended", got)
                .put("officePct", officePct)
                .put("noticePct", noticePct)
                .put("allowance", allowance)
                .put("lateExcess", lateExcess)
                .put("punctualityPct", punctualityPct)
                .put("avgHours", hoursN == 0 ? null : hours / hoursN)
                .put("score", score)
                .put("band", band(score));
    }

    /** The score: the rounded average of the parts that have data, or null with none. */
    public static Long score(Long noticePct, Long officePct, Long punctualityPct)
    {
        long sum = 0;

        int n = 0;

        for (Long part : new Long[] {noticePct, officePct, punctualityPct})
        {
            if (part != null)
            {
                sum += part;
                n++;
            }
        }

        return n == 0 ? null : Num.round((double) sum / n);
    }

    /** {@code attTone}: Good at 85 or more, Watch at 70–84, Talk below 70, No data without a score. */
    public static String band(Long score)
    {
        if (score == null)
        {
            return NO_DATA;
        }

        return score >= 85 ? GOOD : score >= 70 ? WATCH : TALK;
    }

    /**
     * The Collaboration "Office and on-site presence" score (1–5, bands 95/85/70/55), or null when
     * there is no office policy data or attendance is switched off for culture values.
     */
    public static Integer presenceScore(Long officePct, boolean inCulture)
    {
        return !inCulture || officePct == null ? null : CultureScore.bandScore(officePct, PRESENCE_BANDS, true);
    }

    /**
     * {@code attLeaveClass}: absent, excused (Sick or Emergency), planned (applied at least the notice
     * ahead: {@code noticeLong} for Earned, else {@code notice}) or short notice.
     */
    public static String leaveClass(JsonObject a, LocalDate day, JsonObject rules)
    {
        String st = a.getString("status");

        if ("A".equals(st))
        {
            return "absent";
        }

        if ("S".equals(st) || EXCUSED_TYPES.contains(a.getString("leaveType")))
        {
            return "excused";
        }

        if (a.getString("appliedOn") == null)
        {
            return "shortNotice";
        }

        long notice = ChronoUnit.DAYS.between(LocalDate.parse(a.getString("appliedOn")), day);

        int needed = "Earned".equals(a.getString("leaveType"))
                ? rules.getInteger("noticeLong", 7) : rules.getInteger("notice", 2);

        return notice >= needed ? "planned" : "shortNotice";
    }

    /** {@code attRec}: a planned customer visit shows as On duty unless the day is leave or absent. */
    private static JsonObject record(JsonObject records, JsonArray visits, LocalDate s)
    {
        JsonObject a = records == null ? null : records.getJsonObject(s.toString());

        if (onVisit(visits, s))
        {
            String st = a == null ? null : a.getString("status");

            if (st == null || !(st.equals("L") || st.equals("S") || st.equals("A")))
            {
                return new JsonObject().put("status", "D");
            }
        }

        return a;
    }

    private static boolean onVisit(JsonArray visits, LocalDate s)
    {
        if (visits == null)
        {
            return false;
        }

        String iso = s.toString();

        for (Object o : visits)
        {
            JsonObject v = (JsonObject) o;

            if (iso.compareTo(v.getString("from")) >= 0 && iso.compareTo(v.getString("to")) <= 0)
            {
                return true;
            }
        }

        return false;
    }

    private static int minutes(String hhmm)
    {
        String[] p = hhmm.strip().split(":");

        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private static Number number(double x)
    {
        return x == Math.rint(x) ? (Number) (long) x : (Number) x;
    }
}
