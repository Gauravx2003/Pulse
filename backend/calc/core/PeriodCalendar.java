package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The period model (task 4.1, spec platform/period-calendar "Period keys"), ported from the
 * prototype's {@code perOf} / {@code curPer} (app.js lines ~1838 and ~1983).
 *
 * <p>Keys: sprint {@code s:S43}, month {@code m:2026-09}, release {@code r:8.2}, quarter
 * {@code q:Q3} (a calendar quarter of today's year; {@code q:2026-Q3} names the year) and {@code all}.
 *
 * <p>{@link #period(String)} resolves a key to:
 * <pre>{@code
 * {"kind": "sprint" | "month" | "release" | "quarter" | "all",
 *  "key": "s:S43",
 *  "from": "2026-09-21",      // first day
 *  "to":   "2026-10-11",      // last day: a sprint runs to the Sunday before the next sprint
 *  "end":  "2026-10-09",      // last working day of a sprint, release date of a release, else = to
 *  "days": 21,                // calendar days from..to
 *  "label": "Sprint 43 · 21 Sep – 9 Oct (active)",
 *  "short": "Sprint 43",     // the sprint's name when it has one (see below)
 *  "live": true}              // to >= today: live data, otherwise the snapshot taken at close
 * }</pre>
 *
 * <p>A sprint's {@code short} is its name (a live Jira sprint's name, {@code sprints.name}), else
 * {@code "Sprint " + number}; when two sprints of the calendar share a {@code short}, both get
 * {@code " (" + key + ")"} appended, as {@code "Sprint 12 (J140)"} (change jira-sprint-names, spec
 * platform/period-calendar "Sprint names"). Its {@code label} is {@code "<short> · <range>"}, plus
 * {@code " (active)"}. A rename changes only these labels, never the key.
 *
 * <p>Sprints and releases come from their tables, as rows
 * {@code {key, number, name, startsOn, workEndsOn, endsOn}} ({@code name} null or absent when none) and
 * {@code {key, name, startsOn, endsOn, releaseDate, status}}. Sprints can be any length (S43 is three
 * weeks). An unknown or malformed key fails with {@link IllegalArgumentException}; the HTTP layer
 * answers it as 400 {@code invalid_parameter}.
 *
 * <p>Immutable: build one per request, or cache one per day.
 */
public final class PeriodCalendar
{
    public static final String SPRINT = "sprint";

    public static final String MONTH = "month";

    public static final String RELEASE = "release";

    public static final String QUARTER = "quarter";

    public static final String ALL = "all";

    private static final Pattern MONTH_KEY = Pattern.compile("m:(\\d{4}-\\d{2})");

    private static final Pattern QUARTER_KEY = Pattern.compile("q:(?:(\\d{4})-)?Q([1-4])");

    private final List<JsonObject> sprints;

    private final List<JsonObject> releases;

    /** Each sprint's {@code short} by its key, clashes already told apart. */
    private final Map<String, String> sprintShorts;

    private final LocalDate today;

    /**
     * @param sprints  sprint rows, any order
     * @param releases release rows, any order
     * @param today    from {@link PulseClock#today()}
     */
    public PeriodCalendar(JsonArray sprints, JsonArray releases, LocalDate today)
    {
        this.sprints = sorted(sprints, "startsOn");
        this.releases = sorted(releases, "startsOn");
        this.sprintShorts = shorts(this.sprints);
        this.today = today;
    }

    public LocalDate today()
    {
        return today;
    }

    /** Resolves a period key; see the class comment. */
    public JsonObject period(String key)
    {
        if (key == null || key.isBlank())
        {
            throw new IllegalArgumentException("A period key is required");
        }

        if (key.equals(ALL))
        {
            return all();
        }

        if (key.startsWith("s:"))
        {
            return sprint(find(sprints, key.substring(2), "sprint"));
        }

        if (key.startsWith("r:"))
        {
            return release(find(releases, key.substring(2), "release"));
        }

        Matcher month = MONTH_KEY.matcher(key);

        if (month.matches())
        {
            try
            {
                return month(YearMonth.parse(month.group(1)));
            }
            catch (DateTimeParseException e)
            {
                throw new IllegalArgumentException("Unknown period: " + key);
            }
        }

        Matcher quarter = QUARTER_KEY.matcher(key);

        if (quarter.matches())
        {
            int year = quarter.group(1) == null ? today.getYear() : Integer.parseInt(quarter.group(1));

            return quarter(year, Integer.parseInt(quarter.group(2)));
        }

        throw new IllegalArgumentException("Unknown period: " + key);
    }

    /** The sprint that contains today, or null when today is outside every sprint. */
    public JsonObject activeSprint()
    {
        JsonObject row = sprintRowOn(today);

        return row == null ? null : sprint(row);
    }

    /** The sprint that contains {@code day}, or null. */
    public JsonObject sprintOn(LocalDate day)
    {
        JsonObject row = sprintRowOn(day);

        return row == null ? null : sprint(row);
    }

    /** Every sprint as a period, newest first (the "sprint by sprint" table). */
    public JsonArray sprints()
    {
        JsonArray out = new JsonArray();

        for (int i = sprints.size() - 1; i >= 0; i--)
        {
            out.add(sprint(sprints.get(i)));
        }

        return out;
    }

    /** Every release as a period, newest first. */
    public JsonArray releases()
    {
        JsonArray out = new JsonArray();

        for (int i = releases.size() - 1; i >= 0; i--)
        {
            out.add(release(releases.get(i)));
        }

        return out;
    }

    /** The {@code n} months ending with the current month, newest first (the "month by month" table). */
    public JsonArray months(int n)
    {
        JsonArray out = new JsonArray();

        YearMonth m = YearMonth.from(today);

        for (int i = 0; i < n; i++)
        {
            out.add(month(m.minusMonths(i)));
        }

        return out;
    }

    /** True when {@code day} falls in the period (inclusive); always true for {@code all}. */
    public static boolean contains(JsonObject period, LocalDate day)
    {
        if (ALL.equals(period.getString("kind")))
        {
            return true;
        }

        String iso = day.toString();

        return iso.compareTo(period.getString("from")) >= 0 && iso.compareTo(period.getString("to")) <= 0;
    }

    // ------------------------------------------------------------------ builders

    private JsonObject sprint(JsonObject row)
    {
        LocalDate from = date(row, "startsOn");
        LocalDate to = date(row, "endsOn");
        LocalDate end = date(row, "workEndsOn");

        boolean live = !to.isBefore(today);

        String name = sprintShorts.get(row.getString("key"));

        return period(SPRINT, "s:" + row.getString("key"), from, to, end,
                name + " · " + CalendarFormat.range(from, end) + (live ? " (active)" : ""), name, live);
    }

    private JsonObject release(JsonObject row)
    {
        LocalDate from = date(row, "startsOn");
        LocalDate to = date(row, "endsOn");
        LocalDate end = row.getString("releaseDate") == null ? to : date(row, "releaseDate");

        boolean live = !to.isBefore(today);

        String state = "Released".equals(row.getString("status")) ? "released" : "in progress";

        String name = "Release " + row.getString("key");

        return period(RELEASE, "r:" + row.getString("key"), from, to, end,
                name + " · " + CalendarFormat.range(from, end) + " (" + state + ")", name, live);
    }

    private JsonObject month(YearMonth m)
    {
        LocalDate from = m.atDay(1);
        LocalDate to = m.atEndOfMonth();

        boolean live = !to.isBefore(today);

        String name = CalendarFormat.monthYear(from);

        return period(MONTH, "m:" + m, from, to, to, name + (live ? " (to date)" : ""), name, live);
    }

    private JsonObject quarter(int year, int q)
    {
        LocalDate from = LocalDate.of(year, (q - 1) * 3 + 1, 1);
        LocalDate to = YearMonth.of(year, q * 3).atEndOfMonth();

        boolean live = !to.isBefore(today);

        String name = "Q" + q + " " + year;

        String key = year == today.getYear() ? "q:Q" + q : "q:" + year + "-Q" + q;

        return period(QUARTER, key, from, to, to, name + (live ? " (to date)" : ""), name, live);
    }

    /**
     * {@code all}: from the first of the month of the earliest sprint or release to the last day of
     * the latest one (the prototype's "All · Apr – Sep 2026"). With neither, the year to date.
     */
    private JsonObject all()
    {
        LocalDate first = null;
        LocalDate last = null;

        List<JsonObject> rows = new ArrayList<>(sprints);

        rows.addAll(releases);

        for (JsonObject row : rows)
        {
            LocalDate from = date(row, "startsOn");
            LocalDate to = date(row, "endsOn");

            first = first == null || from.isBefore(first) ? from : first;
            last = last == null || to.isAfter(last) ? to : last;
        }

        LocalDate from = first == null ? today.withDayOfYear(1) : first.withDayOfMonth(1);
        LocalDate to = last == null ? today : last;

        LocalDate shownTo = to.isAfter(today) ? today : to;

        String span = from.getYear() == shownTo.getYear()
                ? CalendarFormat.monthYear(from).substring(0, 3) + " – " + CalendarFormat.monthYear(shownTo)
                : CalendarFormat.monthYear(from) + " – " + CalendarFormat.monthYear(shownTo);

        return period(ALL, ALL, from, to, to, "All · " + span, span, !to.isBefore(today));
    }

    private static JsonObject period(String kind, String key, LocalDate from, LocalDate to, LocalDate end,
                                     String label, String shortLabel, boolean live)
    {
        return new JsonObject()
                .put("kind", kind)
                .put("key", key)
                .put("from", from.toString())
                .put("to", to.toString())
                .put("end", end.toString())
                .put("days", ChronoUnit.DAYS.between(from, to) + 1)
                .put("label", label)
                .put("short", shortLabel)
                .put("live", live);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The {@code short} of every sprint: its trimmed name, else {@code "Sprint " + number}; the sprints
     * that share one each get {@code " (" + key + ")"} appended.
     */
    private static Map<String, String> shorts(List<JsonObject> sprints)
    {
        Map<String, String> plain = new HashMap<>();

        Map<String, Integer> uses = new HashMap<>();

        for (JsonObject row : sprints)
        {
            String name = row.getString("name");

            String shortName = name == null || name.isBlank() ? "Sprint " + row.getValue("number") : name.strip();

            plain.put(row.getString("key"), shortName);

            uses.merge(shortName, 1, Integer::sum);
        }

        Map<String, String> out = new HashMap<>();

        for (Map.Entry<String, String> sprint : plain.entrySet())
        {
            String shortName = sprint.getValue();

            out.put(sprint.getKey(), uses.get(shortName) > 1 ? shortName + " (" + sprint.getKey() + ")" : shortName);
        }

        return out;
    }

    private JsonObject sprintRowOn(LocalDate day)
    {
        for (JsonObject row : sprints)
        {
            if (!day.isBefore(date(row, "startsOn")) && !day.isAfter(date(row, "endsOn")))
            {
                return row;
            }
        }

        return null;
    }

    private static JsonObject find(List<JsonObject> rows, String id, String what)
    {
        for (JsonObject row : rows)
        {
            if (id.equals(row.getString("key")))
            {
                return row;
            }
        }

        throw new IllegalArgumentException("Unknown " + what + ": " + id);
    }

    private static List<JsonObject> sorted(JsonArray rows, String field)
    {
        List<JsonObject> out = new ArrayList<>();

        if (rows != null)
        {
            for (int i = 0; i < rows.size(); i++)
            {
                out.add(rows.getJsonObject(i));
            }
        }

        out.sort(Comparator.comparing(row -> row.getString(field)));

        return out;
    }

    private static LocalDate date(JsonObject row, String field)
    {
        return LocalDate.parse(row.getString(field));
    }
}
