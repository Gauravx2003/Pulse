package com.pulse.calc.work;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Delivery in a period (task 8.1; reference section 12 "Sprint metrics"), ported from the prototype's
 * {@code perMetrics} and {@code median} (app.js ~2018, ~1541): story points committed and done,
 * commitment %, carried over, bugs, issues still open, median cycle time, estimate accuracy and the
 * issues that went stale, with the period's time from {@link PeriodWork}. Plus the spec's reopen rate.
 *
 * <p>An issue is {@code {key, issueType, status, storyPoints, estimateHours, spentHours, cycle,
 * carriedOver, wasStale, live, reopened}}: {@code cycle} in working days from {@link #cycleDays},
 * {@code live} for an issue of the active sprint.
 */
public final class SprintMetrics
{

    private SprintMetrics()
    {
    }

    /**
     * {@code {com, dsp, commit, carry, bugs, open, cyc, logged, work, cover, est, stale}}: commitment
     * and estimate accuracy are % (null without committed points or done issues), {@code carry},
     * {@code bugs} and {@code open} are issue keys.
     */
    public static JsonObject of(List<JsonObject> issues, JsonObject periodWork)
    {
        double committed = 0;

        double donePoints = 0;

        double doneSpent = 0;

        double doneEstimate = 0;

        int doneCount = 0;

        int stale = 0;

        JsonArray carry = new JsonArray();

        JsonArray bugs = new JsonArray();

        JsonArray open = new JsonArray();

        List<Double> cycles = new ArrayList<>();

        for (JsonObject issue : issues)
        {
            double points = Workload.orZero(Workload.number(issue, "storyPoints"));

            committed += points;

            boolean done = "Done".equals(issue.getString("status"));

            if (done)
            {
                doneCount++;

                donePoints += points;

                doneSpent += Workload.orZero(Workload.number(issue, "spentHours"));

                doneEstimate += Workload.orZero(Workload.number(issue, "estimateHours"));

                Double cycle = Workload.number(issue, "cycle");

                if (cycle != null && cycle != 0)
                {
                    cycles.add(cycle);
                }
            }

            if (issue.getBoolean("carriedOver", false))
            {
                carry.add(issue.getString("key"));
            }

            if ("Bug".equals(issue.getString("issueType")))
            {
                bugs.add(issue.getString("key"));
            }

            if (issue.getBoolean("live", false) && !done)
            {
                open.add(issue.getString("key"));
            }

            if (issue.getBoolean("wasStale", false))
            {
                stale++;
            }
        }

        Integer commit = committed == 0 ? null : (int) Math.round(donePoints / committed * 100);

        Integer accuracy = doneCount == 0 || doneEstimate == 0 ? null : (int) Math.round(doneSpent / doneEstimate * 100);

        return new JsonObject()
                .put("com", committed)
                .put("dsp", donePoints)
                .put("commit", commit)
                .put("carry", carry)
                .put("bugs", bugs)
                .put("open", open)
                .put("cyc", median(cycles))
                .put("logged", periodWork.getValue("logged"))
                .put("work", periodWork.getValue("work"))
                .put("cover", periodWork.getValue("cover"))
                .put("est", accuracy)
                .put("stale", stale);
    }

    /**
     * Cycle time: working days from the first move to In Progress to resolution, both included
     * (company calendar); null while unresolved.
     */
    public static Integer cycleDays(LocalDate started, LocalDate resolved, Predicate<LocalDate> companyWorking)
    {
        if (started == null || resolved == null)
        {
            return null;
        }

        return Workload.count(started, resolved, companyWorking);
    }

    /**
     * Reopen rate (spec section 12): done issues reopened within 14 days, as % of done issues; null
     * without done issues.
     */
    public static Integer reopenRate(List<JsonObject> issues)
    {
        int done = 0;

        int reopened = 0;

        for (JsonObject issue : issues)
        {
            if ("Done".equals(issue.getString("status")))
            {
                done++;

                if (issue.getBoolean("reopened", false))
                {
                    reopened++;
                }
            }
        }

        return done == 0 ? null : (int) Math.round(reopened * 100.0 / done);
    }

    /**
     * {@code median}: the middle value, the mean of the two middle values for an even count, 0 for none.
     */
    public static double median(List<Double> values)
    {
        if (values.isEmpty())
        {
            return 0;
        }

        List<Double> sorted = new ArrayList<>(values);

        Collections.sort(sorted);

        int middle = sorted.size() / 2;

        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }
}
