package com.pulse.calc.work;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Hours logged from Jira work intervals (change jira-derived-hours, design.md decision 5; spec
 * work/effort-extra-mile "Hours from work intervals"). A work interval is the time an issue spent between its
 * Start and End fields; this splits one person's intervals into hours per day:
 *
 * <ol>
 *   <li>clip each interval to [start, min(end, now)] in Asia/Kolkata, never after today; an open interval
 *       (no end) counts up to now;</li>
 *   <li>on each working day of the person in that span (a weekday that is not a company holiday, a leave or a
 *       customer-visit day, as {@code WorkloadFormulas.Days#person}), take the overlap with the working-day
 *       window ({@code workday.start}-{@code workday.end});</li>
 *   <li>merge the person's overlapping pieces of the same day, so a Dev and a QA interval, or two issues in
 *       progress at once, count each hour once;</li>
 *   <li>cap the day at {@code dayHours}.</li>
 * </ol>
 *
 * <p>An interval without a start (a cleared field, or an issue that gained Jira time tracking) and one whose end
 * is not after its start count nothing. Effort item hours of one issue are the same split of that issue's own
 * intervals, before the merge with the person's other issues.
 *
 * <p>Input: {@code [{startsAt, endsAt}]} with ISO date-times with an offset (as PostgreSQL returns
 * {@code timestamptz}); {@code endsAt} null while open. Output: {@code {"2026-09-21": 4.0, ...}}, only days with
 * hours, in date order.
 */
public final class WorkIntervals
{

    /** The company time zone: the window and the days are read here. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private static final LocalTime DEFAULT_START = LocalTime.of(10, 0);

    private static final LocalTime DEFAULT_END = LocalTime.of(18, 0);

    private static final double SECONDS_PER_HOUR = 3600.0;

    private WorkIntervals()
    {
    }

    /**
     * One person's hours per day from {@code from} to {@code to} (both inclusive).
     *
     * @param intervals {@code [{startsAt, endsAt}]} of one person (or of one issue, for its Effort hours)
     * @param working   whether a day is one of the person's working days (weekday, no holiday, leave or visit)
     * @param rules     the rule set in force: {@code workday.start}, {@code workday.end}, {@code dayHours}
     * @param today     the company's today; no hours are counted after it
     * @param now       the current instant; an open interval counts up to it
     */
    public static JsonObject hours(JsonArray intervals, LocalDate from, LocalDate to, Predicate<LocalDate> working,
                                   JsonObject rules, LocalDate today, Instant now)
    {
        LocalTime windowStart = time(rules, "start", DEFAULT_START);

        LocalTime windowEnd = time(rules, "end", DEFAULT_END);

        double dayHours = rules.getDouble("dayHours", 8.0);

        JsonObject out = new JsonObject();

        if (!windowEnd.isAfter(windowStart) || intervals == null)
        {
            return out;
        }

        // never after today: the latest instant counted is the end of today, or now when it comes first
        Instant endOfToday = today.plusDays(1).atStartOfDay(ZONE).toInstant();

        Instant limit = now.isBefore(endOfToday) ? now : endOfToday;

        Map<LocalDate, List<Instant[]>> pieces = new TreeMap<>();

        for (Object item : intervals)
        {
            JsonObject interval = (JsonObject) item;

            Instant start = instant(interval.getValue("startsAt"));

            if (start == null)
            {
                continue;
            }

            Instant end = instant(interval.getValue("endsAt"));

            if (end != null && !end.isAfter(start))
            {
                continue;
            }

            Instant stop = end == null || end.isAfter(limit) ? limit : end;

            if (!stop.isAfter(start))
            {
                continue;
            }

            split(start, stop, from, to, working, windowStart, windowEnd, pieces);
        }

        for (Map.Entry<LocalDate, List<Instant[]>> day : pieces.entrySet())
        {
            double hours = Math.min(merged(day.getValue()) / SECONDS_PER_HOUR, dayHours);

            if (hours > 0)
            {
                out.put(day.getKey().toString(), hours);
            }
        }

        return out;
    }

    /** The sum of a {@link #hours} result. */
    public static double total(JsonObject hours)
    {
        double total = 0;

        for (String day : hours.fieldNames())
        {
            total += hours.getDouble(day);
        }

        return total;
    }

    // ------------------------------------------------------------------ helpers

    /** Adds the window overlap of [start, stop) on each working day in range to {@code pieces}. */
    private static void split(Instant start, Instant stop, LocalDate from, LocalDate to, Predicate<LocalDate> working,
                              LocalTime windowStart, LocalTime windowEnd, Map<LocalDate, List<Instant[]>> pieces)
    {
        LocalDate first = ZonedDateTime.ofInstant(start, ZONE).toLocalDate();

        LocalDate last = ZonedDateTime.ofInstant(stop, ZONE).toLocalDate();

        if (first.isBefore(from))
        {
            first = from;
        }

        if (last.isAfter(to))
        {
            last = to;
        }

        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1))
        {
            if (!working.test(day))
            {
                continue;
            }

            Instant opens = day.atTime(windowStart).atZone(ZONE).toInstant();

            Instant closes = day.atTime(windowEnd).atZone(ZONE).toInstant();

            Instant lo = start.isAfter(opens) ? start : opens;

            Instant hi = stop.isBefore(closes) ? stop : closes;

            if (hi.isAfter(lo))
            {
                pieces.computeIfAbsent(day, d -> new ArrayList<>()).add(new Instant[] {lo, hi});
            }
        }
    }

    /** Seconds covered by the union of the pieces of one day. */
    private static long merged(List<Instant[]> pieces)
    {
        pieces.sort((a, b) -> a[0].compareTo(b[0]));

        long seconds = 0;

        Instant lo = null;

        Instant hi = null;

        for (Instant[] piece : pieces)
        {
            if (hi == null || piece[0].isAfter(hi))
            {
                if (hi != null)
                {
                    seconds += hi.getEpochSecond() - lo.getEpochSecond();
                }

                lo = piece[0];
                hi = piece[1];
            }
            else if (piece[1].isAfter(hi))
            {
                hi = piece[1];
            }
        }

        if (hi != null)
        {
            seconds += hi.getEpochSecond() - lo.getEpochSecond();
        }

        return seconds;
    }

    private static LocalTime time(JsonObject rules, String key, LocalTime fallback)
    {
        JsonObject workday = rules.getJsonObject("workday");

        String value = workday == null ? null : workday.getString(key);

        if (value == null)
        {
            return fallback;
        }

        try
        {
            return LocalTime.parse(value.strip(), DateTimeFormatter.ofPattern("H:mm"));
        }
        catch (DateTimeParseException e)
        {
            return fallback;
        }
    }

    /** An ISO date-time with an offset ({@code 2026-09-21T14:00+05:30}, {@code ...Z}) or an {@link Instant}. */
    private static Instant instant(Object value)
    {
        if (value instanceof Instant i)
        {
            return i;
        }

        if (value instanceof OffsetDateTime o)
        {
            return o.toInstant();
        }

        if (value instanceof String s && !s.isBlank())
        {
            return OffsetDateTime.parse(s).toInstant();
        }

        return null;
    }
}
