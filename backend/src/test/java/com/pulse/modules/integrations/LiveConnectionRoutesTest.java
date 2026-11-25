package com.pulse.modules.integrations;

import com.pulse.platform.live.LoopbackFlag;
import com.pulse.platform.live.StubLiveConnector;
import com.pulse.platform.live.testkit.LiveMockServer;
import com.pulse.platform.secrets.SecretBox;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 24.5 (change pulse-v2-live-connectors): generic Test connection, Save and Disconnect for a
 * registered live connector, through the real server, with the stub connector (registered by the probe
 * module) and the mock server.
 */
class LiveConnectionRoutesTest
{
    private static final int PORT = 18_378;

    private static final String BASE = "/api/v1/integrations/" + StubLiveConnector.TOOL;

    private static final String SECRET = "stub-T0ken-Plain-Value-4242";

    private static final String OTHER_SECRET = "stub-T0ken-Other-Value-7777";

    private static TestApi api;

    private static TestApi.Session admin;

    private static LiveMockServer mock;

    /** Every response body of the current test, checked for the secret afterwards. */
    private final List<String> bodies = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject()
                .put(SecretBox.CONFIG_KEY, SecretBox.generateKey())
                .put(LoopbackFlag.CONFIG_KEY, true), "integrations");

        admin = RoleMatrix.session(api, RoleMatrix.ADMIN);

        mock = TestApi.await(LiveMockServer.start(api.vertx(), StubLiveConnector.TOOL));
    }

    @AfterAll
    static void stop() throws Exception
    {
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

        api.exec("DELETE FROM integration_connections");
        api.exec("DELETE FROM credential_log WHERE tool = '" + StubLiveConnector.TOOL + "'");
        api.exec("DELETE FROM audit_log WHERE target_id = '" + StubLiveConnector.TOOL + "'");
    }

    @AfterEach
    void noSecretInAnyResponse()
    {
        for (String body : bodies)
        {
            assertFalse(body.contains(SECRET), body);
            assertFalse(body.contains(OTHER_SECRET), body);
        }
    }

    // ------------------------------------------------------------------ helpers

    private JsonObject values(String secret)
    {
        return new JsonObject()
                .put("config", new JsonObject().put("baseUrl", mock.baseUrl() + "/"))
                .put("secret", secret);
    }

    private HttpResponse<Buffer> record(HttpResponse<Buffer> res)
    {
        bodies.add(res.bodyAsString() == null ? "" : res.bodyAsString());

        return res;
    }

    private HttpResponse<Buffer> test(JsonObject body) throws Exception
    {
        return record(api.post(BASE + "/test", admin, body));
    }

    private HttpResponse<Buffer> save(JsonObject body) throws Exception
    {
        return record(api.put(BASE + "/connection", admin, body));
    }

    private JsonObject stored() throws Exception
    {
        HttpResponse<Buffer> res = record(api.get(BASE + "/connection", admin));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject().getJsonObject("connection");
    }

    /** Tests, then saves with the proof; returns the saved connection. */
    private JsonObject connect(String secret) throws Exception
    {
        HttpResponse<Buffer> tested = test(values(secret));

        assertEquals(200, tested.statusCode(), tested.bodyAsString());

        HttpResponse<Buffer> saved = save(values(secret).put("proof", tested.bodyAsJsonObject().getString("proof")));

        assertEquals(200, saved.statusCode(), saved.bodyAsString());

        return saved.bodyAsJsonObject().getJsonObject("connection");
    }

    // ------------------------------------------------------------------ access

    @Test
    void adminOnly() throws Exception
    {
        Map<String, Integer> adminOnly = Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 403,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403);

        RoleMatrix.assertRoleMatrix(api, "GET " + BASE + "/connection", adminOnly);
    }

    @Test
    void toolWithoutLiveConnectorIs404() throws Exception
    {
        // Slack is a seeded tool: it keeps the Phase 1 /tools/sl routes
        assertEquals(404, record(api.get("/api/v1/integrations/sl/connection", admin)).statusCode());
        assertEquals(404, record(api.post("/api/v1/integrations/sl/test", admin, values(SECRET))).statusCode());
        assertEquals(404, record(api.delete("/api/v1/integrations/nope/connection", admin)).statusCode());
        assertTrue(mock.requests().isEmpty());
    }

    // ------------------------------------------------------------------ test connection

    @Test
    void passOn200GivesAccountWarningsAndProof() throws Exception
    {
        HttpResponse<Buffer> res = test(values(SECRET));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject body = res.bodyAsJsonObject();

        assertTrue(body.getBoolean("ok"));
        assertEquals(200, body.getInteger("status"));
        assertEquals("demo.reader", body.getValue("account"));
        assertNotNull(body.getString("proof"));
        assertNotNull(body.getLong("proofExpiresAt"));
        assertEquals("insecure_http", body.getJsonArray("warnings").getJsonObject(0).getString("code"));

        for (String key : List.of("scopes", "scopeNote", "fields", "expiresAt", "hint"))
        {
            assertTrue(body.containsKey(key), key);
        }

        // nothing is stored by a test, and only GET went out, with the bearer header
        assertNull(stored());
        assertEquals(1, mock.requests().size());
        assertEquals("GET", mock.requests().get(0).getString("method"));
    }

    @Test
    void failOn401WithStatusAndHintAndNoProof() throws Exception
    {
        JsonObject before = connect(SECRET);

        mock.faultOn("/myself", LiveMockServer.status(401));

        HttpResponse<Buffer> res = test(values(OTHER_SECRET));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject body = res.bodyAsJsonObject();

        assertFalse(body.getBoolean("ok"));
        assertEquals(401, body.getInteger("status"));
        assertEquals("Check the token.", body.getString("hint"));
        assertNull(body.getString("proof"));

        // without a proof, Save is refused and the stored values stay
        HttpResponse<Buffer> saved = save(values(OTHER_SECRET).put("proof", "none"));

        assertEquals(422, saved.statusCode());
        assertEquals("test_required", TestApi.error(saved).getString("code"));

        JsonObject after = stored();

        assertEquals(before.getInteger("version"), after.getInteger("version"));
        assertEquals(before.getString("last4"), after.getString("last4"));
    }

    @Test
    void redirectIsAFailedTestNotFollowed() throws Exception
    {
        mock.faultOn("/myself", LiveMockServer.redirect("http://10.0.0.5/"));

        JsonObject body = test(values(SECRET)).bodyAsJsonObject();

        assertFalse(body.getBoolean("ok"));
        assertEquals("redirect", body.getString("code"));
        assertNull(body.getString("proof"));
        assertEquals(1, mock.requests().size());
    }

    @Test
    void invalidValuesAre400BeforeAnyRequest() throws Exception
    {
        HttpResponse<Buffer> unknown = test(values(SECRET).put("config", new JsonObject()
                .put("baseUrl", mock.baseUrl()).put("extra", "x")));

        assertEquals(400, unknown.statusCode());
        assertEquals("config.extra", TestApi.error(unknown).getString("field"));

        HttpResponse<Buffer> metadata = test(values(SECRET).put("config", new JsonObject()
                .put("baseUrl", "http://169.254.169.254")));

        assertEquals(400, metadata.statusCode());
        assertEquals("config.baseUrl", TestApi.error(metadata).getString("field"));

        HttpResponse<Buffer> query = test(values(SECRET).put("config", new JsonObject()
                .put("baseUrl", mock.baseUrl() + "?a=b")));

        assertEquals(400, query.statusCode());

        HttpResponse<Buffer> blank = test(values(" "));

        assertEquals(400, blank.statusCode());
        assertEquals("secret", TestApi.error(blank).getString("field"));

        assertTrue(mock.requests().isEmpty());
    }

    // ------------------------------------------------------------------ save

    @Test
    void saveWithProofStoresOnlyLast4AndLogs() throws Exception
    {
        JsonObject connection = connect(SECRET);

        assertEquals("connected", connection.getString("state"));
        assertTrue(connection.getBoolean("hasSecret"));
        assertEquals("4242", connection.getString("last4"));
        assertEquals(mock.baseUrl(), connection.getJsonObject("config").getString("baseUrl"));
        assertFalse(connection.containsKey("ciphertext"));

        assertEquals(1, api.count("SELECT count(*) FROM credential_log WHERE tool = 'stub' AND action = 'added'"));
        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'credential.changed' "
                + "AND target_id = 'stub' AND summary->>'change' = 'added'"));
        assertEquals(0, api.count("SELECT count(*) FROM audit_log WHERE summary::text LIKE '%" + SECRET + "%'"));

        connect(OTHER_SECRET);

        assertEquals(1, api.count("SELECT count(*) FROM credential_log WHERE tool = 'stub' AND action = 'changed'"));
        assertEquals("7777", stored().getString("last4"));
    }

    @Test
    void proofIsSingleUse() throws Exception
    {
        String proof = test(values(SECRET)).bodyAsJsonObject().getString("proof");

        assertEquals(200, save(values(SECRET).put("proof", proof)).statusCode());

        HttpResponse<Buffer> again = save(values(SECRET).put("proof", proof));

        assertEquals(422, again.statusCode());
        assertEquals("test_required", TestApi.error(again).getString("code"));
    }

    @Test
    void valuesChangedAfterTheTestCannotBeSaved() throws Exception
    {
        JsonObject before = connect(SECRET);

        String proof = test(values(SECRET)).bodyAsJsonObject().getString("proof");

        HttpResponse<Buffer> changedSecret = save(values(OTHER_SECRET).put("proof", proof));

        assertEquals(422, changedSecret.statusCode());
        assertEquals("test_required", TestApi.error(changedSecret).getString("code"));

        String proof2 = test(values(SECRET)).bodyAsJsonObject().getString("proof");

        HttpResponse<Buffer> changedExpiry = save(values(SECRET).put("expiresAt", "2027-01-31").put("proof", proof2));

        assertEquals(422, changedExpiry.statusCode());

        JsonObject after = stored();

        assertEquals(before.getInteger("version"), after.getInteger("version"));
        assertEquals("4242", after.getString("last4"));
    }

    @Test
    void trailingSlashDoesNotBreakTheProof() throws Exception
    {
        String proof = test(values(SECRET)).bodyAsJsonObject().getString("proof");

        JsonObject same = values(SECRET).put("config", new JsonObject().put("baseUrl", mock.baseUrl()));

        assertEquals(200, save(same.put("proof", proof)).statusCode());
    }

    // ------------------------------------------------------------------ disconnect

    @Test
    void disconnectDeletesTheSecretKeepsConfigAndLogs() throws Exception
    {
        connect(SECRET);

        HttpResponse<Buffer> res = record(api.delete(BASE + "/connection", admin));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject connection = res.bodyAsJsonObject().getJsonObject("connection");

        assertEquals("disconnected", connection.getString("state"));
        assertFalse(connection.getBoolean("hasSecret"));
        assertNull(connection.getString("last4"));
        assertEquals(mock.baseUrl(), connection.getJsonObject("config").getString("baseUrl"));

        assertEquals(0, api.count("SELECT count(*) FROM integration_connections WHERE secret_ciphertext IS NOT NULL"));
        assertEquals(1, api.count("SELECT count(*) FROM credential_log WHERE tool = 'stub' AND action = 'disconnected'"));
        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'credential.changed' "
                + "AND target_id = 'stub' AND summary->>'change' = 'disconnected'"));

        HttpResponse<Buffer> again = record(api.delete(BASE + "/connection", admin));

        assertEquals(409, again.statusCode());
    }
}
