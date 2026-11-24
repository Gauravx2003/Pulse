package com.pulse.modules.action_center;

import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 13.2: the Action center's insight sections on a real server with the Track 2 demo seed and "today"
 * pinned to 28 Sep 2026: the role matrix of every section, people signals (spec "Signal set" and
 * "Visibility"), the sprint forecast ("Does not fit"), next-sprint capacity ("Team total"), skills
 * coverage ("Single owner"), the team pulse, the digest preview and the page's one-request overview.
 */
class ActionCenterInsightsRoutesTest
{

    private static final int PORT = 18_271;

    private static final String API = "/api/v1/action_center";

    private static final List<String> SECTIONS = List.of("signals", "forecast", "capacity", "coverage", "pulse", "wins", "digest", "overview");

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress",
                "okrs", "roadmap", "action_center");
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    @Test
    void everySectionIsForManagersAndAdmins() throws Exception
    {
        for (String section : SECTIONS)
        {
            RoleMatrix.assertRoleMatrix(api, "GET " + API + "/" + section, Map.of(
                    RoleMatrix.ADMIN, 200,
                    RoleMatrix.MANAGER, 200,
                    RoleMatrix.EMPLOYEE, 403,
                    RoleMatrix.PEER, 403));
        }
    }

    @Test
    void peopleSignalsCoverTheReportsWithARiskAndANextStep() throws Exception
    {
        JsonObject body = get(RoleMatrix.MANAGER, "signals");

        JsonArray people = body.getJsonArray("people");

        assertFalse(people.isEmpty());
        assertTrue(people.stream().map(p -> ((JsonObject) p).getString("name")).noneMatch("Rahul Nair"::equals), "never the viewer");

        JsonObject priya = person(people, "Priya Shah");

        assertEquals("High", priya.getString("risk"));
        assertTrue(labels(priya).contains("Pulse check-in low"), priya.encode());
        assertTrue(body.getJsonArray("watch").contains("Priya Shah"));

        for (Object o : people)
        {
            JsonObject row = (JsonObject) o;

            assertTrue(List.of("High", "Watch", "Low", "OK").contains(row.getString("risk")));
            assertFalse(row.getString("next").isBlank());
        }

        for (int i = 1; i < people.size(); i++)
        {
            assertTrue(people.getJsonObject(i - 1).getJsonArray("signals").size() >= people.getJsonObject(i).getJsonArray("signals").size(),
                    "most signals first");
        }
    }

    @Test
    void theForecastListsWhatDoesNotFitAsLikelyToCarryOver() throws Exception
    {
        JsonObject body = get(RoleMatrix.MANAGER, "forecast");

        assertEquals("s:S43", body.getString("sprint"));

        JsonObject priya = person(body.getJsonArray("people"), "Priya Shah");

        assertTrue(priya.getDouble("remaining") > priya.getDouble("capacity"), priya.encode());
        assertFalse(priya.getJsonArray("carry").isEmpty(), "Priya's overflow is listed");
        assertTrue(body.getInteger("fitsPct") < 100);
        assertTrue(body.getInteger("carry") >= priya.getJsonArray("carry").size());
    }

    @Test
    void theTeamTotalSumsThePersonSuggestions() throws Exception
    {
        JsonObject body = get(RoleMatrix.MANAGER, "capacity");

        assertEquals("Sprint 44", body.getJsonObject("sprint").getString("name"));
        assertEquals("2026-10-12", body.getJsonObject("sprint").getString("from"));

        long sum = 0;

        double focus = 0;

        for (Object o : body.getJsonArray("people"))
        {
            JsonObject row = (JsonObject) o;

            focus += row.getDouble("focusDays");

            if (row.getValue("suggestedPoints") instanceof Number n)
            {
                sum += n.longValue();
            }
        }

        assertEquals(sum, body.getJsonObject("total").getLong("suggestedPoints"));
        assertEquals(Math.round(focus * 10) / 10.0, body.getJsonObject("total").getDouble("focusDays"), 0.001);
    }

    @Test
    void anAreaWithOneOwnerIsASinglePointOfFailure() throws Exception
    {
        JsonObject body = get(RoleMatrix.MANAGER, "coverage");

        JsonObject quota = body.getJsonArray("areas").stream().map(a -> (JsonObject) a)
                .filter(a -> "quota".equals(a.getString("area"))).findFirst().orElseThrow();

        assertEquals("Single point of failure", quota.getString("coverage"));
        assertTrue(quota.getString("suggestion").startsWith("Pair "), quota.getString("suggestion"));
        assertFalse(body.getJsonArray("people").isEmpty());
    }

    @Test
    void theTeamPulseHasSixWeeksAndTheLowAnswers() throws Exception
    {
        JsonObject body = get(RoleMatrix.MANAGER, "pulse");

        assertEquals(6, body.getJsonArray("weeks").size());
        assertTrue(body.getJsonArray("low").contains("Priya Shah"), body.encode());
        assertTrue(body.getDouble("mood") > 0);
    }

    @Test
    void theDigestPreviewSummarisesTheWeek() throws Exception
    {
        JsonObject digest = get(RoleMatrix.MANAGER, "digest");

        JsonObject items = api.get(API + "/items", RoleMatrix.session(api, RoleMatrix.MANAGER)).bodyAsJsonObject();

        assertEquals("2026-09-28", digest.getString("date"));
        assertEquals(items.getJsonArray("items").size(), digest.getInteger("total"));
        assertEquals(items.getJsonObject("counts").getInteger("High"), digest.getInteger("high"));
        assertTrue(digest.getJsonArray("items").size() <= 5);
        assertTrue(digest.getJsonArray("watch").stream().anyMatch(w -> "Priya Shah".equals(((JsonObject) w).getString("name"))));
    }

    @Test
    void theOverviewHasEverySectionOfThePageInOneRequest() throws Exception
    {
        JsonObject body = get(RoleMatrix.MANAGER, "overview");

        JsonObject sections = body.getJsonObject("sections");

        assertTrue(body.getJsonObject("errors").isEmpty(), body.getJsonObject("errors").encode());

        for (String section : List.of("items", "signals", "forecast", "capacity", "coverage", "pulse", "wins"))
        {
            assertTrue(sections.containsKey(section), section);
        }

        assertEquals(get(RoleMatrix.MANAGER, "signals"), sections.getJsonObject("signals"));
    }

    private static JsonObject get(String who, String section) throws Exception
    {
        HttpResponse<Buffer> res = api.get(API + "/" + section, RoleMatrix.session(api, who));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static JsonObject person(JsonArray rows, String name)
    {
        return rows.stream().map(r -> (JsonObject) r).filter(r -> name.equals(r.getString("name"))).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + rows.encode()));
    }

    private static List<String> labels(JsonObject row)
    {
        return row.getJsonArray("signals").stream().map(s -> ((JsonObject) s).getString("label")).toList();
    }
}
