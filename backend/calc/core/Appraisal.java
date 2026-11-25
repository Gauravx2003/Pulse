package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * The appraisal score and increment recommendation (task 4.8, spec appraisal/appraisal "Appraisal
 * score", "Bands and recommendation" and "Cap and budget"), ported from the prototype's
 * {@code aprRules}, {@code avgScores}, {@code latestCycleWith}, {@code aprParts}, {@code aprBand} and
 * {@code aprRecommend} (app.js ~983–1000).
 *
 * <p>Score: the weighted average of the parts that exist, re-weighted over those parts:
 * <ul>
 *   <li>perf (50): the performance index over the last 4 quarters, the 3 closed quarters and the live one;</li>
 *   <li>r360 (20): average of the peer and cross-department scores of the latest cycle with at least 2
 *       such responses;</li>
 *   <li>mgr (20): average of the manager scores of the latest cycle with a manager response;</li>
 *   <li>disc (10): the Discipline score (task 4.5).</li>
 * </ul>
 * Recommendation: band low + (high − low) × position of the score in the band (the top band runs to
 * 5), rounded to 0.5% and capped at rule {@code max} (20%). People with under {@code minMonths} (6)
 * months of service at the cycle date get 0; 6–11 months are pro-rated by months ÷ 12, rounded to 0.5%.
 *
 * <p>Rules ({@link #defaultRules}): {@code {w: {perf, r360, mgr, disc}, max, budget, minMonths,
 * bands: [{min, lo, hi, name, tone}, ...]}}, bands from the highest down.
 */
public final class Appraisal
{
    public static final String BELOW_EXPECTATIONS = "Below expectations";

    private static final JsonObject DEFAULTS = new JsonObject()
            .put("w", new JsonObject().put("perf", 50).put("r360", 20).put("mgr", 20).put("disc", 10))
            .put("max", 20)
            .put("budget", 12)
            .put("minMonths", 6)
            .put("bands", new JsonArray()
                    .add(band(4.5, 15, 20, "Outstanding", "ok"))
                    .add(band(4.0, 10, 15, "Exceeds", "ok"))
                    .add(band(3.5, 7, 10, "Strong", "brand"))
                    .add(band(3.0, 4, 7, "Meets", "brand"))
                    .add(band(2.5, 0, 3, "Developing", "accent"))
                    .add(band(0, 0, 0, BELOW_EXPECTATIONS, "danger")));

    private static final String[][] PARTS = {
            {"perf", "Performance index (4-quarter avg)"},
            {"r360", "360° feedback (peers & cross-dept)"},
            {"mgr", "Manager review"},
            {"disc", "Discipline (Jira)"}};

    private Appraisal()
    {
    }

    /** {@code APR_DEFAULT}: the default Appraisal Standards (a copy). */
    public static JsonObject defaultRules()
    {
        return DEFAULTS.copy();
    }

    // ------------------------------------------------------------------ inputs

    /**
     * The performance part: the average of the 3 closed quarters' index and the live index.
     *
     * @param closed the index of the 3 quarters before the live one, oldest first
     */
    public static double performance(JsonArray closed, double index)
    {
        double s = 0;

        for (int i = 0; i < 3; i++)
        {
            s += closed.getDouble(i);
        }

        return (s + index) / 4;
    }

    /** {@code avgScores}: the average of every score in the responses; null when there is none. */
    public static Double averageScores(JsonArray responses)
    {
        double sum = 0;

        int n = 0;

        for (Object o : responses)
        {
            for (Object x : ((JsonObject) o).getJsonArray("scores", new JsonArray()))
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

    /**
     * The 360° part: the latest cycle with at least 2 submitted peer or cross-department responses.
     *
     * @param cycles {@code [{name, responses: [{group, status, scores}]}]}, oldest first, the person's
     *               responses as subject only
     * @return {@code {score, cycle}}; score null and cycle "—" when no cycle qualifies
     */
    public static JsonObject feedback360(JsonArray cycles)
    {
        for (int k = cycles.size() - 1; k >= 0; k--)
        {
            JsonObject c = cycles.getJsonObject(k);

            JsonArray rs = submitted(c, false);

            if (rs.size() >= 2)
            {
                return new JsonObject().put("score", averageScores(rs)).put("cycle", c.getString("name"));
            }
        }

        return new JsonObject().put("score", null).put("cycle", "—");
    }

    /** The manager part ({@code latestCycleWith(pid, 'manager')}): null with no manager response. */
    public static Double managerReview(JsonArray cycles)
    {
        for (int k = cycles.size() - 1; k >= 0; k--)
        {
            JsonArray rs = submitted(cycles.getJsonObject(k), true);

            if (!rs.isEmpty())
            {
                return averageScores(rs);
            }
        }

        return null;
    }

    /** Whole calendar months from {@code from} to {@code to}, ignoring the day ({@code monthsBetween}). */
    public static int monthsBetween(LocalDate from, LocalDate to)
    {
        return (to.getYear() - from.getYear()) * 12 + (to.getMonthValue() - from.getMonthValue());
    }

    // ------------------------------------------------------------------ score and recommendation

    /**
     * {@code aprParts}: the four parts and the re-weighted score.
     *
     * @param in {@code {perf, r360, mgr, disc, cycle}}; any part may be null
     * @return {@code {parts: [{key, label, value, weight}], score, cycle, missing: [label]}}
     */
    public static JsonObject parts(JsonObject in, JsonObject rules)
    {
        JsonObject w = rules.getJsonObject("w");

        JsonArray parts = new JsonArray();
        JsonArray missing = new JsonArray();

        double s = 0;
        double sw = 0;

        for (String[] p : PARTS)
        {
            Double value = in.getValue(p[0]) == null ? null : in.getDouble(p[0]);

            double weight = w.getDouble(p[0]);

            parts.add(new JsonObject().put("key", p[0]).put("label", p[1]).put("value", value).put("weight", weight));

            if (value == null)
            {
                missing.add(p[1]);
            }
            else
            {
                s += value * weight;
                sw += weight;
            }
        }

        return new JsonObject()
                .put("parts", parts)
                .put("score", sw == 0 ? null : s / sw)
                .put("cycle", in.getString("cycle", "—"))
                .put("missing", missing);
    }

    /** {@code aprBand}: the first band whose minimum the score reaches. */
    public static JsonObject band(double score, JsonObject rules)
    {
        JsonArray bands = rules.getJsonArray("bands");

        for (int i = 0; i < bands.size(); i++)
        {
            if (score >= bands.getJsonObject(i).getDouble("min"))
            {
                return bands.getJsonObject(i).copy();
            }
        }

        return bands.getJsonObject(bands.size() - 1).copy();
    }

    /**
     * {@code aprRecommend}: the recommended increment.
     *
     * @param parts    {@link #parts}
     * @param joinedOn the person's joining date
     * @param asOf     the date service is counted to (the end of the appraisal year, 30 Sep)
     * @return {@code {pct, band, eligible, months, prorated, why, improvementPlan}}; with no score:
     *         {@code {pct: 0, band, eligible: false, why: "Not enough review data", improvementPlan: false}}
     */
    public static JsonObject recommend(JsonObject parts, LocalDate joinedOn, LocalDate asOf, JsonObject rules)
    {
        Double score = parts.getValue("score") == null ? null : parts.getDouble("score");

        if (score == null)
        {
            return new JsonObject()
                    .put("pct", 0)
                    .put("band", band(0, rules))
                    .put("eligible", false)
                    .put("why", "Not enough review data")
                    .put("improvementPlan", false);
        }

        JsonArray bands = rules.getJsonArray("bands");

        int i = index(bands, score);

        JsonObject b = bands.getJsonObject(i);

        double min = b.getDouble("min");
        double lo = b.getDouble("lo");
        double hi = b.getDouble("hi");
        double top = i == 0 ? 5 : bands.getJsonObject(i - 1).getDouble("min");
        double frac = Math.max(0, Math.min(1, (score - min) / (top - min)));

        double pct = Math.min(rules.getDouble("max"), halfPercent(lo + (hi - lo) * frac));

        int months = monthsBetween(joinedOn, asOf);

        boolean eligible = months >= rules.getInteger("minMonths");

        boolean prorated = eligible && months < 12;

        if (prorated)
        {
            pct = halfPercent(pct * months / 12);
        }

        return new JsonObject()
                .put("pct", eligible ? pct : 0)
                .put("band", b.copy())
                .put("eligible", eligible)
                .put("months", months)
                .put("prorated", prorated)
                .put("why", eligible ? "" : "Joined " + months + " months ago (needs " + rules.getInteger("minMonths") + ")")
                .put("improvementPlan", BELOW_EXPECTATIONS.equals(b.getString("name")));
    }

    /**
     * Checks a proposed increment against the hard cap (rule {@code max}, 20%): rejects anything
     * outside 0 to the cap. Throws {@link IllegalArgumentException} naming the field.
     */
    public static double checkCap(Double pct, JsonObject rules)
    {
        double max = rules.getDouble("max");

        if (pct == null || pct.isNaN() || pct < 0 || pct > max)
        {
            throw new IllegalArgumentException("pct: The increment must be between 0% and " + Num.text(max) + "%.");
        }

        return pct;
    }

    // ------------------------------------------------------------------ increments and budget

    /**
     * The new CTC after an increment of {@code pct}%, in lakh to 2 decimals; null without a CTC.
     */
    public static Double newCtc(Number ctc, double pct)
    {
        return ctc == null ? null : money(ctc.doubleValue() * (1 + pct / 100));
    }

    /**
     * True when an eligible person's increment is outside the range of their band (spec "Bands and
     * recommendation": an exception needs a reason).
     *
     * @param recommendation {@link #recommend}'s result ({@code eligible}, {@code band: {lo, hi}})
     */
    public static boolean exception(JsonObject recommendation, double pct)
    {
        JsonObject band = recommendation.getJsonObject("band", new JsonObject());

        return recommendation.getBoolean("eligible", false) && band.getNumber("lo") != null
                && (pct < band.getNumber("lo").doubleValue() || pct > band.getNumber("hi").doubleValue());
    }

    /**
     * The cycle's money (spec "Cap and budget"): payroll is the sum of the CTCs given, spend the sum of
     * CTC × increment, budget rule {@code budget}% of payroll; rows without a CTC are left out.
     *
     * @param rows  {@code [{ctc, pct}]}, amounts in lakh
     * @param rules the appraisal rules ({@code budget})
     * @return {@code {payroll, budget, spend, overBudget, averagePct}}, amounts to 2 decimals and the
     *         average increment to 1
     */
    public static JsonObject budget(JsonArray rows, JsonObject rules)
    {
        double payroll = 0;

        double spend = 0;

        for (Object o : rows)
        {
            JsonObject r = (JsonObject) o;

            if (r.getNumber("ctc") != null)
            {
                double ctc = r.getNumber("ctc").doubleValue();

                payroll += ctc;

                spend += ctc * r.getNumber("pct", 0).doubleValue() / 100;
            }
        }

        double budget = payroll * rules.getNumber("budget", 12).doubleValue() / 100;

        return new JsonObject()
                .put("payroll", money(payroll))
                .put("budget", money(budget))
                .put("spend", money(spend))
                .put("overBudget", spend > budget + 1e-9)
                .put("averagePct", payroll == 0 ? 0 : Math.round(spend / payroll * 1000) / 10.0);
    }

    /** An amount in lakh to 2 decimals, halves up. */
    public static double money(double lakh)
    {
        return BigDecimal.valueOf(lakh).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------------ helpers

    /** Rounds to the nearest 0.5 as JavaScript's {@code Math.round(x * 2) / 2} does (halves up). */
    static double halfPercent(double x)
    {
        return Math.floor(x * 2 + 0.5) / 2;
    }

    private static int index(JsonArray bands, double score)
    {
        for (int i = 0; i < bands.size(); i++)
        {
            if (score >= bands.getJsonObject(i).getDouble("min"))
            {
                return i;
            }
        }

        return bands.size() - 1;
    }

    private static JsonArray submitted(JsonObject cycle, boolean manager)
    {
        JsonArray out = new JsonArray();

        for (Object o : cycle.getJsonArray("responses", new JsonArray()))
        {
            JsonObject r = (JsonObject) o;

            String group = r.getString("group");

            if (!"Submitted".equals(r.getString("status")))
            {
                continue;
            }

            if (manager ? "manager".equals(group) : !"self".equals(group) && !"manager".equals(group))
            {
                out.add(r);
            }
        }

        return out;
    }

    private static JsonObject band(double min, int lo, int hi, String name, String tone)
    {
        return new JsonObject().put("min", min).put("lo", lo).put("hi", hi).put("name", name).put("tone", tone);
    }
}
