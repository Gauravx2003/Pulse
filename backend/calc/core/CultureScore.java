package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Map;

/**
 * Culture value scores (task 4.4, spec platform/calculation-engine "Formulas as defined in the
 * reference", "Traceable calculation payload" and "No opportunity, no penalty"), ported from the
 * prototype's {@code bandScore}, {@code PRM}, {@code valData}, {@code valueBonus} and the later
 * {@code cult} (app.js lines ~445, ~2196–2251).
 *
 * <p>A culture value's score is
 * {@code min(5, 360° rating × (1 − data share) + data score × data share + extra-mile bonus)}, to 2
 * decimals, where:
 * <ul>
 *   <li>the data score is the weight-averaged 1–5 band score of the value's data parameters
 *       ({@link CultureParameters}); a parameter with no data, or 0 where 0 means no opportunity, is
 *       left out and the other weights re-normalised; with none left, the 360° rating counts 100%;</li>
 *   <li>Moral & Ethics has no data parameters: only the 360° rating (and bonus) counts;</li>
 *   <li>Discipline is the Discipline score (task 4.5), not this formula;</li>
 *   <li>the bonus is 0.05 (rule {@code bonusPerPoint}) per confirmed extra-mile point, split across the
 *       values an item credits, capped at 0.3 (rule {@code bonusCap}) per value per quarter.</li>
 * </ul>
 *
 * <p>Pure functions over plain data. Rules are {@code {dataShare, bonusPerPoint, bonusCap, staleDays,
 * officeDays}} (prototype {@code S.rules}).
 */
public final class CultureScore
{
    /** Rater-group weights shown in the detail view (app.js RATER_W). */
    public static final Map<String, Integer> RATER_WEIGHTS = Map.of("Manager", 40, "Internal team", 25, "Peers", 25,
            "Peers (combined)", 25, "Cross-department", 15, "Self", 20);

    private CultureScore()
    {
    }

    // ------------------------------------------------------------------ parameters

    /**
     * {@code bandScore}: 1–5 from four thresholds. Higher is better: 5 at or above {@code bands[0]},
     * then 4, 3, 2, else 1. Lower is better: 5 at or below {@code bands[0]}, and so on.
     */
    public static int bandScore(double x, JsonArray bands, boolean higherIsBetter)
    {
        for (int i = 0; i < 4; i++)
        {
            double t = bands.getDouble(i);

            if (higherIsBetter ? x >= t : x <= t)
            {
                return 5 - i;
            }
        }

        return 1;
    }

    /**
     * {@code PRM}: one scored parameter for the detail payload.
     *
     * @param def    a definition from {@link CultureParameters}
     * @param input  for a measured parameter: {@code {raw, of?, high?}} ({@code of}: the "of N" count,
     *               {@code high}: high-severity bugs); null means no data
     * @param signal for a {@code signal} parameter: the {@code culture_signals} row
     *               {@code {raw, display, sourceLabel, isSimulated}}; null means no data
     * @param rules  {@code staleDays} and {@code officeDays} fill the {@code how} text
     * @return {@code {key, name, source, how, raw, display, bands, higherIsBetter, weight, unit, score,
     *         notApplicable, sourceLabel, isSimulated}}; {@code score} is null when not applicable
     */
    public static JsonObject parameter(JsonObject def, JsonObject input, JsonObject signal, JsonObject rules)
    {
        boolean isSignal = "signal".equals(def.getString("format"));

        JsonObject from = isSignal ? signal : input;

        Double raw = from == null || from.getValue("raw") == null ? null : from.getDouble("raw");

        boolean zeroNoOpportunity = def.getBoolean("zeroIsNoOpportunity");

        boolean na = raw == null || zeroNoOpportunity && raw == 0;

        String display;

        if (na)
        {
            display = zeroNoOpportunity && raw != null && raw == 0 ? "0 — no opportunity" : "—";
        }
        else if (isSignal)
        {
            display = signal.getString("display");
        }
        else
        {
            display = format(def.getString("format"), raw, input);
        }

        String how = def.getString("how")
                .replace("{staleDays}", Num.text(rules.getDouble("staleDays", 2d)))
                .replace("{officeDays}", Num.text(rules.getDouble("officeDays", 3d)));

        return new JsonObject()
                .put("key", def.getString("key"))
                .put("name", def.getString("name"))
                .put("source", def.getString("source"))
                .put("how", how)
                .put("raw", raw == null ? null : number(raw))
                .put("display", display)
                .put("bands", def.getJsonArray("bands").copy())
                .put("higherIsBetter", def.getBoolean("higherIsBetter"))
                .put("weight", def.getInteger("weight"))
                .put("unit", def.getString("unit"))
                .put("score", na ? null : bandScore(raw, def.getJsonArray("bands"), def.getBoolean("higherIsBetter")))
                .put("notApplicable", na)
                .put("sourceLabel", isSignal && signal != null && signal.getString("sourceLabel") != null
                        ? signal.getString("sourceLabel") : def.getString("source"))
                .put("isSimulated", isSignal && signal != null && signal.getBoolean("isSimulated", true));
    }

    /**
     * {@code valData}: the scored data parameters of one value and their weighted data score.
     *
     * @param inputs  measured raw values by parameter key ({@code {raw, of?, high?}})
     * @param signals {@code culture_signals} rows by parameter key ({@code {raw, display, sourceLabel, isSimulated}})
     * @return {@code {value, parameters, score, weight}}: {@code score} (2 decimals) is null when no
     *         parameter applies; {@code weight} is the total weight of the parameters that apply
     */
    public static JsonObject valData(String value, JsonObject inputs, JsonObject signals, JsonObject rules)
    {
        JsonArray parameters = new JsonArray();

        double weight = 0;

        double weighted = 0;

        for (Object d : CultureParameters.of(value))
        {
            JsonObject def = (JsonObject) d;

            String key = def.getString("key");

            JsonObject p = parameter(def, inputs == null ? null : inputs.getJsonObject(key),
                    signals == null ? null : signals.getJsonObject(key), rules);

            parameters.add(p);

            if (!p.getBoolean("notApplicable"))
            {
                weight += p.getInteger("weight");
                weighted += p.getInteger("score") * p.getInteger("weight");
            }
        }

        return new JsonObject()
                .put("value", value)
                .put("parameters", parameters)
                .put("score", weight == 0 ? null : Num.round(weighted / weight, 2))
                .put("weight", number(weight));
    }

    // ------------------------------------------------------------------ bonus and score

    /**
     * {@code valueBonus}: per value, 0.05 per confirmed point split across the values an item credits,
     * rounded to 2 decimals and capped at 0.3.
     *
     * @param items the person's extra-mile items of the quarter: {@code {state, points, values}}; only
     *              {@code state = "confirmed"} with points count ({@code points} from calc/work)
     * @return 11 bonuses in value order
     */
    public static JsonArray valueBonus(JsonArray items, JsonObject rules)
    {
        double[] b = new double[CultureParameters.VALUES.size()];

        double perPoint = rules.getDouble("bonusPerPoint", 0.05);

        for (Object o : items == null ? new JsonArray() : items)
        {
            JsonObject it = (JsonObject) o;

            double pt = it.getValue("points") == null ? 0 : it.getDouble("points");

            if (!"confirmed".equals(it.getString("state")) || pt == 0)
            {
                continue;
            }

            JsonArray values = it.getJsonArray("values", new JsonArray());

            for (Object v : values)
            {
                int k = CultureParameters.VALUES.indexOf((String) v);

                if (k >= 0)
                {
                    b[k] += pt / values.size() * perPoint;
                }
            }
        }

        JsonArray out = new JsonArray();

        double cap = rules.getDouble("bonusCap", 0.3);

        for (double x : b)
        {
            out.add(number(Math.min(cap, Num.fixed(x, 2))));
        }

        return out;
    }

    /**
     * {@code cult} (the later definition, with data share): the 11 culture value scores.
     *
     * @param ratings    the published 360° rating per value (11, value order)
     * @param dataScores the {@link #valData} score per value (11; null for none and for Moral & Ethics)
     * @param bonus      {@link #valueBonus}
     * @param discipline the Discipline score (task 4.5)
     * @param rules      {@code dataShare} in percent (default 0)
     */
    public static JsonArray cult(JsonArray ratings, JsonArray dataScores, JsonArray bonus, double discipline,
                                 JsonObject rules)
    {
        JsonArray out = new JsonArray();

        for (int i = 0; i < CultureParameters.VALUES.size(); i++)
        {
            out.add(number(score(i, ratings, dataScores, bonus, discipline, rules)));
        }

        return out;
    }

    /**
     * The traceable payload of one value (spec "Traceable calculation payload"): the score, the formula
     * parts, the rater groups, the parameters and the bonus items. Applying
     * {@code min(5, rating360 × (1 − dataShare/100) + dataScore × dataShare/100 + bonus)} to the parts,
     * rounded to 2 decimals, gives {@code score}. For Discipline, {@code parts.discipline} is the score.
     *
     * @param raterGroups per-group 360° ratings for this value: {@code {"Manager": 4.5, "Peers": 4.3}}
     * @param valData     {@link #valData} of this value (ignored for Moral & Ethics and Discipline)
     * @param bonusItems  the confirmed extra-mile items crediting this value: {@code {ref, title, kind, points, values}}
     */
    public static JsonObject detail(String value, double rating, JsonObject raterGroups, JsonObject valData,
                                    double bonus, double discipline, JsonArray bonusItems, JsonObject rules)
    {
        int i = CultureParameters.VALUES.indexOf(value);

        if (i < 0)
        {
            throw new IllegalArgumentException("Unknown culture value: " + value);
        }

        boolean moral = CultureParameters.MORAL_AND_ETHICS.equals(value);

        boolean disc = CultureParameters.DISCIPLINE.equals(value);

        Double dataScore = moral || disc || valData == null || valData.getValue("score") == null
                ? null : valData.getDouble("score");

        double share = rules.getDouble("dataShare", 0d);

        JsonObject parts = disc
                ? new JsonObject().put("discipline", number(Num.fixed(discipline, 2)))
                : new JsonObject()
                        .put("rating360", number(rating))
                        .put("dataScore", dataScore == null ? null : number(dataScore))
                        .put("dataShare", number(dataScore == null ? 0 : share))
                        .put("bonus", number(bonus));

        JsonArray groups = new JsonArray();

        if (raterGroups != null)
        {
            raterGroups.forEach(e -> groups.add(new JsonObject()
                    .put("group", e.getKey())
                    .put("weight", RATER_WEIGHTS.get(e.getKey()))
                    .put("average", e.getValue())));
        }

        JsonArray items = new JsonArray();

        double perPoint = rules.getDouble("bonusPerPoint", 0.05);

        for (Object o : bonusItems == null ? new JsonArray() : bonusItems)
        {
            JsonObject it = (JsonObject) o;

            int n = it.getJsonArray("values", new JsonArray()).size();

            items.add(new JsonObject()
                    .put("ref", it.getString("ref"))
                    .put("title", it.getString("title"))
                    .put("kind", it.getString("kind"))
                    .put("bonus", number(n == 0 ? 0 : it.getDouble("points", 0d) / n * perPoint)));
        }

        JsonArray ratings = new JsonArray();
        JsonArray scores = new JsonArray();
        JsonArray bonuses = new JsonArray();

        for (int k = 0; k < CultureParameters.VALUES.size(); k++)
        {
            ratings.add(k == i ? rating : 0);
            scores.add(k == i ? dataScore : null);
            bonuses.add(k == i ? bonus : 0);
        }

        return new JsonObject()
                .put("value", value)
                .put("score", number(score(i, ratings, scores, bonuses, discipline, rules)))
                .put("parts", parts)
                .put("raterGroups", groups)
                .put("parameters", moral || disc || valData == null ? new JsonArray() : valData.getJsonArray("parameters"))
                .put("bonusItems", items);
    }

    private static double score(int i, JsonArray ratings, JsonArray dataScores, JsonArray bonus, double discipline,
                                JsonObject rules)
    {
        String value = CultureParameters.VALUES.get(i);

        if (CultureParameters.DISCIPLINE.equals(value))
        {
            return Num.fixed(discipline, 2);
        }

        double d = rules.getDouble("dataShare", 0d) / 100;

        Object vd = CultureParameters.MORAL_AND_ETHICS.equals(value) || dataScores == null ? null : dataScores.getValue(i);

        double v = ratings.getDouble(i);

        double base = vd == null ? v : v * (1 - d) + ((Number) vd).doubleValue() * d;

        double b = bonus == null || bonus.getValue(i) == null ? 0 : bonus.getDouble(i);

        return Math.min(5, Num.fixed(base + b, 2));
    }

    // ------------------------------------------------------------------ display

    private static String format(String format, double raw, JsonObject input)
    {
        return switch (format)
        {
            case "pct" -> Num.round(raw) + "%";
            case "of5" -> Num.fixedText(raw, 1) + " / 5";
            case "days" -> Num.text(raw) + " d";
            case "lines" -> Num.round(raw) + " lines";
            case "openBugs" ->
            {
                int high = input.getInteger("high", 0);

                yield Num.text(raw) + (high > 0 ? " (" + high + " high)" : "");
            }
            case "pctOf" -> Num.text(raw) + "% of " + input.getInteger("of", 0);
            case "of" -> Num.text(raw) + " of " + input.getInteger("of", 0);
            case "pctInSprints" ->
            {
                int n = input.getInteger("of", 0);

                yield Num.text(raw) + "% in " + n + " sprint" + (n > 1 ? "s" : "");
            }
            default -> Num.text(raw);
        };
    }

    /** Whole numbers as integers, so payloads read "3", not "3.0". */
    private static Number number(double x)
    {
        return x == Math.rint(x) && Math.abs(x) < 1e15 ? (Number) (long) x : (Number) x;
    }
}
