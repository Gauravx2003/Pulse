package com.pulse.modules;

import com.pulse.calc.core.CoreThresholds;
import com.pulse.calc.core.RuleCatalogue;
import com.pulse.calc.work.WorkThresholds;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 23.6: the thresholds the screens show come from CalcService, so an Admin rule edit moves every
 * affected screen without a deploy. Overview's "No update &gt; N days" follows rule {@code staleDays}, the
 * Discipline panel's daily-update target follows {@code d4} and {@code d5}, and the PR standard meter
 * on Progress follows {@code prTarget}; the fixed thresholds arrive with the payloads
 * ({@code core.thresholds}, {@code work.thresholds}) instead of living in the UI.
 */
class RuleEditMovesScreensTest
{

    private static final int PORT = 18_275;

    private static final String QUARTER = "q:2026-Q3";

    private static TestApi api;

    private static TestApi.Session admin;

    private static String priya;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress",
                "okrs", "visits", "reviews", "training", "attendance", "culture");

        admin = RoleMatrix.session(api, RoleMatrix.ADMIN);

        priya = api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    @Test
    void overviewStaleLabelFollowsStaleDays() throws Exception
    {
        assertEquals(2, overview().getJsonObject("kpis").getInteger("staleAfter"));

        save(RuleCatalogue.STALENESS, new JsonObject().put("staleDays", 4));

        assertEquals(4, overview().getJsonObject("kpis").getInteger("staleAfter"));
    }

    @Test
    void disciplineTargetFollowsTheComplianceBands() throws Exception
    {
        JsonObject targets = culture().getJsonObject("discipline").getJsonObject("metrics").getJsonObject("targets");

        assertEquals(85, targets.getInteger("compliance"));
        assertEquals(95, targets.getInteger("complianceTop"));
        assertEquals(80, targets.getInteger("replan"));

        save(RuleCatalogue.DISCIPLINE, new JsonObject().put("d5", 90).put("d4", 75));

        JsonObject moved = culture().getJsonObject("discipline").getJsonObject("metrics").getJsonObject("targets");

        assertEquals(75, moved.getInteger("compliance"));
        assertEquals(90, moved.getInteger("complianceTop"));
        assertEquals(80, moved.getInteger("replan"), "rules c–h are fixed");
    }

    @Test
    void prStandardMeterFollowsPrTarget() throws Exception
    {
        JsonObject month = firstMonthWithCompliance();

        int comp = month.getInteger("comp");

        assertEquals(CoreThresholds.tone(comp, WorkThresholds.prComplianceTones(new JsonObject().put("prTarget", 85))), month.getString("tone"));

        // a target the month's compliance just reaches, or one above it: either way its tone changes
        int target = comp < 85 ? Math.max(50, comp) : 100;

        save(RuleCatalogue.PR_STANDARD, new JsonObject().put("prTarget", target));

        JsonObject after = firstMonthWithCompliance();

        String expected = CoreThresholds.tone(comp, WorkThresholds.prComplianceTones(new JsonObject().put("prTarget", target)));

        assertEquals(expected, after.getString("tone"));

        if (comp >= WorkThresholds.PR_COMPLIANCE_LOW && comp != 100)
        {
            assertFalse(month.getString("tone").equals(after.getString("tone")), "the meter moved with prTarget " + target + " at " + comp + "%");
        }
    }

    @Test
    void fixedThresholdsAndTonesArriveWithThePayloads() throws Exception
    {
        JsonArray score = culture().getJsonObject("tones").getJsonArray("score");

        assertEquals(CoreThresholds.SCORE_TONES, score);

        JsonArray answers = api.get("/api/v1/progress/people/" + priya + "/pulse", admin).bodyAsJsonObject().getJsonArray("items");

        assertFalse(answers.isEmpty(), "Priya has pulse answers");

        for (Object o : answers)
        {
            JsonObject a = (JsonObject) o;

            assertEquals(CoreThresholds.tone(a.getInteger("mood"), WorkThresholds.MOOD_TONES), a.getString("moodTone"));
            assertEquals(CoreThresholds.tone(a.getInteger("workload"), WorkThresholds.WORKLOAD_TONES), a.getString("workloadTone"));
        }

        for (Object o : progress().getJsonObject("trends").getJsonArray("culture"))
        {
            JsonObject row = (JsonObject) o;

            // a missing gain arrives guarded as {notApplicable: true}
            Number gain = row.getValue("gain") instanceof Number n ? n : null;

            assertEquals(WorkThresholds.gainTone(gain), row.getString("tone"), row.getString("value"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject overview() throws Exception
    {
        return ok(api.get("/api/v1/work/people/" + priya + "/overview", admin));
    }

    private static JsonObject culture() throws Exception
    {
        return ok(api.get("/api/v1/culture/people/" + priya + "?period=" + QUARTER, admin));
    }

    private static JsonObject progress() throws Exception
    {
        return ok(api.get("/api/v1/progress/people/" + priya, admin));
    }

    private static JsonObject firstMonthWithCompliance() throws Exception
    {
        for (Object o : progress().getJsonObject("trends").getJsonArray("prByMonth"))
        {
            JsonObject m = (JsonObject) o;

            if (m.getValue("comp") instanceof Number)
            {
                return m;
            }
        }

        throw new AssertionError("Priya has a month with PR checks");
    }

    private static void save(String group, JsonObject values) throws Exception
    {
        int version = api.get("/api/v1/rules/groups", admin).bodyAsJsonObject().getInteger("version");

        ok(api.put("/api/v1/rules/groups/" + group, admin, new JsonObject().put("values", values).put("version", version)));
    }

    private static JsonObject ok(HttpResponse<Buffer> res)
    {
        assertTrue(res.statusCode() == 200, res.statusCode() + " " + res.bodyAsString());

        JsonObject body = res.bodyAsJsonObject();

        assertNotNull(body);

        return body;
    }
}
