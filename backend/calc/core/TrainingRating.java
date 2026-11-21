package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The training rating (task 4.7, spec growth/training-mentoring "Training rating"), ported from the
 * prototype's {@code periods}, {@code pStat}, {@code ciStats} (app.js ~1158–1166), {@code trCpAvg},
 * {@code trRating}, {@code trBand} and {@code trPerson} (app.js ~1651–1657).
 *
 * <p>The rating is the weighted average of the parts that exist, re-weighted over those parts:
 * <ul>
 *   <li>Assessment: score % ÷ 20, weight rule {@code trWa} (40);</li>
 *   <li>Mentor final review 1–5, weight {@code trWm} (20);</li>
 *   <li>Manager final review 1–5, weight {@code trWg} (20);</li>
 *   <li>Check-ins verified: 1 + 4 × verified ÷ closed (verified, needs work and missed), weight
 *       {@code trWc} (10);</li>
 *   <li>Checkpoint reviews: the average of every non-self score in the submitted checkpoint 360°
 *       forms, weight {@code trWp} (10).</li>
 * </ul>
 * The rating is Final once the assessment and both final reviews exist, Provisional before that, and
 * Not rated with no part at all. Bands: Outstanding ≥ 4.5, Strong ≥ 3.75, Meets ≥ 3, Needs support
 * ≥ 2.25, else Not yet. A person's overall rating ({@link #person}) is the plan-length-weighted average
 * of the Final ratings, or of the Provisional ones while none is Final.
 *
 * <p>Pure functions over plain data. An assignment ({@link #rating}, {@link #checkIns}):
 * <pre>{@code
 * {"start": "2026-09-14", "end": null,            // end of the training period; null = start + weeks × 7 − 1
 *  "weeks": 12, "cadence": "Weekly",               // Daily | Weekly | Monthly
 *  "updates": [{"period": "2026-09-14", "status": "Verified"}, ...],  // Submitted | Verified | Needs work
 *  "assess": {"score": 82, "result": "Pass"},      // or null
 *  "final": {"mentor": {"rating": 4, "by": "Priya Shah"}, "manager": {"rating": 4, "by": "Rahul Nair"}}}
 * }</pre>
 * Checkpoint requests ({@link #checkpointAverage}) are the 360° requests of the assignment's checkpoint
 * cycles: {@code {group, status, scores: [n | null, ...]}}. Rules: {@code {trWa, trWm, trWg, trWc, trWp}}.
 */
public final class TrainingRating
{
    public static final String FINAL = "Final";

    public static final String PROVISIONAL = "Provisional";

    public static final String NOT_RATED = "Not rated";

    public static final String VERIFIED = "Verified";

    public static final String SUBMITTED = "Submitted";

    public static final String NEEDS_WORK = "Needs work";

    public static final String MISSED = "Missed";

    public static final String DUE = "Due";

    private TrainingRating()
    {
    }

    // ------------------------------------------------------------------ check-ins

    /** {@code trEnd}: the last day of the training period. */
    public static LocalDate end(JsonObject assignment)
    {
        String end = assignment.getString("end");

        if (end != null)
        {
            return LocalDate.parse(end);
        }

        return LocalDate.parse(assignment.getString("start")).plusDays(assignment.getInteger("weeks") * 7L - 1);
    }

    /**
     * {@code periods}: the check-in periods from the start to the end of the training period or today,
     * latest first. Daily periods are working days (weekends and holidays left out); weekly periods run
     * Monday to Friday, the first one from the start; monthly periods are calendar months, the first one
     * from the start.
     *
     * @return {@code [{key, label, from, to, current}]}; {@code current} when the period has not ended
     */
    public static JsonArray periods(JsonObject assignment, Set<LocalDate> holidays, LocalDate today)
    {
        String cadence = assignment.getString("cadence", "Weekly");

        LocalDate start = LocalDate.parse(assignment.getString("start"));
        LocalDate planEnd = end(assignment);
        LocalDate last = planEnd.isBefore(today) ? planEnd : today;

        List<JsonObject> out = new ArrayList<>();

        if ("Daily".equals(cadence))
        {
            for (LocalDate d : WorkingDays.list(start, last, holidays, null))
            {
                out.add(period(d.toString(), CalendarFormat.date(d), d, d, !d.isBefore(today)));
            }
        }
        else if ("Weekly".equals(cadence))
        {
            LocalDate m = start.with(DayOfWeek.MONDAY);

            while (!m.isAfter(last))
            {
                LocalDate to = m.plusDays(4);

                out.add(period(m.toString(), "Week of " + CalendarFormat.date(m), m.isBefore(start) ? start : m, to,
                        !to.isBefore(today)));

                m = m.plusDays(7);
            }
        }
        else
        {
            LocalDate x = start.withDayOfMonth(1);

            while (!x.isAfter(last))
            {
                LocalDate to = x.withDayOfMonth(x.lengthOfMonth());

                out.add(period(x.toString().substring(0, 7), CalendarFormat.monthYear(x), x.isBefore(start) ? start : x,
                        to, !to.isBefore(today)));

                x = x.plusMonths(1);
            }
        }

        Collections.reverse(out);

        return new JsonArray(new ArrayList<>(out));
    }

    /**
     * {@code ciStats}: each check-in period with its status ({@code pStat}: the update's status, else
     * Due while the period is current, else Missed) and the counts.
     *
     * @return {@code {periods: [{key, label, from, to, current, status}], verified, submitted, needsWork,
     *         missed, total}}
     */
    public static JsonObject checkIns(JsonObject assignment, Set<LocalDate> holidays, LocalDate today)
    {
        JsonArray periods = periods(assignment, holidays, today);

        JsonArray updates = assignment.getJsonArray("updates") == null ? new JsonArray() : assignment.getJsonArray("updates");

        int verified = 0;
        int submitted = 0;
        int needsWork = 0;
        int missed = 0;

        for (Object o : periods)
        {
            JsonObject p = (JsonObject) o;

            String status = status(updates, p);

            p.put("status", status);

            switch (status)
            {
                case VERIFIED -> verified++;
                case SUBMITTED -> submitted++;
                case NEEDS_WORK -> needsWork++;
                case MISSED -> missed++;
                default ->
                {
                }
            }
        }

        return new JsonObject()
                .put("periods", periods)
                .put("verified", verified)
                .put("submitted", submitted)
                .put("needsWork", needsWork)
                .put("missed", missed)
                .put("total", periods.size());
    }

    /**
     * {@code trCpAvg}: the average of every score given in the submitted checkpoint reviews, self
     * reviews left out; null when there is none.
     */
    public static Double checkpointAverage(JsonArray requests)
    {
        double sum = 0;

        int n = 0;

        for (Object o : requests == null ? new JsonArray() : requests)
        {
            JsonObject r = (JsonObject) o;

            if (!SUBMITTED.equals(r.getString("status")) || "self".equals(r.getString("group")))
            {
                continue;
            }

            for (Object x : r.getJsonArray("scores", new JsonArray()))
            {
                if (x != null)
                {
                    sum += ((Number) x).doubleValue();
                    n++;
                }
            }
        }

        return n == 0 ? null : sum / n;
    }

    // ------------------------------------------------------------------ rating

    /**
     * {@code trRating}: the parts, the re-weighted score and the band.
     *
     * @param checkIns          {@link #checkIns} of the assignment
     * @param checkpointAverage {@link #checkpointAverage} of its checkpoint reviews, or null
     * @return {@code {parts: [{part, value, weight, detail}], score, final, state, band: {tone, label}}};
     *         {@code value} and {@code score} are null when missing
     */
    public static JsonObject rating(JsonObject assignment, JsonObject checkIns, Double checkpointAverage, JsonObject rules)
    {
        JsonObject assess = assignment.getJsonObject("assess");
        JsonObject fin = assignment.getJsonObject("final") == null ? new JsonObject() : assignment.getJsonObject("final");
        JsonObject mentor = fin.getJsonObject("mentor");
        JsonObject manager = fin.getJsonObject("manager");

        int verified = checkIns.getInteger("verified");
        int closed = verified + checkIns.getInteger("needsWork") + checkIns.getInteger("missed");

        JsonArray parts = new JsonArray()
                .add(part("Assessment", assess == null ? null : assess.getDouble("score") / 20, rules.getDouble("trWa", 40d),
                        assess == null ? "not yet" : Num.text(assess.getDouble("score")) + "% (" + assess.getString("result") + ")"))
                .add(part("Mentor review", mentor == null ? null : mentor.getDouble("rating"), rules.getDouble("trWm", 20d),
                        mentor == null ? "not yet" : "by " + mentor.getString("by")))
                .add(part("Manager review", manager == null ? null : manager.getDouble("rating"), rules.getDouble("trWg", 20d),
                        manager == null ? "not yet" : "by " + manager.getString("by")))
                .add(part("Check-ins verified", closed == 0 ? null : 1 + 4.0 * verified / closed, rules.getDouble("trWc", 10d),
                        verified + " of " + closed + " closed"))
                .add(part("Checkpoint reviews", checkpointAverage, rules.getDouble("trWp", 10d), "mentor & manager scores"));

        double s = 0;
        double w = 0;

        for (Object o : parts)
        {
            JsonObject p = (JsonObject) o;

            if (p.getValue("value") != null)
            {
                s += p.getDouble("value") * p.getDouble("weight");
                w += p.getDouble("weight");
            }
        }

        Double score = w == 0 ? null : s / w;

        boolean isFinal = mentor != null && manager != null && assess != null;

        return new JsonObject()
                .put("parts", parts)
                .put("score", score)
                .put("final", isFinal)
                .put("state", score == null ? NOT_RATED : isFinal ? FINAL : PROVISIONAL)
                .put("band", band(score));
    }

    /** {@code trBand}: {@code {tone, label}}; Not rated for null. */
    public static JsonObject band(Double score)
    {
        if (score == null)
        {
            return band("", NOT_RATED);
        }

        if (score >= 4.5)
        {
            return band("ok", "Outstanding");
        }

        if (score >= 3.75)
        {
            return band("ok", "Strong");
        }

        if (score >= 3)
        {
            return band("brand", "Meets");
        }

        if (score >= 2.25)
        {
            return band("accent", "Needs support");
        }

        return band("danger", "Not yet");
    }

    /**
     * {@code trPerson} (score part): a person's overall rating over their plans, weighted by plan length
     * in weeks. Uses the Final ratings, or the Provisional ones while none is Final; plans not rated are
     * left out.
     *
     * @param plans {@code [{weeks, rating}]} with {@code rating} from {@link #rating}
     * @return {@code {score, final, n}}; {@code n} is the number of Final ratings, {@code score} null with
     *         no rated plan. The shape is what {@link PerformanceIndex#growth} reads.
     */
    public static JsonObject person(JsonArray plans)
    {
        List<JsonObject> rated = new ArrayList<>();
        List<JsonObject> fin = new ArrayList<>();

        for (Object o : plans)
        {
            JsonObject x = (JsonObject) o;

            JsonObject r = x.getJsonObject("rating");

            if (r.getValue("score") == null)
            {
                continue;
            }

            rated.add(x);

            if (r.getBoolean("final"))
            {
                fin.add(x);
            }
        }

        List<JsonObject> use = fin.isEmpty() ? rated : fin;

        double w = 0;
        double s = 0;

        for (JsonObject x : use)
        {
            w += x.getDouble("weeks");
        }

        for (JsonObject x : use)
        {
            s += x.getJsonObject("rating").getDouble("score") * x.getDouble("weeks");
        }

        return new JsonObject()
                .put("score", w == 0 ? null : s / w)
                .put("final", !fin.isEmpty())
                .put("n", fin.size());
    }

    // ------------------------------------------------------------------ helpers

    private static String status(JsonArray updates, JsonObject period)
    {
        for (Object o : updates)
        {
            JsonObject u = (JsonObject) o;

            if (period.getString("key").equals(u.getString("period")))
            {
                return u.getString("status");
            }
        }

        return period.getBoolean("current") ? DUE : MISSED;
    }

    private static JsonObject period(String key, String label, LocalDate from, LocalDate to, boolean current)
    {
        return new JsonObject()
                .put("key", key)
                .put("label", label)
                .put("from", from.toString())
                .put("to", to.toString())
                .put("current", current);
    }

    private static JsonObject part(String name, Double value, double weight, String detail)
    {
        return new JsonObject()
                .put("part", name)
                .put("value", value)
                .put("weight", weight)
                .put("detail", detail);
    }

    private static JsonObject band(String tone, String label)
    {
        return new JsonObject().put("tone", tone).put("label", label);
    }
}
