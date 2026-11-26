package com.pulse.modules.integrations;

import com.pulse.calc.core.CalcService;
import com.pulse.platform.live.LoopbackFlag;
import com.pulse.platform.live.StubLiveConnector;
import com.pulse.platform.live.testkit.LiveMockServer;
import com.pulse.platform.secrets.SecretBox;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 24.6 (change pulse-v2-live-connectors): the generic Sync now orchestrator, through the real
 * server, with the stub connector (registered by the probe module) and the mock server. The stub
 * ingests into the test table {@code stub_items}; a seed row with a colliding external id stands for
 * the Phase 1 seed data a live sync must never touch.
 */
class LiveSyncRoutesTest
{
    private static final int PORT = 18_379;

    private static final String BASE = "/api/v1/integrations/" + StubLiveConnector.TOOL;

    private static final String SECRET = "stub-Sync-Token-Plain-5150";

    private static final String SEED_ROWS = "SELECT string_agg(external_id || ':' || coalesce(updated, '') || ':' "
            + "|| synced_at::text, ',' ORDER BY external_id) AS rows FROM stub_items WHERE source = 'seed'";

    private static TestApi api;

    private static TestApi.Session admin;

    private static LiveMockServer mock;

    private static MessageConsumer<JsonObject> invalidations;

    /** Topics of every calculation cache invalidation event published during the current test. */
    private static final List<JsonArray> published = new CopyOnWriteArrayList<>();

    private final List<String> bodies = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject()
                .put(SecretBox.CONFIG_KEY, SecretBox.generateKey())
                .put(LoopbackFlag.CONFIG_KEY, true), "integrations");

        admin = RoleMatrix.session(api, RoleMatrix.ADMIN);

        mock = TestApi.await(LiveMockServer.start(api.vertx(), StubLiveConnector.TOOL));

        api.exec("CREATE TABLE IF NOT EXISTS stub_items (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), "
                + "source text NOT NULL, external_id text NOT NULL, synced_at timestamptz NOT NULL DEFAULT now(), "
                + "updated text, assignee_account_id text, UNIQUE (source, external_id))");

        invalidations = api.vertx().eventBus().consumer(CalcService.INVALIDATE_ADDRESS,
                msg -> published.add(msg.body().getJsonArray("topics", new JsonArray())));
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (invalidations != null)
        {
            TestApi.await(invalidations.unregister());
        }

        if (mock != null)
        {
            mock.close();
        }

        if (api != null)
        {
            api.close();
        }
    }

    @BeforeEach
    void clean() throws Exception
    {
        mock.reset();
        published.clear();

        String tool = "'" + StubLiveConnector.TOOL + "'";

        api.exec("DELETE FROM integration_connections");
        api.exec("DELETE FROM sync_state");
        api.exec("DELETE FROM sync_log WHERE tool = " + tool);
        api.exec("DELETE FROM credential_log WHERE tool = " + tool);
        api.exec("DELETE FROM external_accounts WHERE tool = " + tool);
        api.exec("DELETE FROM stub_items");
        api.exec("INSERT INTO stub_items (source, external_id, updated, synced_at) VALUES "
                + "('seed', 'ST-1', 'seed-value', '2026-01-01T00:00:00Z'), "
                + "('seed', 'SEED-9', 'seed-only', '2026-01-01T00:00:00Z')");
    }

    @AfterEach
    void noSecretInAnyResponse() throws Exception
    {
        for (String body : bodies)
        {
            assertFalse(body.contains(SECRET), body);
        }

        assertEquals(0, api.count("SELECT count(*) FROM sync_log WHERE message LIKE '%" + SECRET + "%' "
                + "OR counts::text LIKE '%" + SECRET + "%'"));
        assertEquals(0, api.count("SELECT count(*) FROM credential_log WHERE message LIKE '%" + SECRET + "%'"));
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<Buffer> record(HttpResponse<Buffer> res)
    {
        bodies.add(res.bodyAsString() == null ? "" : res.bodyAsString());

        return res;
    }

    private void connect() throws Exception
    {
        JsonObject values = new JsonObject()
                .put("config", new JsonObject().put("baseUrl", mock.baseUrl()))
                .put("secret", SECRET);

        HttpResponse<Buffer> tested = record(api.post(BASE + "/test", admin, values));

        assertEquals(200, tested.statusCode(), tested.bodyAsString());

        HttpResponse<Buffer> saved = record(api.put(BASE + "/connection", admin,
                values.copy().put("proof", tested.bodyAsJsonObject().getString("proof"))));

        assertEquals(200, saved.statusCode(), saved.bodyAsString());

        mock.reset();
    }

    private HttpResponse<Buffer> sync() throws Exception
    {
        return record(api.post(BASE + "/sync", admin, new JsonObject()));
    }

    private JsonObject syncOk() throws Exception
    {
        HttpResponse<Buffer> res = sync();

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private long liveRows() throws Exception
    {
        return api.count("SELECT count(*) FROM stub_items WHERE source = 'stub'");
    }

    private String cursor() throws Exception
    {
        JsonObject row = api.queryOne("SELECT cursor FROM sync_state WHERE tool = '" + StubLiveConnector.TOOL + "'");

        return row == null ? null : row.getString("cursor");
    }

    private JsonObject tile() throws Exception
    {
        HttpResponse<Buffer> res = record(api.get("/api/v1/integrations/tools", admin));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        for (Object o : res.bodyAsJsonObject().getJsonArray("tiles"))
        {
            JsonObject tile = (JsonObject) o;

            if (StubLiveConnector.TOOL.equals(tile.getString("id")))
            {
                return tile;
            }
        }

        throw new AssertionError("no tile for the live stub connector");
    }

    // ------------------------------------------------------------------ access and preconditions

    @Test
    void adminOnly() throws Exception
    {
        connect();

        RoleMatrix.assertRoleMatrix(api, "POST " + BASE + "/sync", new JsonObject(), Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 403,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));
    }

    @Test
    void toolWithoutLiveConnectorIs404() throws Exception
    {
        assertEquals(404, record(api.post("/api/v1/integrations/sl/sync", admin, new JsonObject())).statusCode());
        assertEquals(404, record(api.post("/api/v1/integrations/nope/sync", admin, new JsonObject())).statusCode());
    }

    @Test
    void notConnectedIs409AndSendsNothing() throws Exception
    {
        HttpResponse<Buffer> res = sync();

        assertEquals(409, res.statusCode(), res.bodyAsString());
        assertEquals("not_connected", TestApi.error(res).getString("code"));
        assertTrue(mock.requests().isEmpty());
        assertEquals(0, api.count("SELECT count(*) FROM sync_log WHERE tool = 'stub'"));
    }

    // ------------------------------------------------------------------ success

    @Test
    void syncIngestsEveryPageLogsCountsAndAdvancesCursor() throws Exception
    {
        connect();

        JsonObject body = syncOk();

        JsonObject entry = body.getJsonObject("sync");

        assertEquals("success", entry.getString("outcome"));
        assertFalse(entry.getBoolean("simulated"));
        assertEquals(4, entry.getJsonObject("counts").getInteger("total"));
        assertEquals(4, entry.getJsonObject("counts").getJsonObject("entities").getJsonObject("stub_items")
                .getInteger("inserted"));

        assertEquals(4, liveRows());
        assertEquals("2026-11-21T11:15:00Z", cursor());

        // unmatched accounts are recorded through the person resolver
        assertTrue(api.count("SELECT count(*) FROM external_accounts WHERE tool = 'stub' AND person_id IS NULL") >= 2);

        // only GET requests to the configured origin, with the token
        for (JsonObject req : mock.requests())
        {
            assertEquals("GET", req.getString("method"));
        }

        assertEquals("connected", body.getJsonObject("tile").getString("state"));
        assertEquals(Boolean.FALSE, body.getJsonObject("tile").getBoolean("syncing"));
    }

    @Test
    void twoSyncsGiveNoDuplicatesAndTheSecondUsesTheCursor() throws Exception
    {
        connect();

        syncOk();

        mock.reset();

        JsonObject second = syncOk().getJsonObject("sync");

        assertEquals("success", second.getString("outcome"));
        assertEquals(4, liveRows());
        assertEquals(0, second.getJsonObject("counts").getJsonObject("entities").getJsonObject("stub_items")
                .getInteger("inserted"));

        assertEquals("2026-11-21T11:15:00Z", mock.requests().get(0).getJsonObject("query").getString("since"));
    }

    @Test
    void calculationCacheIsInvalidated() throws Exception
    {
        connect();

        syncOk();

        // the event bus delivers asynchronously; the sync answer is sent after the publish
        for (int i = 0; i < 50 && published.isEmpty(); i++)
        {
            TestApi.await(Future.<Void>future(p -> api.vertx().setTimer(20, t -> p.complete())));
        }

        assertTrue(published.stream().anyMatch(t -> t.contains(CalcService.ALL) && t.contains("stub_items")),
                String.valueOf(published));
    }

    // ------------------------------------------------------------------ failure

    @Test
    void serverErrorOnPage2KeepsPage1AndTheCursor() throws Exception
    {
        connect();

        mock.faultAt(2, LiveMockServer.status(500));

        JsonObject body = syncOk();

        JsonObject entry = body.getJsonObject("sync");

        assertEquals("failed", entry.getString("outcome"));
        assertEquals("500", entry.getString("message"));
        assertEquals(2, entry.getJsonObject("counts").getInteger("total"));

        // page 1 stays, the cursor does not move, no request after the failure
        assertEquals(2, liveRows());
        assertNull(cursor());
        assertEquals(2, mock.requests().size());

        JsonObject state = api.queryOne("SELECT status, last_error_code FROM sync_state WHERE tool = 'stub'");

        assertEquals("failed", state.getString("status"));
        assertEquals("500", state.getString("last_error_code"));

        // tile, Fix action and credential activity log
        assertEquals("sync_failed", body.getJsonObject("tile").getString("state"));
        assertEquals("Sync failed · 500", body.getJsonObject("tile").getString("status"));
        assertEquals("fix", body.getJsonObject("tile").getString("action"));
        assertEquals("sync_failed", tile().getString("state"));
        assertEquals(1, api.count("SELECT count(*) FROM credential_log WHERE tool = 'stub' AND action = 'sync_failed' "
                + "AND message = 'Sync failed · 500'"));

        // the pages that were ingested still invalidate the cache; a later success clears the failure
        mock.reset();

        JsonObject retry = syncOk();

        assertEquals("success", retry.getJsonObject("sync").getString("outcome"));
        assertEquals(4, liveRows());
        assertEquals("2026-11-21T11:15:00Z", cursor());
        assertEquals("connected", retry.getJsonObject("tile").getString("state"));
    }

    @Test
    void unauthorizedStopsTheRunWithItsCode() throws Exception
    {
        connect();

        mock.faultAt(1, LiveMockServer.status(401));

        JsonObject entry = syncOk().getJsonObject("sync");

        assertEquals("failed", entry.getString("outcome"));
        assertEquals("401", entry.getString("message"));
        assertEquals(0, liveRows());
        assertEquals("Sync failed · 401", tile().getString("status"));
    }

    @Test
    void unreadableSecretFailsWithKeyAndSendsNothing() throws Exception
    {
        connect();

        api.exec("UPDATE integration_connections SET secret_ciphertext = decode('00112233445566778899aabbccddeeff', 'hex') "
                + "WHERE tool = 'stub'");

        JsonObject entry = syncOk().getJsonObject("sync");

        assertEquals("failed", entry.getString("outcome"));
        assertEquals("key", entry.getString("message"));
        assertTrue(mock.requests().isEmpty());
        assertEquals("Sync failed · key", tile().getString("status"));
    }

    @Test
    void seedRowsAreIdenticalBeforeAndAfterAFailedAndASuccessfulSync() throws Exception
    {
        String before = api.queryOne(SEED_ROWS).getString("rows");

        connect();

        mock.faultAt(2, LiveMockServer.status(500));

        assertEquals("failed", syncOk().getJsonObject("sync").getString("outcome"));
        assertEquals(before, api.queryOne(SEED_ROWS).getString("rows"));

        mock.reset();

        assertEquals("success", syncOk().getJsonObject("sync").getString("outcome"));
        assertEquals(before, api.queryOne(SEED_ROWS).getString("rows"));
        assertEquals(2, api.count("SELECT count(*) FROM stub_items WHERE source = 'seed'"));
    }

    // ------------------------------------------------------------------ the lock

    @Test
    void concurrentCallGets409AndWritesNoLogEntry() throws Exception
    {
        connect();

        // a sync of the tool is running
        api.exec("INSERT INTO sync_state (tool, status, last_run_at) VALUES ('stub', 'running', now())");

        HttpResponse<Buffer> res = sync();

        assertEquals(409, res.statusCode(), res.bodyAsString());
        assertEquals("sync_running", TestApi.error(res).getString("code"));
        assertTrue(mock.requests().isEmpty());
        assertEquals(0, api.count("SELECT count(*) FROM sync_log WHERE tool = 'stub'"));
        assertEquals(Boolean.TRUE, tile().getBoolean("syncing"));
    }

    @Test
    void twoSimultaneousCallsRunOneSync() throws Exception
    {
        connect();

        // the first page stalls, so the first sync holds the lock while the second call arrives
        mock.faultAt(1, LiveMockServer.stall());

        Future<HttpResponse<Buffer>> first = api.vertx().executeBlocking(() -> sync());

        for (int i = 0; i < 100 && mock.requests().isEmpty(); i++)
        {
            TestApi.await(Future.<Void>future(p -> api.vertx().setTimer(20, t -> p.complete())));
        }

        HttpResponse<Buffer> second = sync();

        assertEquals(409, second.statusCode(), second.bodyAsString());

        // the stalled sync ends on the read timeout and releases the lock
        HttpResponse<Buffer> done = TestApi.await(first);

        assertEquals(200, done.statusCode(), done.bodyAsString());
        assertEquals("timeout", done.bodyAsJsonObject().getJsonObject("sync").getString("message"));
        assertEquals(1, api.count("SELECT count(*) FROM sync_log WHERE tool = 'stub'"));
        assertEquals("failed", api.queryOne("SELECT status FROM sync_state WHERE tool = 'stub'").getString("status"));
    }

    @Test
    void staleLockIsTakenOver() throws Exception
    {
        connect();

        api.exec("INSERT INTO sync_state (tool, status, last_run_at) VALUES ('stub', 'running', now() - interval '1 hour')");

        assertEquals("success", syncOk().getJsonObject("sync").getString("outcome"));
    }

    @Test
    void disconnectStopsSyncAndKeepsRows() throws Exception
    {
        connect();

        syncOk();

        assertEquals(200, record(api.delete(BASE + "/connection", admin)).statusCode());

        HttpResponse<Buffer> res = sync();

        assertEquals(409, res.statusCode(), res.bodyAsString());
        assertEquals(4, liveRows());
    }

    // ------------------------------------------------------------------ tile expiry (task 24.7)

    @Test
    void tileShowsTheLiveConnectionsExpiry() throws Exception
    {
        connect();

        api.exec("UPDATE integration_connections SET expires_at = now() + interval '5 days' WHERE tool = '"
                + StubLiveConnector.TOOL + "'");

        JsonObject tile = tile();

        assertEquals("token_expiring", tile.getString("state"), tile.encode());
        assertTrue(tile.getString("status").startsWith("Token expires in "), tile.encode());
        assertEquals("fix", tile.getString("action"));
        assertTrue(tile.getValue("expiresAt") != null, tile.encode());

        api.exec("UPDATE integration_connections SET expires_at = now() + interval '90 days' WHERE tool = '"
                + StubLiveConnector.TOOL + "'");

        assertEquals("connected", tile().getString("state"));

        api.exec("UPDATE integration_connections SET expires_at = NULL WHERE tool = '" + StubLiveConnector.TOOL + "'");

        JsonObject none = tile();

        assertEquals("connected", none.getString("state"));
        assertNull(none.getValue("expiresAt"));
    }

    @Test
    void toolDetailAnswersForALiveConnectorOutsideTheCatalogue() throws Exception
    {
        HttpResponse<Buffer> res = record(api.get("/api/v1/integrations/tools/" + StubLiveConnector.TOOL, admin));

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertEquals(StubLiveConnector.TOOL, res.bodyAsJsonObject().getJsonObject("tile").getString("id"));
    }
}
