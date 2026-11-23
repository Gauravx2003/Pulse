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
 * Task 13.1: the Action center on a real server with the Track 2 demo seed, Track 3's visits module and
 * "today" pinned to 28 Sep 2026: the role matrix, Track 2's providers, the order, the High count behind
 * the menu badge, the Admin-only integration items, and a provider from Track 3 (customer visits)
 * appearing with no change to Track 2 code.
 */
class ActionCenterRoutesTest
{

    private static final int PORT = 18_270;

    private static final String API = "/api/v1/action_center/items";

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress",
                "okrs", "roadmap", "integrations", "visits", "action_center");
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    @Test
    void theActionCenterIsForManagersAndAdmins() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API, Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));
    }

    @Test
    void aManagerGetsOneSortedListFromEveryModule() throws Exception
    {
        JsonObject body = get(RoleMatrix.MANAGER);

        JsonArray items = body.getJsonArray("items");

        List<Integer> ranks = items.stream().map(i -> ActionItems.rank(((JsonObject) i).getString("priority"))).toList();

        for (int i = 1; i < ranks.size(); i++)
        {
            assertTrue(ranks.get(i - 1) <= ranks.get(i), "sorted High, Medium, Low");
        }

        assertTrue(body.getJsonArray("unavailable").isEmpty(), body.encode());

        // the prototype's actionItems() as rahul@ on the same seed reads exactly these Track 2 items
        assertItem(items, "work.stale", "Medium", "Priya Shah", "3 issues without an update (PLAT-2198, PLAT-2217, PLAT-2271)");
        assertItem(items, "progress.low-pulse", "High", "Priya Shah",
                "Weekly pulse: mood 2/5, workload 5/5 \u2014 \u201cSprint feels overloaded; customer visit next week too\u201d");
        assertItem(items, "progress.low-pulse", "High", "Neha Joshi",
                "Weekly pulse: mood 2/5, workload 2/5 \u2014 \u201cNot sure I am improving on Jira updates\u201d");
        assertItem(items, "effort.to-confirm", "Medium", "Team", "16 done items waiting for your confirmation");
        assertItem(items, "bugs.high-severity", "Medium", "Team", "2 high-severity bugs open (BUG-4101, BUG-4102)");
        assertItem(items, "pull_requests.rubber-stamp", "Low", "Team", "2 rubber-stamp approvals this sprint (big PRs, no comments)");

        assertEquals("work-items", find(items, "work.stale", "Priya Shah").getJsonObject("target").getString("module"));

        assertTrue(items.stream().map(i -> (JsonObject) i).noneMatch(i -> "integrations.health".equals(i.getString("key"))),
                "integration items are for Admins");
    }

    @Test
    void aTrack3ProviderAppearsWithoutTrack2Code() throws Exception
    {
        JsonArray items = get(RoleMatrix.MANAGER).getJsonArray("items");

        assertTrue(items.stream().map(i -> (JsonObject) i).anyMatch(i -> "Customer visits".equals(i.getString("source"))), items.encode());
    }

    @Test
    void anAdminSeesTheSlackTokenNearExpiry() throws Exception
    {
        JsonObject slack = find(get(RoleMatrix.ADMIN).getJsonArray("items"), "integrations.health", "Slack");

        assertEquals("Low", slack.getString("priority"));
        assertEquals("Token expires in 3 days", slack.getString("text"));
        assertEquals("Fix", slack.getString("button"));
    }

    @Test
    void theMenuBadgeCountsTheHighItems() throws Exception
    {
        JsonObject body = get(RoleMatrix.MANAGER);

        HttpResponse<Buffer> badges = api.get("/api/v1/notifications/badges", RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertEquals(200, badges.statusCode(), badges.bodyAsString());
        assertEquals(body.getJsonObject("counts").getInteger("High"), badges.bodyAsJsonObject().getJsonObject("badges").getInteger("actions", 0),
                badges.bodyAsString());
    }

    @Test
    void everyOverloadItemIsHighWithRebalance() throws Exception
    {
        for (Object item : get(RoleMatrix.ADMIN).getJsonArray("items"))
        {
            JsonObject i = (JsonObject) item;

            if ("work.overload".equals(i.getString("key")))
            {
                assertEquals("High", i.getString("priority"));
                assertEquals("Rebalance", i.getString("button"));
                assertFalse(i.getString("text").isBlank());
            }
        }
    }

    private static void assertItem(JsonArray items, String key, String priority, String personName, String text)
    {
        JsonObject item = find(items, key, personName);

        assertEquals(priority, item.getString("priority"), key);
        assertEquals(text, item.getString("text"), key);
    }

    private static JsonObject find(JsonArray items, String key, String personName)
    {
        return items.stream().map(i -> (JsonObject) i)
                .filter(i -> key.equals(i.getString("key")) && personName.equals(i.getString("personName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(key + " for " + personName + " in " + items.encode()));
    }

    private static JsonObject get(String email) throws Exception
    {
        HttpResponse<Buffer> res = api.get(API, RoleMatrix.session(api, email));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }
}
