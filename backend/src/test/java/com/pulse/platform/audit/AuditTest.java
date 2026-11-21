package com.pulse.platform.audit;

import com.pulse.platform.http.RequestId;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static com.pulse.testsupport.RoleMatrix.ADMIN;
import static com.pulse.testsupport.RoleMatrix.EMPLOYEE;
import static com.pulse.testsupport.RoleMatrix.MANAGER;
import static com.pulse.testsupport.RoleMatrix.PEER;
import static com.pulse.testsupport.RoleMatrix.session;
import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3.7: the audit service writes in the caller's transaction (rolled back with it), knows only
 * catalogue events, keeps secrets and free text out, stamps request id and role, and its read endpoint
 * is restricted to audit.read and audits every read.
 */
class AuditTest
{
    private static final int PORT = 18_197;

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    // ------------------------------------------------------------------ service

    @Test
    void entryRollsBackOnlyWithItsAction() throws Exception
    {
        String failed = UUID.randomUUID().toString();
        String committed = UUID.randomUUID().toString();

        assertThrows(Exception.class, () -> await(api.sql().inTransaction(tx ->
                AuditService.record(tx, entry(AuditEvents.RULES_CHANGED, failed))
                        .compose(v -> Future.failedFuture(new IllegalStateException("action failed"))))));

        assertEquals(0L, api.count("SELECT count(*) FROM audit_log WHERE target_id = '" + failed + "'"),
                "the failed action's entry rolled back with it");

        await(api.sql().inTransaction(tx -> AuditService.record(tx, entry(AuditEvents.RULES_CHANGED, committed))));

        assertEquals(1L, api.count("SELECT count(*) FROM audit_log WHERE target_id = '" + committed + "'"));
    }

    @Test
    void onlyCatalogueEventsAreAccepted() throws Exception
    {
        assertThrows(Exception.class, () -> await(AuditService.record(api.sql(), entry("rules.tweaked", "x"))));
        assertThrows(Exception.class, () -> await(AuditService.record(api.sql(),
                entry(AuditEvents.RULES_CHANGED, "x").put("outcome", "maybe"))));
    }

    @Test
    void entriesHoldNoSecretOrFreeText() throws Exception
    {
        String target = UUID.randomUUID().toString();

        await(AuditService.record(api.sql(), entry(AuditEvents.CREDENTIAL_CHANGED, target).put("summary", new JsonObject()
                .put("tool", "github")
                .put("fieldsChanged", new JsonArray().add("token").add("baseUrl"))
                .put("token", "ghp_s3cr3tvalue")
                .put("after", new JsonObject().put("apiKey", "k-999").put("comment", "Rahul was rude in the 1:1"))
                .put("note", "x".repeat(500)))));

        JsonObject row = api.queryOne("SELECT summary FROM audit_log WHERE target_id = '" + target + "'");
        String stored = row.getValue("summary").toString();

        for (String leaked : new String[] {"ghp_s3cr3tvalue", "k-999", "rude", "xxxxx"})
        {
            assertFalse(stored.contains(leaked), leaked + " stored: " + stored);
        }

        assertTrue(stored.contains("github") && stored.contains("baseUrl"), "tool and changed field names kept: " + stored);
    }

    @Test
    void entriesCarryRequestIdIpAndRole() throws Exception
    {
        HttpResponse<Buffer> res = api.post("/api/v1/auth/login", null,
                new JsonObject().put("email", "neha@pulse.demo").put("password", TestApi.DEMO_PASSWORD));

        assertEquals(200, res.statusCode());

        String requestId = res.headers().get(RequestId.HEADER);

        JsonObject row = api.queryOne("SELECT actor_role, source_ip, outcome, occurred_at FROM audit_log "
                + "WHERE action = 'auth.sign_in' AND request_id = '" + requestId + "'");

        assertEquals("Employee", row.getString("actor_role"));
        assertEquals("success", row.getString("outcome"));
        assertTrue(row.getString("source_ip") != null && !row.getString("source_ip").isBlank());
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void refusedPermissionAndVisibilityChecksAreAudited() throws Exception
    {
        HttpResponse<Buffer> refused = api.get("/api/v1/audit", session(api, EMPLOYEE));

        assertEquals(403, refused.statusCode());
        assertEquals(1L, api.count("SELECT count(*) FROM audit_log WHERE action = 'permission.refused' "
                + "AND outcome = 'refused' AND request_id = '" + TestApi.error(refused).getString("requestId") + "'"));

        String priya = api.queryOne("SELECT p.id::text AS id FROM people p JOIN users u ON u.id = p.user_id "
                + "WHERE u.email = 'priya@pulse.demo'").getString("id");

        HttpResponse<Buffer> peer = api.get("/api/v1/probe/people/" + priya + "/overview", session(api, PEER));

        assertEquals(403, peer.statusCode());
        assertEquals(1L, api.count("SELECT count(*) FROM audit_log WHERE action = 'access.refused' AND target_id = '"
                + priya + "' AND request_id = '" + TestApi.error(peer).getString("requestId") + "'"));
    }

    // ------------------------------------------------------------------ read endpoint

    @Test
    void readerNeedsAuditReadPermission() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/audit", Map.of(
                ADMIN, 200,
                MANAGER, 403,
                EMPLOYEE, 403,
                PEER, 403));
    }

    @Test
    void readsAreFilteredAndThemselvesAudited() throws Exception
    {
        TestApi.Session admin = session(api, ADMIN);

        HttpResponse<Buffer> res = api.get("/api/v1/audit?action=auth.sign_in&pageSize=5", admin);

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject page = res.bodyAsJsonObject();

        assertTrue(page.getLong("total") >= 1);
        page.getJsonArray("items").forEach(i -> assertEquals("auth.sign_in", ((JsonObject) i).getString("action")));

        JsonObject read = api.queryOne("SELECT summary FROM audit_log WHERE action = 'audit.read' AND request_id = '"
                + res.headers().get(RequestId.HEADER) + "'");

        assertEquals("auth.sign_in", new JsonObject(read.getValue("summary").toString())
                .getJsonObject("filters").getString("action"));

        String tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();

        assertEquals(0L, api.get("/api/v1/audit?from=" + tomorrow, admin).bodyAsJsonObject().getLong("total"));

        HttpResponse<Buffer> badDate = api.get("/api/v1/audit?from=yesterday", admin);

        assertEquals(400, badDate.statusCode());
        assertEquals("from", TestApi.error(badDate).getString("field"));

        HttpResponse<Buffer> unknown = api.get("/api/v1/audit?password=x", admin);

        assertEquals(400, unknown.statusCode());
        assertEquals("password", TestApi.error(unknown).getString("field"));
    }

    @Test
    void exportIsCsvAndAudited() throws Exception
    {
        HttpResponse<Buffer> res = api.get("/api/v1/audit/export?outcome=success", session(api, ADMIN));

        assertEquals(200, res.statusCode());
        assertTrue(res.headers().get("Content-Type").startsWith("text/csv"));
        assertEquals("false", res.headers().get("X-Export-Truncated"));
        assertTrue(res.bodyAsString().startsWith("occurredAt,actorId,actorName,actorRole,action,"));
        assertEquals(1L, api.count("SELECT count(*) FROM audit_log WHERE action = 'audit.export' AND request_id = '"
                + res.headers().get(RequestId.HEADER) + "'"));
    }

    @Test
    void deletedActorShowsOnlyAnOpaqueId() throws Exception
    {
        String gone = UUID.randomUUID().toString();

        api.exec("INSERT INTO audit_log (actor_id, actor_role, action, outcome) VALUES ('" + gone
                + "', 'Employee', 'auth.sign_in', 'success')");

        JsonObject item = api.get("/api/v1/audit?actor=" + gone, session(api, ADMIN)).bodyAsJsonObject()
                .getJsonArray("items").getJsonObject(0);

        assertEquals(gone, item.getString("actorId"));
        assertNull(item.getString("actorName"));
    }

    private static JsonObject entry(String action, String target)
    {
        return new JsonObject()
                .put("action", action)
                .put("targetType", "test")
                .put("targetId", target)
                .put("outcome", "success")
                .put("summary", new JsonObject());
    }
}
