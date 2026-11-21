package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The working-day calendar (task 4.1, spec platform/period-calendar "Working-day definition"),
 * ported from the prototype's {@code isWork} (app.js line 155) plus the joining-date rule.
 *
 * <p>A working day is a weekday that is not a company holiday, not a leave day, not a customer-visit
 * day for that person and not before that person's joining date. Every denominator (capacity,
 * daily-update compliance, office policy, worklog checks) uses {@link #list} or {@link #count}.
 *
 * <p>Pure functions over plain data. A person is a {@link JsonObject}, all fields optional:
 * <pre>{@code
 * {"joinedOn": "2026-09-08",
 *  "location": "Pune",                                   // selects site-specific holidays
 *  "leave":    ["2026-09-22"],                           // leave days (leave calendar or attendance upload)
 *  "visits":   [{"from": "2026-09-24", "to": "2026-09-25"}]}
 * }</pre>
 * With no person, only weekends and holidays are excluded (the company calendar).
 */
public final class WorkingDays
{
    /** A working day. */
    public static final String WORKING = "working";

    public static final String WEEKEND = "weekend";

    public static final String HOLIDAY = "holiday";

    public static final String LEAVE = "leave";

    public static final String VISIT = "visit";

    /** Before the person joined: shown as "—" and excluded everywhere. */
    public static final String BEFORE_JOINING = "before_joining";

    private WorkingDays()
    {
    }

    /**
     * The holiday dates that apply at {@code location}: rows with no location apply everywhere.
     * {@code holidays} rows are {@code {date, name, location?}}.
     */
    public static Set<LocalDate> holidays(JsonArray holidays, String location)
    {
        Set<LocalDate> out = new HashSet<>();

        if (holidays == null)
        {
            return out;
        }

        for (int i = 0; i < holidays.size(); i++)
        {
            JsonObject h = holidays.getJsonObject(i);

            String site = h.getString("location");

            if (site == null || site.isBlank() || site.equalsIgnoreCase(location == null ? "" : location))
            {
                out.add(LocalDate.parse(h.getString("date")));
            }
        }

        return out;
    }

    /**
     * Why {@code day} is or is not a working day for {@code person} (null: company calendar). When
     * several reasons apply, the first in this order wins: before joining, weekend, holiday, leave,
     * visit.
     */
    public static String status(LocalDate day, Set<LocalDate> holidays, JsonObject person)
    {
        if (person != null && person.getString("joinedOn") != null
                && day.isBefore(LocalDate.parse(person.getString("joinedOn"))))
        {
            return BEFORE_JOINING;
        }

        DayOfWeek dow = day.getDayOfWeek();

        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
        {
            return WEEKEND;
        }

        if (holidays != null && holidays.contains(day))
        {
            return HOLIDAY;
        }

        if (person == null)
        {
            return WORKING;
        }

        if (contains(person.getJsonArray("leave"), day))
        {
            return LEAVE;
        }

        if (onVisit(person.getJsonArray("visits"), day))
        {
            return VISIT;
        }

        return WORKING;
    }

    public static boolean isWorking(LocalDate day, Set<LocalDate> holidays, JsonObject person)
    {
        return WORKING.equals(status(day, holidays, person));
    }

    /** The working days from {@code from} to {@code to}, both inclusive, in order. */
    public static List<LocalDate> list(LocalDate from, LocalDate to, Set<LocalDate> holidays, JsonObject person)
    {
        List<LocalDate> out = new ArrayList<>();

        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1))
        {
            if (isWorking(day, holidays, person))
            {
                out.add(day);
            }
        }

        return out;
    }

    /** The number of working days from {@code from} to {@code to}, both inclusive. */
    public static int count(LocalDate from, LocalDate to, Set<LocalDate> holidays, JsonObject person)
    {
        return list(from, to, holidays, person).size();
    }

    /** The working days of a period ({@link PeriodCalendar#period}: {@code from} to {@code to}). */
    public static List<LocalDate> inPeriod(JsonObject period, Set<LocalDate> holidays, JsonObject person)
    {
        return list(LocalDate.parse(period.getString("from")), LocalDate.parse(period.getString("to")), holidays, person);
    }

    private static boolean contains(JsonArray dates, LocalDate day)
    {
        if (dates == null)
        {
            return false;
        }

        String iso = day.toString();

        for (int i = 0; i < dates.size(); i++)
        {
            if (iso.equals(dates.getString(i)))
            {
                return true;
            }
        }

        return false;
    }

    private static boolean onVisit(JsonArray visits, LocalDate day)
    {
        if (visits == null)
        {
            return false;
        }

        String iso = day.toString();

        for (int i = 0; i < visits.size(); i++)
        {
            JsonObject v = visits.getJsonObject(i);

            if (iso.compareTo(v.getString("from")) >= 0 && iso.compareTo(v.getString("to")) <= 0)
            {
                return true;
            }
        }

        return false;
    }
}
