package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * The Discipline score (task 4.5, spec culture/culture-values "Discipline rules" and "Discipline
 * override"), ported from the prototype's {@code discMetrics} and {@code discScore} (app.js ~703–720).
 *
 * <p>Discipline is data only:
 * <ul>
 *   <li>a. base band from daily-update compliance: ≥ d5 (95) → 5, ≥ d4 (85) → 4, ≥ d3 (70) → 3,
 *       ≥ d2 (50) → 2, else 1;</li>
 *   <li>b. stale tickets: 3 or more −0.5; any while the base is 5 −0.5;</li>
 *   <li>c. re-planning before the due date: under 50% −0.5, under 80% −0.25;</li>
 *   <li>d. status accuracy under 90% −0.25;</li>
 *   <li>e. time logging: under 70% same-day or 3+ bulk entries −0.25;</li>
 *   <li>f. blockers raised within a day under 70% −0.25;</li>
 *   <li>g. process adherence (DoD, PR review, stand-up average) under 85% −0.25;</li>
 *   <li>h. attendance score (task 4.6, when rule {@code att.inCulture} is on): under 70% −0.5, under 85%
 *       −0.25, and −0.25 for any day absent without leave.</li>
 * </ul>
 * The score is never more than 1 below the base and never below 1, and stays 3.0 until 4 weeks of
 * data exist. A holder of reviews.calibrate may override it with a score 1–5 and a written reason
 * ({@link #validateOverride}); the endpoint refuses the caller's own score and audits every change.
 *
 * <p>Pure functions over plain data. Inputs ({@link #metrics}):
 * <pre>{@code
 * {"days": [["2026-08-31", true], ...],   // working days of the window and whether the person updated
 *  "stale": 1,                            // open issues stale past rule staleDays (calc/work)
 *  "replan": [proactive, late], "status": [wrong, checked], "block": [early, raised],
 *  "logSame": 85, "bulk": 1,              // % worklogs same day, bulk entries
 *  "dod": 95, "review": 100, "standup": 90,  // process adherence %; review from the PR data (calc/work)
 *  "attendance": {"score": 92, "absent": 0}}  // attStats (task 4.6), or null
 * }</pre>
 * Rules: {@code {d5, d4, d3, d2, attInCulture}}.
 */
public final class Discipline
{
    /** Weeks of data needed before the score is more than the 3.0 default. */
    public static final int MIN_WEEKS = 4;

    /** The score with no data, or with under {@link #MIN_WEEKS} weeks of it. */
    public static final double DEFAULT_SCORE = 3.0;

    /** Shortest override reason accepted. */
    public static final int MIN_REASON = 10;

    private Discipline()
    {
    }

    /**
     * {@code discMetrics}: the rule inputs, base band, adjustments and automatic score; null when the
     * person has no Discipline data.
     *
     * @return {@code {days, updated, compliance, weeks, enoughData, stale, replan, replanN, status, statusN,
     *         logSame, bulk, block, blockN, process, attendance, base, adjustments, auto}}; each adjustment
     *         is {@code {rule, label, detail, points}}
     */
    public static JsonObject metrics(JsonObject in, JsonObject rules)
    {
        if (in == null)
        {
            return null;
        }

        JsonArray days = in.getJsonArray("days", new JsonArray());

        int updated = 0;

        Set<LocalDate> weeks = new HashSet<>();

        for (Object o : days)
        {
            JsonArray d = (JsonArray) o;

            if (Boolean.TRUE.equals(d.getBoolean(1)))
            {
                updated++;
            }

            weeks.add(LocalDate.parse(d.getString(0)).with(DayOfWeek.MONDAY));
        }

        long compliance = days.isEmpty() ? 0 : Num.round((double) updated / days.size() * 100);

        int stale = in.getInteger("stale", 0);

        JsonArray replanN = in.getJsonArray("replan");
        JsonArray statusN = in.getJsonArray("status");
        JsonArray blockN = in.getJsonArray("block");

        long replan = pct(replanN.getInteger(0), replanN.getInteger(0) + replanN.getInteger(1));
        long status = statusN.getInteger(1) == 0 ? 100 : Num.round((1 - (double) statusN.getInteger(0) / statusN.getInteger(1)) * 100);
        long block = pct(blockN.getInteger(0), blockN.getInteger(1));
        long process = Num.round((in.getDouble("dod") + in.getDouble("review") + in.getDouble("standup")) / 3);

        int logSame = in.getInteger("logSame");
        int bulk = in.getInteger("bulk");

        int base = compliance >= rules.getDouble("d5", 95d) ? 5
                : compliance >= rules.getDouble("d4", 85d) ? 4
                : compliance >= rules.getDouble("d3", 70d) ? 3
                : compliance >= rules.getDouble("d2", 50d) ? 2 : 1;

        JsonArray adj = new JsonArray();

        if (stale >= 3)
        {
            adj.add(adjustment("b", "Stale tickets", stale + " stale", -0.5));
        }
        else if (stale > 0 && base == 5)
        {
            adj.add(adjustment("b", "Stale tickets", stale + " stale (5 needs none)", -0.5));
        }

        if (replan < 50)
        {
            adj.add(adjustment("c", "Re-planning", replan + "% proactive", -0.5));
        }
        else if (replan < 80)
        {
            adj.add(adjustment("c", "Re-planning", replan + "% proactive", -0.25));
        }

        if (status < 90)
        {
            adj.add(adjustment("d", "Status accuracy", status + "%", -0.25));
        }

        if (logSame < 70 || bulk >= 3)
        {
            adj.add(adjustment("e", "Time logging", logSame + "% same-day, " + bulk + " bulk entries", -0.25));
        }

        if (block < 70)
        {
            adj.add(adjustment("f", "Early blockers", block + "% within 1 day", -0.25));
        }

        if (process < 85)
        {
            adj.add(adjustment("g", "Process adherence", process + "%", -0.25));
        }

        JsonObject att = in.getJsonObject("attendance");

        if (rules.getBoolean("attInCulture", true) && att != null && att.getValue("score") != null)
        {
            double score = att.getDouble("score");

            String shown = Num.text(score) + "%";

            if (score < 70)
            {
                adj.add(adjustment("h", "Attendance & leave", shown, -0.5));
            }
            else if (score < 85)
            {
                adj.add(adjustment("h", "Attendance & leave", shown, -0.25));
            }

            int absent = att.getInteger("absent", 0);

            if (absent > 0)
            {
                adj.add(adjustment("h", "Absent without leave", absent + " day" + (absent > 1 ? "s" : ""), -0.25));
            }
        }

        double total = 0;

        for (Object o : adj)
        {
            total += ((JsonObject) o).getDouble("points");
        }

        boolean enoughData = weeks.size() >= MIN_WEEKS;

        double auto = enoughData ? Num.fixed(Math.max(1, Math.max(base - 1, base + total)), 2) : DEFAULT_SCORE;

        return new JsonObject()
                .put("days", days.copy())
                .put("updated", updated)
                .put("compliance", compliance)
                .put("weeks", weeks.size())
                .put("enoughData", enoughData)
                .put("stale", stale)
                .put("replan", replan)
                .put("replanN", replanN.copy())
                .put("status", status)
                .put("statusN", statusN.copy())
                .put("logSame", logSame)
                .put("bulk", bulk)
                .put("block", block)
                .put("blockN", blockN.copy())
                .put("process", process)
                .put("attendance", att == null ? null : att.copy())
                .put("base", base)
                .put("adjustments", adj)
                .put("auto", auto);
    }

    /**
     * {@code discScore}: the override when there is one, else the automatic score, else 3.0.
     *
     * @param metrics  {@link #metrics}, or null with no data
     * @param override {@code {score, reason}}, or null
     * @return {@code {score, source: "override" | "auto" | "none", reason?}}
     */
    public static JsonObject score(JsonObject metrics, JsonObject override)
    {
        if (override != null)
        {
            return new JsonObject()
                    .put("score", override.getValue("score"))
                    .put("source", "override")
                    .put("reason", override.getString("reason"));
        }

        if (metrics == null)
        {
            return new JsonObject().put("score", DEFAULT_SCORE).put("source", "none");
        }

        return new JsonObject().put("score", metrics.getValue("auto")).put("source", "auto");
    }

    /**
     * Checks a manager override: a score from 1 to 5 and a reason of at least {@link #MIN_REASON}
     * characters. Returns the reason trimmed; throws {@link IllegalArgumentException} naming the field.
     */
    public static String validateOverride(Double score, String reason)
    {
        if (score == null || score.isNaN() || score < 1 || score > 5)
        {
            throw new IllegalArgumentException("score: Score must be between 1 and 5.");
        }

        String trimmed = reason == null ? "" : reason.strip();

        if (trimmed.length() < MIN_REASON)
        {
            throw new IllegalArgumentException("reason: Give a reason of at least " + MIN_REASON + " characters.");
        }

        return trimmed;
    }

    private static long pct(int part, int whole)
    {
        return whole == 0 ? 100 : Num.round((double) part / whole * 100);
    }

    private static JsonObject adjustment(String rule, String label, String detail, double points)
    {
        return new JsonObject().put("rule", rule).put("label", label).put("detail", detail).put("points", points);
    }
}
