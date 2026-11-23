package com.pulse.platform.jobs;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;

/**
 * A five-field cron expression evaluated at minute precision (task 5.5): {@code minute hour
 * day-of-month month day-of-week}, in the company time zone (the caller passes local times).
 *
 * <p>Each field takes {@code *}, a number, a range {@code a-b}, a step {@code *}{@code /n} or
 * {@code a-b/n}, or a comma list of those. Day-of-week runs 0–7 with 0 and 7 both Sunday. Day-of-month
 * also takes {@code L}, the last day of the month. As in classic cron, when both day fields are
 * restricted a day matches if either matches.
 *
 * <pre>{@code
 * "0 16 * * 5"    Friday 16:00          "0 9 * * 1"     Monday 09:00
 * "0 10 L * *"    last day, 10:00       "30 2 * * *"    every day 02:30
 * }</pre>
 */
public final class CronSchedule
{
    private static final int MAX_SEARCH_MINUTES = 366 * 24 * 60 * 5;

    private final String expression;

    private final BitSet minutes;

    private final BitSet hours;

    private final BitSet days;

    private final boolean lastDay;

    private final BitSet months;

    private final BitSet weekdays;

    private final boolean anyDay;

    private final boolean anyWeekday;

    private CronSchedule(String expression, BitSet minutes, BitSet hours, BitSet days, boolean lastDay, BitSet months,
                         BitSet weekdays, boolean anyDay, boolean anyWeekday)
    {
        this.expression = expression;
        this.minutes = minutes;
        this.hours = hours;
        this.days = days;
        this.lastDay = lastDay;
        this.months = months;
        this.weekdays = weekdays;
        this.anyDay = anyDay;
        this.anyWeekday = anyWeekday;
    }

    /** Parses {@code expression}; throws {@link IllegalArgumentException} naming the bad field. */
    public static CronSchedule parse(String expression)
    {
        if (expression == null)
        {
            throw new IllegalArgumentException("Cron expression is missing");
        }

        String[] f = expression.trim().split("\\s+");

        if (f.length != 5)
        {
            throw new IllegalArgumentException("Cron '" + expression + "' needs 5 fields: minute hour day month weekday");
        }

        boolean lastDay = false;

        String dayField = f[2];

        if (dayField.equals("L"))
        {
            lastDay = true;
        }

        BitSet weekdays = field(expression, "weekday", f[4], 0, 7);

        if (weekdays.get(7))
        {
            weekdays.set(0);
            weekdays.clear(7);
        }

        return new CronSchedule(expression.trim(),
                field(expression, "minute", f[0], 0, 59),
                field(expression, "hour", f[1], 0, 23),
                lastDay ? new BitSet() : field(expression, "day", dayField, 1, 31),
                lastDay,
                field(expression, "month", f[3], 1, 12),
                weekdays,
                dayField.equals("*"),
                f[4].equals("*"));
    }

    /** True when the schedule fires in the minute of {@code time} (seconds are ignored). */
    public boolean matches(LocalDateTime time)
    {
        if (!minutes.get(time.getMinute()) || !hours.get(time.getHour()) || !months.get(time.getMonthValue()))
        {
            return false;
        }

        boolean day = lastDay
                ? time.getDayOfMonth() == YearMonth.from(time).lengthOfMonth()
                : days.get(time.getDayOfMonth());

        boolean weekday = weekdays.get(time.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : time.getDayOfWeek().getValue());

        if (anyWeekday)
        {
            return day;
        }

        if (anyDay && !lastDay)
        {
            return weekday;
        }

        return day || weekday;
    }

    /** The first minute strictly after {@code after} at which the schedule fires, or null within five years. */
    public LocalDateTime next(LocalDateTime after)
    {
        LocalDateTime t = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);

        for (int i = 0; i < MAX_SEARCH_MINUTES; i++)
        {
            if (matches(t))
            {
                return t;
            }

            t = t.plusMinutes(1);
        }

        return null;
    }

    public String expression()
    {
        return expression;
    }

    @Override
    public String toString()
    {
        return expression;
    }

    private static BitSet field(String expression, String name, String value, int min, int max)
    {
        BitSet bits = new BitSet(max + 1);

        for (String part : value.split(",", -1))
        {
            try
            {
                part(bits, part, min, max);
            }
            catch (RuntimeException e)
            {
                throw new IllegalArgumentException("Cron '" + expression + "': bad " + name + " '" + value + "'", e);
            }
        }

        if (bits.isEmpty())
        {
            throw new IllegalArgumentException("Cron '" + expression + "': empty " + name);
        }

        return bits;
    }

    private static void part(BitSet bits, String part, int min, int max)
    {
        int step = 1;

        String range = part;

        int slash = part.indexOf('/');

        if (slash >= 0)
        {
            step = Integer.parseInt(part.substring(slash + 1));
            range = part.substring(0, slash);

            if (step < 1)
            {
                throw new IllegalArgumentException("step must be at least 1");
            }
        }

        int from;

        int to;

        if (range.equals("*"))
        {
            from = min;
            to = max;
        }
        else if (range.contains("-"))
        {
            String[] ends = range.split("-", 2);

            from = Integer.parseInt(ends[0]);
            to = Integer.parseInt(ends[1]);
        }
        else
        {
            from = Integer.parseInt(range);
            to = slash >= 0 ? max : from;
        }

        if (from < min || to > max || from > to)
        {
            throw new IllegalArgumentException("out of range " + min + "-" + max);
        }

        for (int v = from; v <= to; v += step)
        {
            bits.set(v);
        }
    }
}
