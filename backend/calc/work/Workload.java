package com.pulse.calc.work;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;

/**
 * Live workload of a person in the active sprint (task 8.1, spec work/work-items and reference section 12
 * "Workload"), ported from the prototype's {@code band}, {@code idle}, {@code sprintElapsedPct},
 * {@code flags} and {@code metrics} (app.js ~158-186), plus the spec's remaining-hours fallback chain,
 * "no estimate" and the issue drawer's date and time checks. Pure functions over plain data.
 *
 * <p>An issue is a {@link JsonObject} with the {@code IssueRepository} keys: {@code key}, {@code status},
 * {@code storyPoints}, {@code estimateHours}, {@code spentHours}, {@code remainingHours}, {@code dueOn},
 * {@code startedOn}, plus {@code lastActivity} (ISO date or date-time of the assignee's last counted
 * activity). Rules are the rule set in force ({@code staleDays}, {@code notStartedPct}, {@code focus},
 * {@code under}, {@code healthy}, {@code over}). A working-day predicate answers for one person
 * (weekends, holidays, leave and visit days excluded) or for the company.
 */
public final class Workload
{

    /**
     * Utilization reported when no working day is left in the sprint (the prototype's 999).
     */
    public static final int NO_CAPACITY = 999;

    /**
     * "Estimate +50%": (spent + remaining) / original at or above this.
     */
    public static final double ESTIMATE_GROWTH = 1.5;

    /**
     * "Low logged time": spent / (working days in progress x focus x 0.75) below 0.3.
     */
    public static final double EXPECTED_SHARE_OF_FOCUS = 0.75;

    public static final double LOW_LOGGED = 0.3;

    /**
     * "Longer than estimated": in progress for more than twice the estimate (spec section 12 checks).
     */
    public static final double LONGER_THAN_ESTIMATE = 2;

    private Workload()
    {
    }

    /**
     * {@code band}: [tone, label] of a utilization %: Under below {@code under}, Healthy below
     * {@code healthy}, At capacity up to 100, Over up to {@code over}, then Critical.
     */
    public static JsonArray band(double utilization, JsonObject rules)
    {
        if (utilization < rules.getDouble("under", 60.0))
        {
            return new JsonArray().add("info").add("Under");
        }

        if (utilization < rules.getDouble("healthy", 85.0))
        {
            return new JsonArray().add("ok").add("Healthy");
        }

        if (utilization <= 100)
        {
            return new JsonArray().add("accent").add("At capacity");
        }

        if (utilization <= rules.getDouble("over", 120.0))
        {
            return new JsonArray().add("warn").add("Over");
        }

        return new JsonArray().add("danger").add("Critical");
    }

    /**
     * Remaining hours: the Jira remaining estimate, else original minus spent, else story points x
     * hours per point (calibrated over the last 3 sprints), else 0.
     */
    public static double remainingHours(JsonObject issue, double hoursPerPoint)
    {
        Double remaining = number(issue, "remainingHours");

        if (remaining != null)
        {
            return remaining;
        }

        Double estimate = number(issue, "estimateHours");

        if (estimate != null)
        {
            return Math.max(0, estimate - orZero(number(issue, "spentHours")));
        }

        Double points = number(issue, "storyPoints");

        return points == null ? 0 : points * hoursPerPoint;
    }

    /**
     * {@code idle}: working days after the last activity up to today (the day of the activity itself
     * does not count, today does).
     */
    public static int idleDays(LocalDate lastActivity, LocalDate today, Predicate<LocalDate> working)
    {
        return count(lastActivity.plusDays(1), today, working);
    }

    /**
     * {@code sprintElapsedPct}: working days of the sprint passed before today, as % of all its
     * working days (company calendar).
     */
    public static int sprintElapsedPct(LocalDate start, LocalDate workEnd, LocalDate today, Predicate<LocalDate> companyWorking)
    {
        int all = count(start, workEnd, companyWorking);

        int past = count(start, today, companyWorking) - 1;

        return all == 0 ? 0 : (int) Math.round(past * 100.0 / all);
    }

    /**
     * {@code flags}: the issue's signals as [tone, label] pairs, in the prototype's order: Overdue,
     * Stale, Not started, Estimate +50%, Low logged time. A Done issue has none.
     */
    public static JsonArray flags(JsonObject issue, LocalDate today, JsonObject rules, int sprintElapsedPct,
                                  Predicate<LocalDate> working)
    {
        JsonArray out = new JsonArray();

        String status = issue.getString("status");

        if ("Done".equals(status))
        {
            return out;
        }

        int idle = idleDays(lastActivityDay(issue), today, working);

        LocalDate due = date(issue, "dueOn");

        if (due != null && due.isBefore(today))
        {
            out.add(new JsonArray().add("danger").add("Overdue"));
        }

        if (("In Progress".equals(status) || "In Review".equals(status)) && idle > rules.getInteger("staleDays", 2))
        {
            out.add(new JsonArray().add("accent").add("Stale"));
        }

        if ("To Do".equals(status) && sprintElapsedPct > rules.getInteger("notStartedPct", 40))
        {
            out.add(new JsonArray().add("accent").add("Not started"));
        }

        Double estimate = number(issue, "estimateHours");

        double spent = orZero(number(issue, "spentHours"));

        double remaining = orZero(number(issue, "remainingHours"));

        if (estimate != null && estimate != 0 && (spent + remaining) / estimate >= ESTIMATE_GROWTH)
        {
            out.add(new JsonArray().add("warn").add("Estimate +50%"));
        }

        LocalDate started = date(issue, "startedOn");

        if (started != null)
        {
            double expected = count(started, today, working) * focus(rules) * EXPECTED_SHARE_OF_FOCUS;

            if (expected > 0 && spent / expected < LOW_LOGGED)
            {
                out.add(new JsonArray().add("info").add("Low logged time"));
            }
        }

        return out;
    }

    /**
     * "No estimate": neither an original estimate nor story points.
     */
    public static boolean noEstimate(JsonObject issue)
    {
        Double estimate = number(issue, "estimateHours");

        Double points = number(issue, "storyPoints");

        return (estimate == null || estimate == 0) && (points == null || points == 0);
    }

    /**
     * The issue drawer's "Date &amp; time verification": idle working days against the stale threshold,
     * logged against elapsed at 75% of focus, estimate drift, longer than estimated and days of work
     * left. Ratios are null when they cannot be judged.
     */
    public static JsonObject checks(JsonObject issue, LocalDate today, JsonObject rules, Predicate<LocalDate> working)
    {
        int idle = idleDays(lastActivityDay(issue), today, working);

        String status = issue.getString("status");

        LocalDate started = date(issue, "startedOn");

        int inProgress = started == null ? 0 : count(started, today, working);

        double focus = focus(rules);

        double expected = inProgress * focus * EXPECTED_SHARE_OF_FOCUS;

        double spent = orZero(number(issue, "spentHours"));

        double remaining = orZero(number(issue, "remainingHours"));

        Double estimate = number(issue, "estimateHours");

        Double logged = expected > 0 ? spent / expected : null;

        Double drift = estimate == null || estimate == 0 ? null : (spent + remaining) / estimate;

        Double estimateDays = estimate == null || estimate == 0 ? null : estimate / focus;

        boolean judged = !"Done".equals(status) && !"To Do".equals(status);

        return new JsonObject()
                .put("idleDays", idle)
                .put("staleAfter", rules.getInteger("staleDays", 2))
                .put("stale", judged && idle > rules.getInteger("staleDays", 2))
                .put("workingDaysInProgress", inProgress)
                .put("expectedHours", expected)
                .put("loggedShare", logged)
                .put("lowLogged", logged != null && logged < LOW_LOGGED)
                .put("estimateDrift", drift)
                .put("estimateGrew", drift != null && drift >= ESTIMATE_GROWTH)
                .put("longerThanEstimate", estimateDays != null && inProgress > LONGER_THAN_ESTIMATE * estimateDays)
                .put("daysLeft", remaining / focus);
    }

    /**
     * The open issues of a live sprint, {@code [{key, rem, due}]} with the remaining hours of
     * {@link #remainingHours}, for the sprint forecast ({@link ActionCenter#forecast}).
     */
    public static JsonArray openIssues(List<JsonObject> issues, double hoursPerPoint)
    {
        JsonArray out = new JsonArray();

        for (JsonObject issue : issues)
        {
            if (!"Done".equals(issue.getString("status")))
            {
                out.add(new JsonObject()
                        .put("key", issue.getString("key"))
                        .put("rem", remainingHours(issue, hoursPerPoint))
                        .put("due", issue.getString("dueOn")));
            }
        }

        return out;
    }

    /**
     * {@code metrics}: the live sprint of one person: open, done and stale issue keys, remaining hours,
     * days of work, capacity (working days from today to the sprint's last working day), utilization
     * with its band, story points and the oldest idle stale issue. Stale here means any of Stale,
     * Overdue or Not started.
     */
    public static JsonObject live(List<JsonObject> issues, LocalDate today, LocalDate sprintWorkEnd, JsonObject rules,
                                  int sprintElapsedPct, Predicate<LocalDate> working, double hoursPerPoint)
    {
        JsonArray open = new JsonArray();

        JsonArray done = new JsonArray();

        JsonArray stale = new JsonArray();

        double remaining = 0;

        double points = 0;

        int oldest = 0;

        for (JsonObject issue : issues)
        {
            points += orZero(number(issue, "storyPoints"));

            if ("Done".equals(issue.getString("status")))
            {
                done.add(issue.getString("key"));

                continue;
            }

            open.add(issue.getString("key"));

            remaining += remainingHours(issue, hoursPerPoint);

            JsonArray flags = flags(issue, today, rules, sprintElapsedPct, working);

            boolean signal = flags.stream().map(f -> ((JsonArray) f).getString(1))
                    .anyMatch(label -> "Stale".equals(label) || "Overdue".equals(label) || "Not started".equals(label));

            if (signal)
            {
                stale.add(issue.getString("key"));

                oldest = Math.max(oldest, idleDays(lastActivityDay(issue), today, working));
            }
        }

        double work = remaining / focus(rules);

        int capacity = count(today, sprintWorkEnd, working);

        int utilization = capacity == 0 ? NO_CAPACITY : (int) Math.round(work / capacity * 100);

        return new JsonObject()
                .put("open", open)
                .put("rem", remaining)
                .put("work", work)
                .put("cap", capacity)
                .put("util", utilization)
                .put("band", band(utilization, rules))
                .put("stale", stale)
                .put("sp", points)
                .put("done", done)
                .put("oldest", oldest);
    }

    /**
     * Working days from {@code from} to {@code to}, both included.
     */
    public static int count(LocalDate from, LocalDate to, Predicate<LocalDate> working)
    {
        int n = 0;

        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1))
        {
            if (working.test(day))
            {
                n++;
            }
        }

        return n;
    }

    private static double focus(JsonObject rules)
    {
        return rules.getDouble("focus", 6.0);
    }

    /**
     * Working days the issue has been idle (see {@link #idleDays}); null when it has no last activity.
     */
    public static Integer idle(JsonObject issue, LocalDate today, Predicate<LocalDate> working)
    {
        return issue.getString("lastActivity") == null ? null : idleDays(lastActivityDay(issue), today, working);
    }

    private static LocalDate lastActivityDay(JsonObject issue)
    {
        String last = issue.getString("lastActivity");

        return LocalDate.parse(last.substring(0, 10));
    }

    private static LocalDate date(JsonObject issue, String key)
    {
        String value = issue.getString(key);

        return value == null ? null : LocalDate.parse(value.substring(0, 10));
    }

    static Double number(JsonObject row, String key)
    {
        Object value = row.getValue(key);

        if (value == null)
        {
            return null;
        }

        return value instanceof Number n ? n.doubleValue() : Double.valueOf(value.toString());
    }

    static double orZero(Double value)
    {
        return value == null ? 0 : value;
    }
}
