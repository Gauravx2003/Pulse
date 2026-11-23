package com.pulse.calc.work;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Locale;

/**
 * Promotion readiness (task 15.2, spec growth/roadmap "Promotion readiness"): the four criteria of the
 * level guide, each Met or Open with what it is based on. A conversation aid for the road map, never a
 * score.
 */
public final class PromotionReadiness
{

    public static final double INDEX_BAR = 4.0;

    public static final int INDEX_QUARTERS = 2;

    public static final int MENTEES = 2;

    public static final double CULTURE_FLOOR = 3.0;

    private PromotionReadiness()
    {
    }

    /**
     * {@code [{key, label, met, detail}]} for: the performance index at least {@value #INDEX_BAR} in each
     * of the last {@value #INDEX_QUARTERS} quarters ({@code indexes}, oldest first, null where a quarter
     * has none), a cross-team epic led ({@code epicKey}), {@value #MENTEES} engineers mentored
     * ({@code mentees}, null when unknown) and no culture value below {@value #CULTURE_FLOOR}
     * ({@code culture}: the 11 scores in value order, null without scores).
     */
    public static JsonArray criteria(List<Double> indexes, String epicKey, Integer mentees, JsonArray culture, List<String> values)
    {
        List<Double> last = indexes.subList(Math.max(0, indexes.size() - INDEX_QUARTERS), indexes.size());

        boolean indexMet = last.size() == INDEX_QUARTERS && last.stream().allMatch(v -> v != null && v >= INDEX_BAR);

        String indexDetail = last.isEmpty() ? "No index yet"
                : String.join(" · ", last.stream().map(v -> v == null ? "—" : String.format(Locale.ROOT, "%.2f", v)).toList());

        JsonArray out = new JsonArray()
                .add(criterion("index", "Performance index ≥ 4.0 for 2 quarters", indexMet, indexDetail))
                .add(criterion("epic", "Lead one cross-team epic", epicKey != null && !epicKey.isBlank(),
                        epicKey == null || epicKey.isBlank() ? "Not recorded yet" : epicKey))
                .add(criterion("mentoring", "Mentor 2 engineers", mentees != null && mentees >= MENTEES,
                        mentees == null ? "No mentoring data yet" : mentees + " of " + MENTEES));

        if (culture == null || culture.isEmpty())
        {
            return out.add(criterion("culture", "Culture values: no value below 3.0", false, "No culture scores yet"));
        }

        int lowest = 0;

        for (int i = 1; i < culture.size(); i++)
        {
            if (number(culture.getValue(i)) < number(culture.getValue(lowest)))
            {
                lowest = i;
            }
        }

        double low = number(culture.getValue(lowest));

        String name = lowest < values.size() ? values.get(lowest) : "Value " + (lowest + 1);

        return out.add(criterion("culture", "Culture values: no value below 3.0", low >= CULTURE_FLOOR,
                "Lowest: " + name + " " + String.format(Locale.ROOT, "%.1f", low)));
    }

    /**
     * {@code "−1"} for a skill one level short of what the next level needs, {@code "Ready"} at or above it.
     */
    public static String gap(int current, int needed)
    {
        return needed > current ? "−" + (needed - current) : "Ready";
    }

    private static JsonObject criterion(String key, String label, boolean met, String detail)
    {
        return new JsonObject().put("key", key).put("label", label).put("met", met).put("status", met ? "Met" : "Open")
                .put("detail", detail);
    }

    private static double number(Object value)
    {
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
