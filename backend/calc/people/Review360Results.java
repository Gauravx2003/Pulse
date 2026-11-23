package com.pulse.calc.people;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 360° results aggregation (spec culture/review-360 "Anonymity of results", "Differencing protection"
 * and "Results"; task 20.3). Pure: plain data in, plain data out. Verified against the prototype's
 * {@code results} and {@code weighted360} (app.js ~891).
 *
 * <ul>
 *   <li>Only submitted responses count. Self and manager are always shown.</li>
 *   <li>Internal and cross-department groups under the anonymity threshold are merged into
 *       "others" (Peers combined); when the merged responses are still under the threshold they are
 *       hidden altogether. Nothing about a merged or hidden group appears except its counts.</li>
 *   <li>Averages per shown group per value ("Not observed" left out); weighted score per value with
 *       the cycle weights (Peers combined weighs as internal).</li>
 *   <li>Hidden strengths: others ≥ self + 0.75; blind spots: self ≥ others + 0.75. Unlike the
 *       prototype, "others" averages only the shown non-self groups, so a hidden group can never be
 *       recovered by comparing it with the manager's ratings.</li>
 *   <li>Comments grouped by shown group, never attributed, in a stable order unrelated to who wrote
 *       them.</li>
 * </ul>
 *
 * <p>A response is {@code {group: self|manager|internal|cross, status, scores: {value: 1..5|null},
 * keepDoing, startDoing}}; {@code values} are the rated values in order.
 */
public final class Review360Results
{
    static final double GAP = 0.75;

    static final List<String> GROUPS = List.of("self", "manager", "internal", "cross");

    static final List<String> ANONYMOUS = List.of("internal", "cross");

    private Review360Results()
    {
    }

    /**
     * @param responses every non-declined request of the subject in the cycle
     * @param threshold the cycle's anonymity threshold (default 3)
     * @param weights   {@code {manager, internal, cross, self}}
     */
    public static JsonObject of(JsonArray responses, List<String> values, int threshold, JsonObject weights)
    {
        Map<String, List<JsonObject>> submitted = new LinkedHashMap<>();

        JsonObject counts = new JsonObject();

        for (String group : GROUPS)
        {
            submitted.put(group, new ArrayList<>());

            counts.put(group, new JsonObject().put("assigned", 0).put("submitted", 0));
        }

        int responded = 0;

        for (Object item : responses)
        {
            JsonObject r = (JsonObject) item;

            String group = r.getString("group");

            if (!submitted.containsKey(group))
            {
                continue;
            }

            JsonObject count = counts.getJsonObject(group);

            count.put("assigned", count.getInteger("assigned") + 1);

            if ("submitted".equals(r.getString("status")))
            {
                submitted.get(group).add(r);

                count.put("submitted", count.getInteger("submitted") + 1);

                responded++;
            }
        }

        // Anonymity: anonymous groups under the threshold merge into "others", or are hidden.
        Map<String, List<JsonObject>> shown = new LinkedHashMap<>();

        List<JsonObject> merged = new ArrayList<>();

        JsonArray mergedGroups = new JsonArray();

        for (String group : GROUPS)
        {
            List<JsonObject> rs = submitted.get(group);

            if (rs.isEmpty())
            {
                continue;
            }

            if (ANONYMOUS.contains(group) && rs.size() < threshold)
            {
                merged.addAll(rs);

                mergedGroups.add(group);
            }
            else
            {
                shown.put(group, rs);
            }
        }

        boolean othersShown = !merged.isEmpty() && merged.size() >= threshold;

        if (othersShown)
        {
            shown.put("others", merged);
        }

        for (String group : GROUPS)
        {
            JsonObject count = counts.getJsonObject(group);

            count.put("state", mergedGroups.contains(group) ? (othersShown ? "merged" : "hidden")
                    : count.getInteger("submitted") > 0 ? "shown" : "none");
        }

        JsonObject averages = new JsonObject();

        for (Map.Entry<String, List<JsonObject>> group : shown.entrySet())
        {
            averages.put(group.getKey(), averages(group.getValue(), values));
        }

        List<JsonObject> nonSelf = new ArrayList<>();

        shown.forEach((group, rs) ->
        {
            if (!"self".equals(group))
            {
                nonSelf.addAll(rs);
            }
        });

        JsonObject others = averages(nonSelf, values);

        JsonObject self = averages.getJsonObject("self");

        JsonArray hiddenStrengths = new JsonArray();

        JsonArray blindSpots = new JsonArray();

        if (self != null)
        {
            for (String value : values)
            {
                Double s = self.getDouble(value);

                Double o = others.getDouble(value);

                if (s == null || o == null)
                {
                    continue;
                }

                if (o - s >= GAP)
                {
                    hiddenStrengths.add(new JsonObject().put("value", value).put("self", s).put("others", o));
                }

                if (s - o >= GAP)
                {
                    blindSpots.add(new JsonObject().put("value", value).put("self", s).put("others", o));
                }
            }
        }

        return new JsonObject()
                .put("responded", responded)
                .put("threshold", threshold)
                .put("groups", counts)
                .put("averages", averages)
                .put("weighted", weighted(averages, values, weights))
                .put("hiddenStrengths", hiddenStrengths)
                .put("blindSpots", blindSpots)
                .put("comments", comments(shown));
    }

    /** Average per value over responses with a rating (null when nobody rated it). */
    static JsonObject averages(List<JsonObject> responses, List<String> values)
    {
        JsonObject out = new JsonObject();

        for (String value : values)
        {
            double sum = 0;

            int n = 0;

            for (JsonObject r : responses)
            {
                Number score = r.getJsonObject("scores", new JsonObject()).getNumber(value);

                if (score != null)
                {
                    sum += score.doubleValue();

                    n++;
                }
            }

            out.put(value, n == 0 ? null : sum / n);
        }

        return out;
    }

    /** Weighted score per value over the shown groups; Peers combined ("others") weighs as internal. */
    static JsonObject weighted(JsonObject averages, List<String> values, JsonObject weights)
    {
        JsonObject out = new JsonObject();

        for (String value : values)
        {
            double sum = 0;

            double totalWeight = 0;

            for (String group : averages.fieldNames())
            {
                Double avg = averages.getJsonObject(group).getDouble(value);

                Number w = weights.getNumber("others".equals(group) ? "internal" : group);

                if (avg != null && w != null)
                {
                    sum += avg * w.doubleValue();

                    totalWeight += w.doubleValue();
                }
            }

            out.put(value, totalWeight == 0 ? null : sum / totalWeight);
        }

        return out;
    }

    /** Keep / start comments per shown group, sorted so their order says nothing about the writer. */
    static JsonObject comments(Map<String, List<JsonObject>> shown)
    {
        JsonObject out = new JsonObject();

        shown.forEach((group, rs) ->
        {
            List<JsonObject> list = new ArrayList<>();

            for (JsonObject r : rs)
            {
                list.add(new JsonObject().put("keep", r.getString("keepDoing", "")).put("start", r.getString("startDoing", "")));
            }

            list.sort(Comparator.comparing((JsonObject c) -> c.getString("keep")).thenComparing(c -> c.getString("start")));

            out.put(group, new JsonArray(list));
        });

        return out;
    }
}
