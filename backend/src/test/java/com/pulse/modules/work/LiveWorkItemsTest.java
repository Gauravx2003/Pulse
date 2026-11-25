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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 25.2 (change pulse-v2-live-connectors): Work items and the issue drawer carry each issue's
 * {@code source} and {@code htmlUrl} as new fields, the lists take an allow-listed {@code source} filter
 * (all, seed, jira; anything else is 400) with the issues per source in {@code sources}, and the KPIs
 * still cover every issue. A live Jira issue linked to Priya is in the live sprint and in a closed month.
 */
class LiveWorkItemsTest
{

    private static final int PORT = 18_294;

    private static final String API = "/api/v1/work";

    private static final String LIVE = "JIRA-501";

    private static final String JUNE = "JIRA-502";

    private static TestApi api;

    private static TestApi.Session admin;

    private static String priya;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort",
                "progress", "integrations");

        admin = RoleMatrix.session(api, RoleMatrix.ADMIN);

        priya = api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");

        String sprint43 = api.queryOne("SELECT id::text AS id FROM sprints WHERE number = 43").getString("id");

        // as the Jira connector writes them, before any read, so no cached result predates them
        api.exec("INSERT INTO external_accounts (tool, tool_account_id, login, person_id) VALUES ('jira', 'priya.shah',"
                + " 'priya.shah', '" + priya + "')");

        api.exec("INSERT INTO issues (key, summary, status, sprint_id, story_points, estimate_hours, remaining_hours,"
                + " source, external_id, synced_at, html_url, assignee_account_id) VALUES ('" + LIVE + "', 'Live trap"
                + " collector', 'In Progress', '" + sprint43 + "', 3, 10, 6, 'jira', '" + LIVE + "', now(),"
                + " 'http://jira.test/browse/" + LIVE + "', 'priya.shah')");

        api.exec("INSERT INTO issues (key, summary, status, story_points, spent_hours, started_on, resolved_on, source,"
                + " external_id, synced_at, html_url, assignee_account_id) VALUES ('" + JUNE + "', 'Live poller fix', 'Done',"
                + " 2, 5, '2026-06-08', '2026-06-15', 'jira', '" + JUNE + "', now(), 'http://jira.test/browse/" + JUNE + "',"
                + " 'priya.shah')");
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
    void theLiveSprintListCarriesSourceAndLinkAndFiltersBySource() throws Exception
    {
        JsonObject all = items(null);

        JsonObject live = item(all, LIVE);

        assertEquals("jira", live.getString("source"));
        assertEquals("http://jira.test/browse/" + LIVE, live.getString("htmlUrl"));

        JsonObject seed = all.getJsonArray("items").stream().map(JsonObject.class::cast)
                .filter(i -> !LIVE.equals(i.getString("key"))).findFirst().orElseThrow();

        assertEquals("seed", seed.getString("source"));

        JsonObject sources = all.getJsonObject("sources");

        assertEquals(1, sources.getInteger("jira"));
        assertEquals(sources.getInteger("all") - 1, sources.getInteger("seed"));
        assertEquals("all", all.getString("source"));

        JsonObject jira = items("jira");

        assertEquals(List.of(LIVE), keys(jira));
        assertEquals("jira", jira.getString("source"));
        assertEquals(1, jira.getJsonObject("chips").getInteger("all"), "the chips count within the source");
        assertEquals(sources, jira.getJsonObject("sources"), "the source counts cover every issue");
        assertEquals(all.getJsonObject("kpis"), jira.getJsonObject("kpis"), "the KPIs cover every issue");

        JsonObject seeded = items("seed");

        assertFalse(keys(seeded).contains(LIVE));
        assertEquals(sources.getInteger("seed"), seeded.getJsonArray("items").size());
    }

    @Test
    void aClosedMonthFiltersBySourceToo() throws Exception
    {
        JsonObject june = get(API + "/people/" + priya + "/work-items?period=m:2026-06&source=jira");

        assertFalse(june.getBoolean("live"));
        assertEquals(List.of(JUNE), keys(june));
        assertEquals("http://jira.test/browse/" + JUNE, item(june, JUNE).getString("htmlUrl"));
        assertEquals(1, june.getJsonObject("sources").getInteger("jira"));
    }

    @Test
    void anUnknownSourceIsRefused() throws Exception
    {
        HttpResponse<Buffer> res = api.get(API + "/people/" + priya + "/work-items?source=github", admin);

        assertEquals(400, res.statusCode(), res.bodyAsString());
        assertEquals("source", TestApi.error(res).getString("field"), res.bodyAsString());
    }

    @Test
    void theDrawerCarriesSourceAndLink() throws Exception
    {
        JsonObject live = get(API + "/issues/" + LIVE).getJsonObject("issue");

        assertEquals("jira", live.getString("source"));
        assertEquals("http://jira.test/browse/" + LIVE, live.getString("htmlUrl"));
        assertEquals(priya, live.getString("assigneeId"), "the drawer's assignee is the linked person");

        JsonObject seed = get(API + "/issues/PLAT-2217").getJsonObject("issue");

        assertEquals("seed", seed.getString("source"));
        assertNull(seed.getValue("htmlUrl"));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject items(String source) throws Exception
    {
        return get(API + "/people/" + priya + "/work-items" + (source == null ? "" : "?source=" + source));
    }

    private static JsonObject get(String path) throws Exception
    {
        HttpResponse<Buffer> res = api.get(path, admin);

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static JsonObject item(JsonObject body, String key)
    {
        return body.getJsonArray("items").stream().map(JsonObject.class::cast)
                .filter(i -> key.equals(i.getString("key"))).findFirst()
                .orElseThrow(() -> new AssertionError(key + " not in " + keys(body)));
    }

    private static List<String> keys(JsonObject body)
    {
        JsonArray items = body.getJsonArray("items");

        assertTrue(items != null, body.encode());

        return items.stream().map(i -> ((JsonObject) i).getString("key")).toList();
    }
}
