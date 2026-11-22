package com.pulse.platform.shell;

import com.pulse.testsupport.TestApi;
import com.pulse.testsupport.TestApi.Session;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.pulse.testsupport.TestApi.DEMO_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 6.4: the shell reads. Demo cards only with the demo seed on, the period choices, the Jira
 * sync line for every role, and the person selector limited to the policy scope.
 */
class ShellRoutesTest
{
    private static final int PORT = 18_199;

    private static final int PORT_NO_DEMO = 18_189;

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28").put("demoSeed", true));
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
    void demoCardsArePublicWhileTheDemoSeedIsOn() throws Exception
    {
        HttpResponse<Buffer> res = api.get("/api/v1/auth/options", null);

        assertEquals(200, res.statusCode());

        JsonObject demo = res.bodyAsJsonObject().getJsonObject("demo");

        assertEquals("demo123", demo.getString("password"));
        assertEquals(List.of("Admin", "Manager", "Employee"), demo.getJsonArray("accounts").stream()
                .map(a -> ((JsonObject) a).getString("role")).toList(), "one card per role, in role order");
        assertEquals("hr@pulse.demo", demo.getJsonArray("accounts").getJsonObject(0).getString("email"));
        assertTrue(demo.getJsonArray("others").contains("Arjun Mehta"));
    }

    @Test
    void demoCardsAreHiddenWhenTheDemoSeedIsOff() throws Exception
    {
        try (TestApi off = TestApi.start(PORT_NO_DEMO, new JsonObject().put("demoSeed", false)))
        {
            HttpResponse<Buffer> res = off.get("/api/v1/auth/options", null);

            assertEquals(200, res.statusCode());
            assertNull(res.bodyAsJsonObject().getValue("demo"));
        }
    }

    @Test
    void shellReadsNeedASession() throws Exception
    {
        for (String path : List.of("/api/v1/periods", "/api/v1/sync-status", "/api/v1/people"))
        {
            assertEquals(401, api.get(path, null).statusCode(), path);
        }
    }

    @Test
    void periodsListSprintsMonthsAndReleasesWithTheActiveSprint() throws Exception
    {
        JsonObject body = api.get("/api/v1/periods", signIn("priya@pulse.demo")).bodyAsJsonObject();

        assertEquals("2026-09-28", body.getString("today"));
        assertEquals("s:S43", body.getString("activeSprint"));

        JsonArray sprints = body.getJsonArray("sprints");

        assertEquals("s:S43", sprints.getJsonObject(0).getString("key"), "newest first");
        assertTrue(sprints.getJsonObject(0).getString("label").endsWith("(active)"));
        assertTrue(keys(sprints).contains("s:S42"));

        JsonArray months = body.getJsonArray("months");

        assertEquals(12, months.size());
        assertEquals("m:2026-09", months.getJsonObject(0).getString("key"));
        assertTrue(months.getJsonObject(0).getString("label").endsWith("(to date)"));
        assertFalse(body.getJsonArray("releases").isEmpty());
    }

    @Test
    void everyRoleSeesTheJiraSyncLine() throws Exception
    {
        api.exec("INSERT INTO sync_log (tool, outcome, started_at, finished_at, simulated) "
                + "VALUES ('jira', 'success', now() - interval '5 minutes', now() - interval '4 minutes', true)");

        for (String email : List.of("hr@pulse.demo", "rahul@pulse.demo", "priya@pulse.demo"))
        {
            HttpResponse<Buffer> res = api.get("/api/v1/sync-status", signIn(email));

            assertEquals(200, res.statusCode(), email);

            JsonObject body = res.bodyAsJsonObject();

            assertEquals("jira", body.getString("tool"));
            assertEquals("synced", body.getString("state"));
            assertTrue(body.getValue("lastSyncedAt") != null);
        }
    }

    @Test
    void syncStateFollowsTheNewestRun()
    {
        assertEquals("never", ShellRoutes.state(null));
        assertEquals("syncing", ShellRoutes.state(new JsonObject().put("outcome", "running")));
        assertEquals("synced", ShellRoutes.state(new JsonObject().put("outcome", "success")));
        assertEquals("failed", ShellRoutes.state(new JsonObject().put("outcome", "failed")));
    }

    @Test
    void personSelectorFollowsThePolicyScope() throws Exception
    {
        api.exec("INSERT INTO people (name, title, department, source, external_id) "
                + "VALUES ('Outside Person', 'Engineer', 'Other', 'test', 'shell-outsider') ON CONFLICT DO NOTHING");

        Set<String> employee = names(api.get("/api/v1/people", signIn("priya@pulse.demo")).bodyAsJsonArray());

        assertEquals(Set.of("Priya Shah"), employee, "an Employee sees only themselves");

        Set<String> manager = names(api.get("/api/v1/people", signIn("rahul@pulse.demo")).bodyAsJsonArray());

        assertTrue(manager.contains("Priya Shah") && manager.contains("Arjun Mehta"), manager.toString());

        JsonArray admin = api.get("/api/v1/people", signIn("hr@pulse.demo")).bodyAsJsonArray();

        assertEquals(api.count("SELECT count(*) AS n FROM people"), admin.size(), "all.view sees everyone");
        assertTrue(names(admin).containsAll(manager));
        assertFalse(manager.contains("Outside Person"), "a Manager sees only their reports: " + manager);
        assertTrue(names(admin).contains("Outside Person"));
    }

    private static Session signIn(String email) throws Exception
    {
        return api.signIn(email, DEMO_PASSWORD);
    }

    private static Set<String> keys(JsonArray periods)
    {
        return periods.stream().map(p -> ((JsonObject) p).getString("key")).collect(Collectors.toSet());
    }

    private static Set<String> names(JsonArray people)
    {
        return people.stream().map(p -> ((JsonObject) p).getString("name")).collect(Collectors.toSet());
    }
}
