package com.pulse.modules.work;

import com.pulse.calc.core.RuleCatalogue;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 8.3: the Nudge action of the issue drawer on a real server with the Track 2 demo seed, Slack
 * connected (Integrations seed) and "today" pinned to 28 Sep 2026. Each test nudges its own issue and
 * sender pair, so the rate limit of one test does not reach another.
 */
class NudgeRoutesTest
{

    private static final int PORT = 18_231;

    private static final String API = "/api/v1/work";

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"),
                "work", "pull_requests", "bugs", "effort", "progress", "integrations", "rules");
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    @Test
    void onlyManagersWhoSeeTheIssueMayNudge() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "POST " + API + "/issues/PLAT-2217/nudge", new JsonObject(), Map.of(
                RoleMatrix.ADMIN, 201,
                RoleMatrix.MANAGER, 201,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));
    }

    @Test
    void theSecondNudgeInsideTheWindowIsRefused() throws Exception
    {
        long outbox = api.count("SELECT count(*) FROM outbox WHERE payload->>'issueKey' = 'PLAT-2271'");

        HttpResponse<Buffer> first = nudge("PLAT-2271", RoleMatrix.MANAGER);

        assertEquals(201, first.statusCode(), first.bodyAsString());

        JsonObject sent = first.bodyAsJsonObject();

        assertEquals("Priya Shah", sent.getString("recipient"));
        assertEquals("slack", sent.getString("channel"));

        HttpResponse<Buffer> second = nudge("PLAT-2271", RoleMatrix.MANAGER);

        assertEquals(429, second.statusCode(), second.bodyAsString());

        JsonObject error = TestApi.error(second);

        assertEquals("nudge_rate_limited", error.getString("code"));
        assertTrue(error.getString("message").contains("PLAT-2271"), error.encode());
        assertTrue(error.getLong("retryAfter") > 23 * 3600, error.encode());
        assertNotNull(second.getHeader("Retry-After"));

        // one record, one outbox message and one audit entry: the refused nudge left nothing behind
        String issue = api.queryOne("SELECT id FROM issues WHERE key = 'PLAT-2271'").getString("id");

        assertEquals(1, api.count("SELECT count(*) FROM nudges WHERE issue_id = '" + issue + "'"));
        assertEquals(outbox + 1, api.count("SELECT count(*) FROM outbox WHERE payload->>'issueKey' = 'PLAT-2271'"));
        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'work.issue.nudged' AND target_id = '" + issue + "'"));

        JsonObject message = api.queryOne("SELECT channel, recipient, subject FROM outbox WHERE payload->>'issueKey' = 'PLAT-2271'");

        assertEquals("slack", message.getString("channel"));
        assertEquals("priya@pulse.demo", message.getString("recipient"));

        // the in-app "stale-work nudge" to the assignee (spec platform/notifications), once
        JsonObject notice = api.queryOne("""
                SELECT n.text, n.link FROM notifications n JOIN users u ON u.id = n.user_id
                WHERE lower(u.email) = 'priya@pulse.demo' AND n.kind = 'work.stale_nudge' AND n.text LIKE '%PLAT-2271%'""");

        assertTrue(notice.getString("text").matches("Nudge: PLAT-2271 has had no activity for \\d+ days?"), notice.getString("text"));
        assertEquals("/work-items?issue=PLAT-2271", notice.getString("link"));
        assertEquals(1, api.count("SELECT count(*) FROM notifications WHERE kind = 'work.stale_nudge' AND text LIKE '%PLAT-2271%'"));

        // the limit is per sender: another manager may still nudge the same issue
        assertEquals(201, nudge("PLAT-2271", RoleMatrix.ADMIN).statusCode());

        // the drawer shows the state to the sender
        JsonObject state = drawer("PLAT-2271", RoleMatrix.MANAGER).getJsonObject("nudge");

        assertTrue(state.getBoolean("enabled"));
        assertTrue(state.getBoolean("nudgeable"));
        assertEquals("slack", state.getString("channel"));
        assertEquals(2L, state.getLong("count"));
        assertTrue(state.getLong("retryAfter") > 0);
    }

    @Test
    void anIssueThatIsNotStaleCannotBeNudged() throws Exception
    {
        String done = api.queryOne("""
                SELECT i.key FROM issues i JOIN people p ON p.id = i.assignee_id
                WHERE p.source = 'seed' AND p.external_id = 'ps' AND i.status = 'Done' ORDER BY i.key LIMIT 1""").getString("key");

        HttpResponse<Buffer> res = nudge(done, RoleMatrix.MANAGER);

        assertEquals(422, res.statusCode(), res.bodyAsString());
        assertEquals("not_stale", TestApi.error(res).getString("code"));

        JsonObject state = drawer(done, RoleMatrix.MANAGER).getJsonObject("nudge");

        assertEquals(false, state.getBoolean("nudgeable"));
        assertNull(state.getValue("retryAfter"));
    }

    @Test
    void aManagerCannotNudgeThemself() throws Exception
    {
        // the demo managers have no work of their own: give Rahul a person record and one issue for this test
        String rahul = api.queryOne("""
                INSERT INTO people (user_id, name, source, external_id)
                SELECT id, name, 'test', 'nudge-self' FROM users WHERE lower(email) = 'rahul@pulse.demo'
                RETURNING id""").getString("id");

        String owner = api.queryOne("SELECT assignee_id FROM issues WHERE key = 'PLAT-2240'").getString("assignee_id");

        api.exec("UPDATE issues SET assignee_id = '" + rahul + "' WHERE key = 'PLAT-2240'");

        try
        {
            HttpResponse<Buffer> res = nudge("PLAT-2240", RoleMatrix.MANAGER);

            assertEquals(422, res.statusCode(), res.bodyAsString());
            assertEquals("self_action_refused", TestApi.error(res).getString("code"));
        }
        finally
        {
            api.exec("UPDATE issues SET assignee_id = '" + owner + "' WHERE key = 'PLAT-2240'");

            api.exec("DELETE FROM people WHERE id = '" + rahul + "'");
        }
    }

    @Test
    void aMissingIssueAnswers404() throws Exception
    {
        HttpResponse<Buffer> res = nudge("NOPE-1", RoleMatrix.MANAGER);

        assertEquals(404, res.statusCode(), res.bodyAsString());
        assertEquals("not_found", TestApi.error(res).getString("code"));
    }

    @Test
    void nudgesTurnedOffInRulesAreRefused() throws Exception
    {
        TestApi.Session admin = RoleMatrix.session(api, RoleMatrix.ADMIN);

        assertEquals(200, saveNudgeRule(admin, false).statusCode());

        try
        {
            HttpResponse<Buffer> res = nudge("PLAT-2217", RoleMatrix.MANAGER);

            assertEquals(422, res.statusCode(), res.bodyAsString());
            assertEquals("nudge_disabled", TestApi.error(res).getString("code"));
        }
        finally
        {
            assertEquals(200, saveNudgeRule(admin, true).statusCode());
        }
    }

    @Test
    void withoutAConnectedChatToolANudgeIsRefused() throws Exception
    {
        api.exec("UPDATE integration_settings SET token_expires_on = '2026-09-01' WHERE tool = 'sl'");

        try
        {
            HttpResponse<Buffer> res = nudge("PLAT-2217", RoleMatrix.MANAGER);

            assertEquals(409, res.statusCode(), res.bodyAsString());
            assertEquals("chat_not_connected", TestApi.error(res).getString("code"));
        }
        finally
        {
            api.exec("UPDATE integration_settings SET token_expires_on = '2026-10-01' WHERE tool = 'sl'");
        }
    }

    private static HttpResponse<Buffer> nudge(String key, String email) throws Exception
    {
        return api.post(API + "/issues/" + key + "/nudge", RoleMatrix.session(api, email), new JsonObject());
    }

    private static HttpResponse<Buffer> saveNudgeRule(TestApi.Session admin, boolean on) throws Exception
    {
        int version = api.get("/api/v1/rules/groups", admin).bodyAsJsonObject().getInteger("version");

        return api.put("/api/v1/rules/groups/" + RuleCatalogue.STALENESS, admin,
                new JsonObject().put("values", new JsonObject().put("nudge", on)).put("version", version));
    }

    private static JsonObject drawer(String key, String email) throws Exception
    {
        HttpResponse<Buffer> res = api.get(API + "/issues/" + key, RoleMatrix.session(api, email));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }
}
