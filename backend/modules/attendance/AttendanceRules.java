package com.pulse.modules.attendance;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Attendance codes and fixed rules (spec time/attendance "Record and statuses"; prototype {@code ATT_ST},
 * {@code LEAVE_TYPES}, {@code attStatus}, {@code PER_MONTHS}). The numbers (notice, office days, late
 * after, grace) are rule {@code att} and live in calc ({@code Attendance}).
 */
final class AttendanceRules
{
    /** Office, WFH, On duty, Leave, Sick or Emergency leave, Half day, Holiday, Absent. */
    static final Map<String, String> STATUS_NAMES = Map.of("O", "Office", "W", "WFH", "D", "On duty", "L", "Leave",
            "S", "Sick or emergency leave", "HD", "Half day", "H", "Holiday", "A", "Absent");

    static final List<String> LEAVE_TYPES = List.of("Casual", "Sick", "Earned", "Comp off", "Emergency");

    /** Leave types that are always excused and never penalize a score. */
    static final Set<String> EXCUSED = Set.of("Sick", "Emergency");

    /** A row that names a company holiday; it is not stored (the holiday calendar is its own import). */
    static final String HOLIDAY_ROW = "HOL";

    /** Months Pulse keeps attendance for: this one and the five before it (prototype {@code PER_MONTHS}). */
    static final int MONTHS_KEPT = 6;

    private static final Map<String, String> WORDS = Map.ofEntries(
            Map.entry("office", "O"), Map.entry("wfo", "O"), Map.entry("present", "O"), Map.entry("p", "O"), Map.entry("o", "O"),
            Map.entry("work from office", "O"), Map.entry("in office", "O"),
            Map.entry("wfh", "W"), Map.entry("w", "W"), Map.entry("remote", "W"), Map.entry("work from home", "W"), Map.entry("home", "W"),
            Map.entry("on duty", "D"), Map.entry("onduty", "D"), Map.entry("od", "D"), Map.entry("d", "D"), Map.entry("customer visit", "D"),
            Map.entry("client visit", "D"), Map.entry("travel", "D"),
            Map.entry("leave", "L"), Map.entry("l", "L"), Map.entry("on leave", "L"),
            Map.entry("sick", "S"), Map.entry("s", "S"), Map.entry("sick leave", "S"), Map.entry("emergency", "S"),
            Map.entry("emergency leave", "S"),
            Map.entry("half day", "HD"), Map.entry("halfday", "HD"), Map.entry("hd", "HD"), Map.entry("half", "HD"),
            Map.entry("absent", "A"), Map.entry("a", "A"), Map.entry("lwp", "A"), Map.entry("loss of pay", "A"),
            Map.entry("holiday", HOLIDAY_ROW), Map.entry("hol", HOLIDAY_ROW), Map.entry("public holiday", HOLIDAY_ROW));

    private AttendanceRules()
    {
    }

    /** The code of a CSV status word ("Office", "WFH", "Half day" ...), {@link #HOLIDAY_ROW}, or null. */
    static String status(String text)
    {
        if (text == null)
        {
            return null;
        }

        return WORDS.get(text.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", " "));
    }

    /** The leave type as listed, matched ignoring case, or null. */
    static String leaveType(String text)
    {
        if (text == null)
        {
            return null;
        }

        for (String type : LEAVE_TYPES)
        {
            if (type.equalsIgnoreCase(text.strip()))
            {
                return type;
            }
        }

        return null;
    }

    /** True for a status that takes a leave type: Leave, Sick or Emergency leave and Half day. */
    static boolean isLeave(String status)
    {
        return "L".equals(status) || "S".equals(status) || "HD".equals(status);
    }

    /** The kept months, newest first ("2026-11", "2026-10" ...). */
    static List<String> months(LocalDate today)
    {
        List<String> out = new ArrayList<>();

        for (int i = 0; i < MONTHS_KEPT; i++)
        {
            out.add(YearMonth.from(today).minusMonths(i).toString());
        }

        return out;
    }
}
