package com.pulse.modules.work;

import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 8.2: Overview, Work items and the issue drawer on a real server with the Track 2 demo seed and
 * "today" pinned to 28 Sep 2026: contract tests per role and the scenarios of spec work/work-items (idle
 * across a holiday is a calendar rule, covered in {@code WorkloadTest}; onboarding needs Track 3's
 * {@code people.onboarding} port and is covered in {@code WorkViewFormulasTest}).
 */
class WorkRoutesTest
{

    private static final int PORT = 18_230;

    private static final String API = "/api/v1/work";

    private static TestApi api;

    private static String priya;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress");

        priya = api.queryOne("SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    // ------------------------------------------------------------------ role matrix

    @Test
    void overviewFollowsVisibility() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/people/{person:priya@pulse.demo}/overview", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 403));
    }

    @Test
    void workItemsFollowVisibility() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/people/{person:priya@pulse.demo}/work-items?period=s:S42", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 403));
    }

    @Test
    void aPeersIssueDrawerIsRefused() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/issues/PLAT-2231", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 404));       // the same answer as a missing issue
    }

    // ------------------------------------------------------------------ overview

    @Test
    void theLiveOverviewHasKpisSignalsAndTheIndex() throws Exception
    {
        JsonObject body = get(API + "/people/" + priya + "/overview", RoleMatrix.EMPLOYEE);

        assertTrue(body.getBoolean("live"));
        assertEquals("s:S43", body.getJsonObject("period").getString("key"));
        assertEquals("Priya Shah", body.getJsonObject("person").getString("name"));
        assertEquals("Rahul Nair", body.getJsonObject("person").getString("manager"));

        JsonObject kpis = body.getJsonObject("kpis");

        assertEquals(8, kpis.getInteger("tasks"));
        assertEquals(6.0, kpis.getDouble("focus"), 1e-9);

        JsonArray attention = body.getJsonArray("needsAttention");

        assertFalse(attention.isEmpty());
        assertTrue(attention.stream().allMatch(i -> !((JsonObject) i).getJsonArray("flags").isEmpty()), "only issues with a signal");

        assertEquals(8, body.getJsonObject("utilizationTrend").getJsonArray("sprints").size());
        assertNotNull(body.getJsonObject("index").getValue("value"));
        assertNotNull(body.getJsonObject("bugs"));
    }

    @Test
    void anOverloadedSprintStatesTheDaysOverAndItsBand() throws Exception
    {
        int overloaded = 0;

        for (String person : api.sql().queryList("SELECT id FROM people WHERE source = 'seed'").toCompletionStage().toCompletableFuture().get()
                .stream().map(p -> ((JsonObject) p).getString("id")).toList())
        {
            JsonObject body = get(API + "/people/" + person + "/overview", RoleMatrix.ADMIN);

            JsonObject kpis = body.getJsonObject("kpis");

            JsonObject alert = body.getJsonObject("alert");

            if (kpis.getInteger("utilization") > 100 && !kpis.getBoolean("onboarding"))
            {
                overloaded++;

                assertEquals("overloaded", alert.getString("kind"));
                assertEquals(kpis.getDouble("workDays") - kpis.getInteger("capacity"), alert.getDouble("daysOver"), 1e-9);
                assertEquals(kpis.getInteger("utilization") > 120, alert.getBoolean("critical"));
                assertEquals(kpis.getJsonArray("band"), alert.getJsonArray("band"));
            }
        }

        assertTrue(overloaded > 0, "the seed has an overloaded sprint");
    }

    @Test
    void aReleaseOverviewHasTheReleaseCard() throws Exception
    {
        JsonObject body = get(API + "/people/" + priya + "/overview?period=r:8.3", RoleMatrix.EMPLOYEE);

        assertFalse(body.getBoolean("live"));

        JsonObject release = body.getJsonObject("release");

        assertEquals("8.3", release.getString("key"));
        assertTrue(release.getJsonArray("sprints").contains("s:S43"));
        assertTrue(release.getDouble("teamDone") >= release.getDouble("personDone"));
        assertNotNull(release.getValue("share"));
        assertNotNull(release.getValue("workingDaysLeft"));
    }

    @Test
    void aClosedSprintOverviewHasItsMetrics() throws Exception
    {
        JsonObject body = get(API + "/people/" + priya + "/overview?period=s:S42", RoleMatrix.EMPLOYEE);

        assertFalse(body.getBoolean("live"));
        assertNotNull(body.getJsonObject("metrics").getValue("com"));
        assertEquals(1, body.getJsonArray("sprints").size());
        assertNull(body.getValue("release"));
    }

    // ------------------------------------------------------------------ work items

    @Test
    void filterAndSearchKeepOnlyMatchingStaleIssuesWithCountsOnTheChips() throws Exception
    {
        // two stale issues (PLAT-2217 "SNMP trap parser", PLAT-2271 "... dropped traps"); search matches key or summary, any case
        JsonObject body = get(API + "/people/" + priya + "/work-items?filter=stale&q=snmp", RoleMatrix.EMPLOYEE);

        JsonArray items = body.getJsonArray("items");

        assertEquals(1, items.size(), items.encode());

        JsonObject issue = items.getJsonObject(0);

        assertEquals("PLAT-2217", issue.getString("key"));

        assertTrue(issue.getJsonArray("flags").stream().map(f -> ((JsonArray) f).getString(1))
                .anyMatch(l -> l.equals("Stale") || l.equals("Not started")), issue.encode());

        JsonArray byKey = get(API + "/people/" + priya + "/work-items?filter=stale&q=plat-227", RoleMatrix.EMPLOYEE).getJsonArray("items");

        assertEquals(1, byKey.size(), byKey.encode());
        assertEquals("PLAT-2271", byKey.getJsonObject(0).getString("key"));

        JsonObject chips = body.getJsonObject("chips");

        assertEquals(8, chips.getInteger("all"));
        assertEquals(2, chips.getInteger("stale"));
        assertEquals(chips.getInteger("all"), chips.getInteger("open") + chips.getInteger("done"));
        assertNotNull(body.getJsonObject("dailyLoad").getJsonArray("days"));
    }

    @Test
    void historyFiltersAndSprintsForAMonth() throws Exception
    {
        JsonObject body = get(API + "/people/" + priya + "/work-items?period=m:2026-08&filter=done", RoleMatrix.EMPLOYEE);

        assertFalse(body.getBoolean("live"));
        assertTrue(body.getJsonArray("items").stream().allMatch(i -> "Done".equals(((JsonObject) i).getString("status"))));
        assertEquals(body.getJsonObject("chips").getInteger("done"), body.getJsonArray("items").size());
        assertFalse(body.getJsonArray("sprints").isEmpty());
    }

    @Test
    void badFiltersAndPeriodsAreRefused() throws Exception
    {
        HttpResponse<Buffer> filter = api.get(API + "/people/" + priya + "/work-items?filter=nonsense",
                RoleMatrix.session(api, RoleMatrix.EMPLOYEE));

        assertEquals(400, filter.statusCode());
        assertEquals("filter", TestApi.error(filter).getString("field"));

        HttpResponse<Buffer> period = api.get(API + "/people/" + priya + "/overview?period=x:1",
                RoleMatrix.session(api, RoleMatrix.EMPLOYEE));

        assertEquals(400, period.statusCode());
        assertEquals("period", TestApi.error(period).getString("field"));
    }

    // ------------------------------------------------------------------ drawer

    @Test
    void theDrawerShowsTimeChecksAndGreysOutBotAndPlanningEvents() throws Exception
    {
        JsonObject body = get(API + "/issues/PLAT-2271", RoleMatrix.EMPLOYEE);

        JsonObject checks = body.getJsonObject("checks");

        assertTrue(checks.getDouble("loggedShare") < 0.3, checks.encode());
        assertTrue(checks.getBoolean("lowLogged"));
        assertTrue(body.getJsonArray("flags").stream().anyMatch(f -> "Low logged time".equals(((JsonArray) f).getString(1))));
        assertEquals("Priya Shah", body.getJsonObject("assignee").getString("name"));

        JsonArray timeline = body.getJsonArray("timeline");

        assertTrue(timeline.stream().map(e -> (JsonObject) e).anyMatch(e -> e.getBoolean("isBot") && !e.getBoolean("counted")));
        assertTrue(timeline.stream().map(e -> (JsonObject) e).anyMatch(e -> "sprint_change".equals(e.getString("kind")) && !e.getBoolean("counted")));
        assertTrue(timeline.stream().map(e -> (JsonObject) e).anyMatch(e -> e.getBoolean("counted")));
    }

    private static JsonObject get(String path, String email) throws Exception
    {
        HttpResponse<Buffer> res = api.get(path, RoleMatrix.session(api, email));

        assertEquals(200, res.statusCode(), path + ": " + res.bodyAsString());

        return res.bodyAsJsonObject();
    }
}
