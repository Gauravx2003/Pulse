package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The performance index and its pillars (task 4.3, spec platform/calculation-engine "Formulas as
 * defined in the reference" and "Pillar inputs in v1"), ported from the prototype's {@code index},
 * {@code cultureAvg}, {@code weightedCulture}, {@code okrP} and {@code growthP} (app.js lines 188–191,
 * 722 and 1833).
 *
 * <p>In v1 the pillars are (a recorded deviation from spec section 12):
 * <ul>
 *   <li>Delivery and Quality: the seeded values ({@code pillars} table).</li>
 *   <li>Culture: the weighted average of the 11 culture value scores ({@link CultureScore#cult}).</li>
 *   <li>OKR: the OKR pillar of the quarter (calc/work, Track 2), or the seeded value when the person has
 *       no OKRs in the quarter.</li>
 *   <li>Growth: the seeded value, averaged with the final training rating once one exists.</li>
 * </ul>
 *
 * <p>Pure functions over plain data. Pillars are {@code {delivery, quality, culture, okr, growth}};
 * weights are {@code {delivery, quality, culture, okr, growth}} from rule {@code weights}.
 */
public final class PerformanceIndex
{
    private static final String[] PILLARS = {"delivery", "quality", "culture", "okr", "growth"};

    private PerformanceIndex()
    {
    }

    /**
     * {@code index}: the weighted average of the five pillars, divided by the total weight so it stays
     * on the 1–5 scale when the weights do not total 100 (a total of 0 counts as 1).
     */
    public static double index(JsonObject pillars, JsonObject weights)
    {
        double sum = 0;

        double total = 0;

        for (String k : PILLARS)
        {
            double w = weights.getDouble(k, 0d);

            sum += pillars.getDouble(k) * w;
            total += w;
        }

        return sum / (total == 0 ? 1 : total);
    }

    /**
     * The five pillars of a person.
     *
     * @param seeded    the seeded {@code {delivery, quality, okr, growth}}
     * @param culture   the 11 culture value scores, in value order ({@link CultureScore#cult})
     * @param valueWeights rule {@code vw}: the 11 value weights
     * @param okrScore  the quarter's OKR pillar, or null when the person has no OKRs in the quarter
     * @param training  {@code {final, score}} of the person's training rating, or null with no plans
     */
    public static JsonObject pillars(JsonObject seeded, JsonArray culture, JsonArray valueWeights, Double okrScore,
                                     JsonObject training)
    {
        return new JsonObject()
                .put("delivery", seeded.getDouble("delivery"))
                .put("quality", seeded.getDouble("quality"))
                .put("culture", cultureAvg(culture, valueWeights))
                .put("okr", okr(okrScore, seeded.getDouble("okr")))
                .put("growth", growth(seeded.getDouble("growth"), training));
    }

    /** {@code cultureAvg} / {@code weightedCulture}: culture value scores averaged by value weight. */
    public static double cultureAvg(JsonArray culture, JsonArray valueWeights)
    {
        double sum = 0;

        double total = 0;

        for (int i = 0; i < culture.size(); i++)
        {
            double w = i < valueWeights.size() && valueWeights.getValue(i) != null ? valueWeights.getDouble(i) : 0;

            sum += culture.getDouble(i) * w;
            total += w;
        }

        return total == 0 ? 0 : sum / total;
    }

    /** {@code okrP}: the OKR pillar, or the seeded value when there are no OKRs in the quarter. */
    public static double okr(Double okrScore, double seeded)
    {
        return okrScore == null ? seeded : okrScore;
    }

    /**
     * {@code growthP}: the seeded Growth value, averaged with the training rating (to 2 decimals) once
     * that rating is final. A provisional rating, or none, leaves the seeded value.
     */
    public static double growth(double seeded, JsonObject training)
    {
        if (training == null || !training.getBoolean("final", false) || training.getValue("score") == null)
        {
            return seeded;
        }

        return Num.round((seeded + training.getDouble("score")) / 2, 2);
    }
}
