package com.pulse.calc.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Date and time display (task 4.1, spec platform/period-calendar "Clock and time zone"): dates as
 * "28 Sep" (en-GB, no leading zero), times as 24-hour "09:05", both in the company time zone
 * {@link PulseClock#ZONE}, whatever the viewer's browser zone.
 *
 * <p>Month names are fixed here rather than taken from the JDK locale data, which spells
 * September "Sept" for en-GB; the prototype and the screens use "Sep".
 */
public final class CalendarFormat
{
    private static final String[] MONTHS = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    private CalendarFormat()
    {
    }

    /** "28 Sep". */
    public static String date(LocalDate day)
    {
        return day.getDayOfMonth() + " " + month(day);
    }

    /** "28 Sep 2026". */
    public static String dateWithYear(LocalDate day)
    {
        return date(day) + " " + day.getYear();
    }

    /** "Sep 2026". */
    public static String monthYear(LocalDate day)
    {
        return month(day) + " " + day.getYear();
    }

    /** "21 Sep – 9 Oct". */
    public static String range(LocalDate from, LocalDate to)
    {
        return date(from) + " – " + date(to);
    }

    /** The company wall-clock time of an instant: a check-in at 05:01Z is 10:31. */
    public static LocalTime companyTime(Instant instant)
    {
        return instant.atZone(PulseClock.ZONE).toLocalTime();
    }

    /** The company date of an instant. */
    public static LocalDate companyDate(Instant instant)
    {
        return instant.atZone(PulseClock.ZONE).toLocalDate();
    }

    /** 24-hour "10:31" in the company time zone. */
    public static String time(Instant instant)
    {
        LocalTime t = companyTime(instant);

        return two(t.getHour()) + ":" + two(t.getMinute());
    }

    /** "28 Sep, 15:30" in the company time zone. */
    public static String dateTime(Instant instant)
    {
        ZonedDateTime at = instant.atZone(PulseClock.ZONE);

        return date(at.toLocalDate()) + ", " + time(instant);
    }

    private static String month(LocalDate day)
    {
        return MONTHS[day.getMonthValue() - 1];
    }

    private static String two(int n)
    {
        return n < 10 ? "0" + n : String.valueOf(n);
    }
}
