package com.pulse.calc.people;

import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 20.3: 360° results against spec culture/review-360 ("Anonymity of results", "Results") and
 * against the oracle {@code results} / {@code weighted360} (app.js ~891) for every subject of the
 * seeded Q2 (a group hidden) and Q3 (groups merged into Peers combined) cycles.
 */
class Review360ResultsTest
{
    private static final String RESULTS = "(function(c,p){var r=results(c,p),w=weighted360(c,p),avg={};"
            + "Object.keys(r.avg).forEach(function(g){var o={};V360.forEach(function(v,i){o[v]=r.avg[g][i]});"
            + "avg[g==='peer'?'internal':g]=o});var wo={};V360.forEach(function(v,i){wo[v]=w[i]});"
            + "return {responded:r.n,averages:avg,weighted:wo}})";

    /** Hidden strengths and blind spots as the prototype lists them (value names). */
    private static final String GAPS = "(function(c,p){var r=results(c,p),h=[],b=[];if(!r.self)return {hidden:h,blind:b};"
            + "V360.forEach(function(v,i){var s=r.self[i],o=r.others[i];if(s===null||o===null)return;"
            + "if(o-s>=0.75)h.push(v);if(s-o>=0.75)b.push(v)});return {hidden:h,blind:b}})";

    private static final JsonObject WEIGHTS = new JsonObject().put("manager", 40).put("internal", 25).put("cross", 15).put("self", 20);

    private static Oracle oracle;

    /** cycle → subject → responses in the reviews module's shape. */
    private static final Map<String, Map<String, JsonArray>> RESPONSES = new LinkedHashMap<>();

    @BeforeAll
    static void start() throws Exception
    {
        oracle = Oracle.start();

        for (Object o : new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "review_requests.json"))))
        {
            JsonObject r = (JsonObject) o;

            String cycle = r.getString("cycle");

            if (!cycle.equals("c0") && !cycle.equals("c1"))
            {
                continue;
            }

            RESPONSES.computeIfAbsent(cycle, k -> new LinkedHashMap<>())
                    .computeIfAbsent(r.getString("subject"), k -> new JsonArray())
                    .add(moduleShape(r));
        }
    }

    @AfterAll
    static void stop()
    {
        oracle.close();
    }

    @AfterEach
    void reset()
    {
        oracle.reset();
    }

    /** A prototype request as review_requests stores it (V340, S340 + S341). */
    private static JsonObject moduleShape(JsonObject r)
    {
        JsonObject answers = r.getJsonObject("answers") == null ? new JsonObject() : r.getJsonObject("answers");

        JsonObject scores = new JsonObject();

        JsonArray list = answers.getJsonArray("scores", new JsonArray());

        for (int i = 0; i < list.size(); i++)
        {
            scores.put(ReviewFormulas.RATED_VALUES.get(i), list.getValue(i));
        }

        String status = switch (r.getString("status"))
        {
            case "Submitted" -> "submitted";
            case "Draft" -> "in_progress";
            default -> "not_started";
        };

        return new JsonObject()
                .put("group", "peer".equals(r.getString("group")) ? "internal" : r.getString("group"))
                .put("status", status)
                .put("scores", scores)
                .put("keepDoing", answers.getString("keep", ""))
                .put("startDoing", answers.getString("start", ""));
    }

    private static JsonObject results(JsonArray responses)
    {
        return Review360Results.of(responses, ReviewFormulas.RATED_VALUES, 3, WEIGHTS);
    }

    private static JsonObject response(String group, int score, String keep)
    {
        JsonObject scores = new JsonObject();

        ReviewFormulas.RATED_VALUES.forEach(v -> scores.put(v, score));

        return new JsonObject().put("group", group).put("status", "submitted").put("scores", scores)
                .put("keepDoing", keep).put("startDoing", "Start " + keep);
    }

    // ------------------------------------------------------------------ oracle

    @Test
    void averagesAndWeightedScoresMatchTheOracleForEverySubject()
    {
        int compared = 0;

        for (Map.Entry<String, Map<String, JsonArray>> cycle : RESPONSES.entrySet())
        {
            for (Map.Entry<String, JsonArray> subject : cycle.getValue().entrySet())
            {
                oracle.compare(RESULTS, Oracle.args(cycle.getKey(), subject.getKey()), 4, args ->
                {
                    JsonObject r = results(subject.getValue());

                    return new JsonObject().put("responded", r.getInteger("responded")).put("averages", r.getJsonObject("averages"))
                            .put("weighted", r.getJsonObject("weighted"));
                });

                compared++;
            }
        }

        assertEquals(12, compared);
    }

    @Test
    void gapsMatchTheOracleWhereNoGroupIsHidden()
    {
        // In Q3 every anonymous response is shown (merged into Peers combined), so "others" is the same
        // as the prototype's. In Q2 the cross-department group is hidden and the port leaves it out.
        for (Map.Entry<String, JsonArray> subject : RESPONSES.get("c1").entrySet())
        {
            oracle.compare(GAPS, Oracle.args("c1", subject.getKey()), 0, args ->
            {
                JsonObject r = results(subject.getValue());

                JsonArray hidden = new JsonArray();

                JsonArray blind = new JsonArray();

                r.getJsonArray("hiddenStrengths").forEach(x -> hidden.add(((JsonObject) x).getString("value")));

                r.getJsonArray("blindSpots").forEach(x -> blind.add(((JsonObject) x).getString("value")));

                return new JsonObject().put("hidden", hidden).put("blind", blind);
            });
        }
    }

    // ------------------------------------------------------------------ spec

    @Test
    void groupUnderThresholdIsHiddenEverywhere()
    {
        // Spec "Group under threshold": cross-department has 2 responses, threshold 3.
        JsonArray responses = JsonArray.of(response("self", 4, "s"), response("manager", 4, "m"),
                response("internal", 3, "i1"), response("internal", 3, "i2"), response("internal", 3, "i3"),
                response("cross", 1, "secret one"), response("cross", 1, "secret two"));

        JsonObject r = results(responses);

        assertEquals("hidden", r.getJsonObject("groups").getJsonObject("cross").getString("state"));

        assertEquals(2, r.getJsonObject("groups").getJsonObject("cross").getInteger("submitted"));

        assertFalse(r.getJsonObject("averages").containsKey("cross"));

        assertFalse(r.getJsonObject("averages").containsKey("others"));

        assertFalse(r.encode().contains("secret"), r.encodePrettily());

        // The hidden ratings (1) never pull the weighted score down: self 4 × 20 + manager 4 × 40 +
        // internal 3 × 25, over 85.
        assertEquals(315.0 / 85, r.getJsonObject("weighted").getDouble("Collaboration"), 1e-9);
    }

    @Test
    void smallGroupsMergeIntoPeersCombinedAtTheThreshold()
    {
        JsonArray responses = JsonArray.of(response("internal", 4, "a"), response("internal", 4, "b"), response("cross", 1, "c"));

        JsonObject r = results(responses);

        assertEquals("merged", r.getJsonObject("groups").getJsonObject("internal").getString("state"));

        assertEquals("merged", r.getJsonObject("groups").getJsonObject("cross").getString("state"));

        assertEquals(3.0, r.getJsonObject("averages").getJsonObject("others").getDouble("Resilience"), 1e-9);

        assertEquals(3, r.getJsonObject("comments").getJsonArray("others").size());

        assertFalse(r.getJsonObject("averages").containsKey("internal"));
    }

    @Test
    void hiddenStrengthsAndBlindSpotsUseTheGap()
    {
        JsonObject self = response("self", 2, "s");

        self.getJsonObject("scores").put("Resilience", 5).put("Moral & Ethics", null);

        JsonArray responses = JsonArray.of(self, response("manager", 4, "m"));

        JsonObject r = results(responses);

        Set<String> hidden = new LinkedHashSet<>();

        r.getJsonArray("hiddenStrengths").forEach(x -> hidden.add(((JsonObject) x).getString("value")));

        assertTrue(hidden.contains("Collaboration"));

        assertFalse(hidden.contains("Moral & Ethics"), "Not observed is left out");

        assertEquals("Resilience", r.getJsonArray("blindSpots").getJsonObject(0).getString("value"));

        assertNull(r.getJsonObject("averages").getJsonObject("self").getDouble("Moral & Ethics"));
    }
}
