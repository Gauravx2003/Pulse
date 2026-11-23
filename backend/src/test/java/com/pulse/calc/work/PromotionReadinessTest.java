package com.pulse.calc.work;

import com.pulse.calc.core.CultureParameters;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 15.2, spec growth/roadmap: the promotion readiness criteria and the skills gap.
 */
class PromotionReadinessTest
{

    private static final JsonArray GOOD_CULTURE = culture(3.4);

    @Test
    void anOpenEpicCriterionLeavesTheOthersMet()
    {
        JsonArray criteria = PromotionReadiness.criteria(List.of(3.9, 4.1, 4.2), null, 2, GOOD_CULTURE, CultureParameters.VALUES);

        assertEquals("Met", status(criteria, "index"));
        assertEquals("4.10 · 4.20", find(criteria, "index").getString("detail"));
        assertEquals("Open", status(criteria, "epic"));
        assertEquals("Met", status(criteria, "mentoring"));
        assertEquals("Met", status(criteria, "culture"));
    }

    @Test
    void oneQuarterBelowTheBarOrMissingKeepsTheIndexOpen()
    {
        assertEquals("Open", status(PromotionReadiness.criteria(List.of(4.5, 3.9), "PLAT-1", 2, GOOD_CULTURE, CultureParameters.VALUES), "index"));
        assertEquals("Open", status(PromotionReadiness.criteria(Arrays.asList(null, 4.4), "PLAT-1", 2, GOOD_CULTURE, CultureParameters.VALUES), "index"));
        assertEquals("Open", status(PromotionReadiness.criteria(List.of(4.4), "PLAT-1", 2, GOOD_CULTURE, CultureParameters.VALUES), "index"));
    }

    @Test
    void aCultureValueBelow3OrNoMentoringDataIsOpen()
    {
        JsonArray low = culture(3.5).set(7, 2.8);

        JsonArray criteria = PromotionReadiness.criteria(List.of(4.1, 4.2), "PLAT-1", null, low, CultureParameters.VALUES);

        assertEquals("Open", status(criteria, "culture"));
        assertEquals("Lowest: Collaboration 2.8", find(criteria, "culture").getString("detail"));
        assertEquals("Open", status(criteria, "mentoring"));
        assertEquals("Open", status(PromotionReadiness.criteria(List.of(4.1, 4.2), "PLAT-1", 1, GOOD_CULTURE, CultureParameters.VALUES), "mentoring"));
    }

    @Test
    void theGapReadsMinusOneOrReady()
    {
        assertEquals("−1", PromotionReadiness.gap(2, 3));
        assertEquals("−2", PromotionReadiness.gap(2, 4));
        assertEquals("Ready", PromotionReadiness.gap(4, 4));
        assertEquals("Ready", PromotionReadiness.gap(5, 4));
    }

    private static JsonArray culture(double each)
    {
        JsonArray out = new JsonArray();

        CultureParameters.VALUES.forEach(v -> out.add(each));

        return out;
    }

    private static JsonObject find(JsonArray criteria, String key)
    {
        return criteria.stream().map(c -> (JsonObject) c).filter(c -> key.equals(c.getString("key"))).findFirst().orElseThrow();
    }

    private static String status(JsonArray criteria, String key)
    {
        return find(criteria, key).getString("status");
    }
}
