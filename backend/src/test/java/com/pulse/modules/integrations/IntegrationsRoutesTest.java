package com.pulse.modules.integrations;

import com.pulse.calc.core.CalcService;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec admin/integrations-connectors, Phase 1 (task 5.4), through the real server with the demo seed. */
class IntegrationsRoutesTest
{
    private static final int PORT = 18_154;

    private static final String BASE = "/api/v1/integrations";

    private static TestApi api;

    private static TestApi.Session admin;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "integrations");

        admin = RoleMatrix.session(api, RoleMatrix.ADMIN);
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    private static JsonObject tile(String tool) throws Exception
    {
        for (Object o : api.get(BASE + "/tools", admin).bodyAsJsonObject().getJsonArray("tiles"))
        {
            JsonObject t = (JsonObject) o;

            if (t.getString("id").equals(tool))
            {
                return t;
            }
        }

        throw new AssertionError("no tile " + tool);
    }

    private static HttpResponse<Buffer> sync(String tool) throws Exception
    {
        return api.post(BASE + "/tools/" + tool + "/sync", admin, new JsonObject());
    }

    // ------------------------------------------------------------------ access

    @Test
    void onlyIntegrationManagersSeeIntegrations() throws Exception
    {
        Map<String, Integer> adminOnly = Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 403,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403);

        RoleMatrix.assertRoleMatrix(api, "GET " + BASE + "/tools", adminOnly);
        RoleMatrix.assertRoleMatrix(api, "GET " + BASE + "/tools/jira", adminOnly);
        RoleMatrix.assertRoleMatrix(api, "GET " + BASE + "/credential-log", adminOnly);
        RoleMatrix.assertRoleMatrix(api, "GET " + BASE + "/sync-log", adminOnly);
        RoleMatrix.assertRoleMatrix(api, "POST " + BASE + "/tools/gc/sync", new JsonObject(), adminOnly);
    }

    // ------------------------------------------------------------------ tiles

    @Test
    void tilesShowTheSeededStates() throws Exception
    {
        JsonObject jira = tile("jira");

        assertEquals("connected", jira.getString("state"));
        assertTrue(jira.getString("status").startsWith("Connected · synced "), jira.getString("status"));
        assertEquals("settings", jira.getString("action"));
        assertFalse(jira.getBoolean("live"));

        JsonObject slack = tile("sl");

        assertEquals("token_expiring", slack.getString("state"));
        assertEquals("Token expires in 3 days", slack.getString("status"));
        assertEquals("fix", slack.getString("action"));

        JsonObject pagerDuty = tile("pd");

        assertEquals("not_connected", pagerDuty.getString("state"));
        assertEquals("Not connected", pagerDuty.getString("status"));
        assertFalse(pagerDuty.getBoolean("connectEnabled"));
        assertTrue(pagerDuty.getString("note").startsWith("Planned"));

        JsonArray tiles = api.get(BASE + "/tools", admin).bodyAsJsonObject().getJsonArray("tiles");

        for (String id : List.of("jira", "tfs", "gh", "sl", "hr"))
        {
            assertTrue(tiles.stream().anyMatch(t -> ((JsonObject) t).getString("id").equals(id)), id);
        }
    }

    @Test
    void failedSyncTileAndFix() throws Exception
    {
        JsonObject hr = tile("hr");

        assertEquals("sync_failed", hr.getString("state"));
        assertEquals("Sync failed · 401 Unauthorized", hr.getString("status"));
        assertEquals("fix", hr.getString("action"));

        HttpResponse<Buffer> res = sync("hr");

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertEquals("success", res.bodyAsJsonObject().getJsonObject("sync").getString("outcome"));
        assertNotEquals("sync_failed", res.bodyAsJsonObject().getJsonObject("tile").getString("state"));
    }

    // ------------------------------------------------------------------ Sync now

    @Test
    void syncNowTwiceCreatesNoDuplicatesAndUpdatesSyncedAt() throws Exception
    {
        long sprints = api.count("SELECT count(*) FROM sprints");

        HttpResponse<Buffer> first = sync("jira");
        String syncedFirst = api.queryOne("SELECT max(synced_at)::text AS at FROM sprints").getString("at");

        HttpResponse<Buffer> second = sync("jira");
        String syncedSecond = api.queryOne("SELECT max(synced_at)::text AS at FROM sprints").getString("at");

        assertEquals(200, first.statusCode(), first.bodyAsString());
        assertEquals(200, second.statusCode(), second.bodyAsString());
        assertEquals(sprints, api.count("SELECT count(*) FROM sprints"));
        assertTrue(syncedSecond.compareTo(syncedFirst) > 0);

        JsonObject body = second.bodyAsJsonObject();
        JsonObject entry = body.getJsonObject("sync");

        assertEquals("success", entry.getString("outcome"));
        assertEquals("jira", entry.getString("tool"));
        assertTrue(entry.getBoolean("simulated"));
        assertTrue(entry.getString("message").startsWith("Simulated sync from seed: "), entry.getString("message"));
        assertEquals("Connected · synced just now", body.getJsonObject("tile").getString("status"));

        JsonArray log = api.get(BASE + "/sync-log?tool=jira", admin).bodyAsJsonObject().getJsonArray("items");

        assertEquals(entry.getString("id"), log.getJsonObject(0).getString("id"));
        assertEquals(sprints, log.getJsonObject(0).getJsonObject("counts").getJsonObject("entities")
                .getJsonObject("sprints").getLong("unchanged").longValue());
    }

    @Test
    void syncNowInvalidatesTheCalcCache() throws Exception
    {
        List<JsonObject> events = new CopyOnWriteArrayList<>();
        var consumer = api.vertx().eventBus().<JsonObject>consumer(CalcService.INVALIDATE_ADDRESS, m -> events.add(m.body()));

        assertEquals(200, sync("tfs").statusCode());

        long until = System.currentTimeMillis() + 5_000;

        while (events.isEmpty() && System.currentTimeMillis() < until)
        {
            TimeUnit.MILLISECONDS.sleep(20);
        }

        TestApi.await(consumer.unregister());

        assertFalse(events.isEmpty());
        assertTrue(events.get(0).getJsonArray("topics").contains(CalcService.ALL));
    }

    @Test
    void unknownToolAndPlannedTool() throws Exception
    {
        assertEquals(404, api.get(BASE + "/tools/nope", admin).statusCode());
        assertEquals(404, sync("nope").statusCode());
        assertEquals(409, sync("pd").statusCode(), "a tool that is not connected has nothing to sync");
        assertEquals(422, api.put(BASE + "/tools/pd/credentials", admin, new JsonObject()
                .put("method", "REST API key").put("values", new JsonObject())).statusCode());
    }

    // ------------------------------------------------------------------ credential forms

    @Test
    void credentialsStoreOnlyAMaskedPlaceholder() throws Exception
    {
        String token = "ATATT3xFfGF0-very-secret-token-Zx9Q";
        JsonObject values = new JsonObject()
                .put("site", "https://pulse-test.atlassian.net")
                .put("email", "pulse-bot@company.com")
                .put("token", token);

        HttpResponse<Buffer> res = api.put(BASE + "/tools/jira/credentials", admin,
                new JsonObject().put("method", "API token").put("values", values));

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertFalse(res.bodyAsString().contains(token), "the secret is never echoed");

        JsonObject stored = api.queryOne("SELECT credentials FROM integration_settings WHERE tool = 'jira'")
                .getJsonObject("credentials");

        assertEquals("••••Zx9Q", stored.getString("token"));
        assertEquals("https://pulse-test.atlassian.net", stored.getString("site"));
        assertEquals(0, api.count("SELECT count(*) FROM integration_settings WHERE credentials::text LIKE '%very-secret%'"));
        assertEquals(0, api.count("SELECT count(*) FROM audit_log WHERE summary::text LIKE '%very-secret%'"));
        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'credential.changed' AND target_id = 'jira'"));

        JsonObject activity = api.get(BASE + "/credential-log", admin).bodyAsJsonObject().getJsonArray("items")
                .stream().map(JsonObject.class::cast).filter(e -> e.getString("tool").equals("jira"))
                .findFirst().orElseThrow();

        assertEquals("jira", activity.getString("tool"));
        assertEquals("Credentials changed (API token)", activity.getString("message"));
        assertEquals("Anita Desai", activity.getString("by"));
    }

    @Test
    void credentialFormValidation() throws Exception
    {
        JsonObject good = new JsonObject().put("site", "https://x.atlassian.net").put("email", "a@b.co").put("token", "t");

        HttpResponse<Buffer> host = api.put(BASE + "/tools/jira/credentials", admin, new JsonObject()
                .put("method", "API token").put("values", good.copy().put("site", "https://evil.example.com")));

        assertEquals(400, host.statusCode());
        assertEquals("values.site", TestApi.error(host).getString("field"));

        assertEquals(400, api.put(BASE + "/tools/jira/credentials", admin, new JsonObject()
                .put("method", "API token").put("values", good.copy().put("site", "http://x.atlassian.net"))).statusCode());
        assertEquals(400, api.put(BASE + "/tools/jira/credentials", admin, new JsonObject()
                .put("method", "API token").put("values", good.copy().put("token", ""))).statusCode());
        assertEquals(400, api.put(BASE + "/tools/jira/credentials", admin, new JsonObject()
                .put("method", "API token").put("values", good.copy().put("email", "nope"))).statusCode());
        assertEquals(400, api.put(BASE + "/tools/jira/credentials", admin, new JsonObject()
                .put("method", "API token").put("values", good.copy().put("extra", "x"))).statusCode());
        assertEquals(400, api.put(BASE + "/tools/jira/credentials", admin, new JsonObject()
                .put("method", "Carrier pigeon").put("values", good)).statusCode());
    }

    @Test
    void disconnectThenSyncIsRefused() throws Exception
    {
        HttpResponse<Buffer> res = api.delete(BASE + "/tools/cf/credentials", admin);

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertEquals("not_connected", res.bodyAsJsonObject().getJsonObject("tile").getString("state"));
        assertTrue(res.bodyAsJsonObject().getJsonObject("tile").getBoolean("connectEnabled"));
        assertEquals(409, sync("cf").statusCode());
        assertEquals(409, api.delete(BASE + "/tools/cf/credentials", admin).statusCode());
    }

    // ------------------------------------------------------------------ settings

    @Test
    void settingsFormShowsScopesAndWarnsOnWriteScope() throws Exception
    {
        JsonObject jira = api.get(BASE + "/tools/jira", admin).bodyAsJsonObject();

        assertTrue(jira.getString("scopeWarning").startsWith("Write scope is not needed"));
        assertFalse(jira.getJsonArray("methods").isEmpty());
        assertTrue(jira.getJsonObject("credentials").getString("token").startsWith("••••"));

        JsonObject github = api.get(BASE + "/tools/gh", admin).bodyAsJsonObject();

        assertNull(github.getString("scopeWarning"));

        HttpResponse<Buffer> mode = api.put(BASE + "/tools/gh/settings", admin, new JsonObject().put("syncMode", "nightly"));

        assertEquals(200, mode.statusCode(), mode.bodyAsString());
        assertEquals("nightly", mode.bodyAsJsonObject().getString("syncMode"));
        assertEquals(400, api.put(BASE + "/tools/gh/settings", admin, new JsonObject().put("syncMode", "realtime")).statusCode());
    }

    @Test
    void jiraConfiguration() throws Exception
    {
        JsonObject body = new JsonObject()
                .put("boards", new JsonArray().add("PLAT").add("CORE"))
                .put("storyPointsField", "customfield_10028")
                .put("syncMode", "hourly")
                .put("ignoreBotEdits", false);

        HttpResponse<Buffer> res = api.put(BASE + "/jira/config", admin, body);

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject read = api.get(BASE + "/jira/config", admin).bodyAsJsonObject();

        assertEquals(new JsonArray().add("PLAT").add("CORE"), read.getJsonArray("boards"));
        assertEquals("customfield_10028", read.getString("storyPointsField"));
        assertEquals("hourly", read.getString("syncMode"));
        assertFalse(read.getBoolean("ignoreBotEdits"));
        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'integration.settings_changed' "
                + "AND target_id = 'jira'"));
    }
}
