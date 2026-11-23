package com.pulse.modules.utilization;

import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 9.1: Team utilization on a real server with the Track 2 demo seed and "today" pinned to
 * 28 Sep 2026: the role matrix, the live sprint (KPIs, PLAT-2198 suggestion, heatmap, people table),
 * closed periods and the report-only rule of spec work/team-utilization. The prototype's exact numbers
 * are checked in {@code TeamFormulasTest}; band edges, onboarding and the skills match in
 * {@code TeamViewsTest}.
 */
class UtilizationRoutesTest
{

    private static final int PORT = 18_240;

    private static final String API = "/api/v1/utilization/team";

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"),
                "work", "pull_requests", "bugs", "effort", "progress", "roadmap", "utilization");
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    @Test
    void onlyManagersAndAdminsOpenTeamUtilization() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API, Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));
    }

    @Test
    void theLiveSprintSuggestsMovingPlat2198FromPriyaToDev() throws Exception
    {
        // The exact numbers of the spec text (171% with Priya's leave and visit days) need Track 3's leave and visit
        // ports; TeamFormulasTest checks them with stand-ins. Without the ports Priya is still the one over capacity.
        JsonObject body = get(API, RoleMatrix.MANAGER);

        assertTrue(body.getBoolean("live"));
        assertEquals("s:S43", body.getJsonObject("period").getString("key"));

        JsonObject suggestion = body.getJsonObject("suggestion");

        assertEquals("PLAT-2198", suggestion.getJsonObject("issue").getString("key"));
        assertEquals("Priya Shah", suggestion.getJsonObject("from").getString("name"));
        assertEquals("Dev Patel", suggestion.getJsonObject("to").getString("name"));
        assertTrue(suggestion.getString("text").startsWith("Move PLAT-2198 (24 h) from Priya Shah ("), suggestion.getString("text"));
        assertTrue(suggestion.getJsonObject("from").getInteger("after") < suggestion.getJsonObject("from").getInteger("utilization"));

        JsonObject kpis = body.getJsonObject("kpis");

        assertEquals("Priya Shah", kpis.getJsonArray("overloaded").getJsonObject(0).getString("name"));

        int stale = 0;

        int open = 0;

        for (Object item : body.getJsonArray("people"))
        {
            stale += ((JsonObject) item).getInteger("stale");

            open += ((JsonObject) item).getInteger("open");
        }

        assertEquals(stale, kpis.getInteger("stale"));
        assertEquals(open, kpis.getInteger("open"));
    }

    @Test
    void thePeopleTableIsSortedAndTheHeatmapCoversTheSprint() throws Exception
    {
        JsonObject body = get(API, RoleMatrix.MANAGER);

        JsonArray people = body.getJsonArray("people");

        assertFalse(people.isEmpty());

        for (int i = 1; i < people.size(); i++)
        {
            assertTrue(people.getJsonObject(i - 1).getDouble("utilization") >= people.getJsonObject(i).getDouble("utilization"),
                    "sorted by utilization");
        }

        JsonObject first = people.getJsonObject(0);

        assertEquals("Priya Shah", first.getString("name"));
        assertNotNull(first.getValue("capacity"));
        assertNotNull(first.getValue("index"));

        JsonObject heatmap = body.getJsonObject("heatmap");

        JsonArray days = heatmap.getJsonArray("days");

        assertFalse(days.isEmpty());
        assertTrue(days.contains("2026-09-28"));
        assertEquals(people.size(), heatmap.getJsonArray("rows").size());

        for (Object item : heatmap.getJsonArray("rows"))
        {
            JsonArray cells = ((JsonObject) item).getJsonArray("cells");

            assertEquals(days.size(), cells.size());

            for (Object c : cells)
            {
                JsonObject cell = (JsonObject) c;

                assertTrue(cell.getValue("value") != null || cell.getString("label") != null, cell.encode());
            }
        }
    }

    @Test
    void aManagerSeesOnlyTheirReports() throws Exception
    {
        Set<String> reports = new HashSet<>();

        for (Object row : api.sql().queryList("""
                SELECT p.id FROM people p JOIN reporting_lines l ON l.user_id = p.user_id AND l.valid_to IS NULL
                JOIN users m ON m.id = l.manager_id WHERE lower(m.email) = 'rahul@pulse.demo'""").toCompletionStage().toCompletableFuture().get())
        {
            reports.add(((JsonObject) row).getString("id"));
        }

        JsonObject body = get(API, RoleMatrix.MANAGER);

        for (Object item : body.getJsonArray("people"))
        {
            assertTrue(reports.contains(((JsonObject) item).getString("id")), ((JsonObject) item).encode());
        }

        assertEquals(reports.size(), body.getJsonObject("team").getInteger("people"));

        long everyone = api.count("SELECT count(*) FROM people");

        assertEquals(everyone, get(API, RoleMatrix.ADMIN).getJsonObject("team").getInteger("people").longValue());
    }

    @Test
    void aClosedSprintHasLoggedUtilizationAndNoSuggestion() throws Exception
    {
        JsonObject body = get(API + "?period=s:S42", RoleMatrix.MANAGER);

        assertFalse(body.getBoolean("live"));
        assertNull(body.getValue("suggestion"));
        assertTrue(absent(body.getValue("release")));
        assertTrue(body.getJsonArray("sprints").isEmpty(), "one sprint: no chart");

        JsonObject kpis = body.getJsonObject("kpis");

        assertNotNull(kpis.getValue("utilization"));
        assertNotNull(kpis.getValue("storyPointsDone"));
        assertFalse(body.getJsonArray("people").isEmpty());
    }

    @Test
    void aReleaseHasItsCardAndUtilizationBySprint() throws Exception
    {
        JsonObject body = get(API + "?period=r:8.2", RoleMatrix.MANAGER);

        JsonObject release = body.getJsonObject("release");

        assertEquals("8.2", release.getString("key"));
        assertEquals(body.getJsonObject("kpis").getDouble("storyPointsDone"), release.getDouble("teamDone"), 1e-9);
        assertNull(release.getValue("personDone"));

        JsonArray sprints = body.getJsonArray("sprints");

        assertEquals(5, sprints.size(), sprints.encode());
        assertEquals("s:S38", sprints.getJsonObject(0).getString("period"), "oldest first");
    }

    @Test
    void theCurrentMonthShowsTheLiveSprintAsAForecast() throws Exception
    {
        JsonArray sprints = get(API + "?period=m:2026-09", RoleMatrix.ADMIN).getJsonArray("sprints");

        assertTrue(sprints.size() > 1, sprints.encode());

        JsonObject last = sprints.getJsonObject(sprints.size() - 1);

        assertEquals("s:S43", last.getString("period"));
        assertTrue(last.getBoolean("live"), "S43 is a forecast");
    }

    @Test
    void anUnknownPeriodIsRefused() throws Exception
    {
        HttpResponse<Buffer> res = api.get(API + "?period=x:1", RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertEquals(400, res.statusCode());
        assertEquals("period", TestApi.error(res).getString("field"));
    }

    /**
     * Null, or "not applicable" as CalcService guards a missing value.
     */
    private static boolean absent(Object value)
    {
        return value == null || value instanceof JsonObject o && o.getBoolean("notApplicable", false);
    }

    private static JsonObject get(String path, String email) throws Exception
    {
        HttpResponse<Buffer> res = api.get(path, RoleMatrix.session(api, email));

        assertEquals(200, res.statusCode(), path + ": " + res.bodyAsString());

        return res.bodyAsJsonObject();
    }
}
