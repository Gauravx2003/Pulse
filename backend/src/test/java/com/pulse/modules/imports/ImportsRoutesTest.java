package com.pulse.modules.imports;

import com.pulse.modules.imports.type.NotesImportType;
import com.pulse.platform.auth.SessionHandler;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The imports API through the real server, sign-in, CSRF, body limits and failure handler (task 16.2),
 * driven by the test-only {@link NotesImportType} (Admin and Manager may run it, Employees may not).
 */
class ImportsRoutesTest
{
    private static final int PORT = 18_316;

    private static TestApi api;

    private static WebClient client;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);

        // TestApi applies the platform migrations only; add this module's tables and the test table.
        try (InputStream v300 = ImportsRoutesTest.class.getClassLoader()
                .getResourceAsStream("modules/imports/migrations/V300__imports_base.sql"))
        {
            api.exec(new String(v300.readAllBytes(), StandardCharsets.UTF_8));
        }

        api.exec(NotesImportType.CREATE_TABLE);

        client = WebClient.create(api.vertx());
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    private static HttpResponse<Buffer> uploadCsv(TestApi.Session session, String query, byte[] csv, String contentType) throws Exception
    {
        return await(client.post(PORT, "localhost", "/api/v1/imports/test_notes/check" + query)
                .putHeader("X-Real-IP", TestApi.newIp())
                .putHeader("Cookie", session.cookie())
                .putHeader(SessionHandler.CSRF_HEADER, session.csrf())
                .putHeader("Content-Type", contentType)
                .sendBuffer(Buffer.buffer(csv)));
    }

    private static HttpResponse<Buffer> delete(TestApi.Session session, String path) throws Exception
    {
        return await(client.delete(PORT, "localhost", path)
                .putHeader("X-Real-IP", TestApi.newIp())
                .putHeader("Cookie", session.cookie())
                .putHeader(SessionHandler.CSRF_HEADER, session.csrf())
                .send());
    }

    @Test
    void onlyRolesHoldingTheTypePermissionMayUseIt() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/imports/test_notes/template", Map.of(
                RoleMatrix.ADMIN, 200,       // hr@: users.team
                RoleMatrix.MANAGER, 200,     // rahul@: users.team
                RoleMatrix.EMPLOYEE, 403,    // priya@
                RoleMatrix.PEER, 403));      // arjun@
    }

    @Test
    void typesListOnlyWhatTheCallerMayRun() throws Exception
    {
        HttpResponse<Buffer> admin = api.get("/api/v1/imports/types", RoleMatrix.session(api, RoleMatrix.ADMIN));

        assertEquals(200, admin.statusCode(), admin.bodyAsString());

        assertTrue(admin.bodyAsString().contains("\"test_notes\""));

        HttpResponse<Buffer> employee = api.get("/api/v1/imports/types", RoleMatrix.session(api, RoleMatrix.EMPLOYEE));

        String employeeTypes = employee.bodyAsString();

        // priya@ holds okr.own (OKR import) but neither users.team (test notes) nor the holiday permissions.
        assertTrue(employeeTypes.contains("\"okrs\""), employeeTypes);

        assertTrue(!employeeTypes.contains("\"test_notes\"") && !employeeTypes.contains("\"holidays\""), employeeTypes);

        assertEquals(401, api.get("/api/v1/imports/types", null).statusCode());
    }

    @Test
    void checkCommitHistoryExportAndUndoEndToEnd() throws Exception
    {
        TestApi.Session hr = RoleMatrix.session(api, RoleMatrix.ADMIN);

        byte[] csv = "title,due\nRunbook,2026-09-30\n=HYPERLINK(\"http://x\"),\nBad,31-02-2026\n".getBytes(StandardCharsets.UTF_8);

        HttpResponse<Buffer> checked = uploadCsv(hr, "?fileName=notes.csv", csv, "text/csv");

        assertEquals(200, checked.statusCode(), checked.bodyAsString());

        JsonObject result = checked.bodyAsJsonObject();

        assertEquals(2, result.getInteger("ready"));

        assertEquals(1, result.getInteger("errors"));

        assertEquals(0, api.count("SELECT count(*) FROM test_notes"));

        HttpResponse<Buffer> committed = api.post("/api/v1/imports/test_notes/commit", hr,
                new JsonObject().put("checkId", result.getString("checkId")));

        assertEquals(200, committed.statusCode(), committed.bodyAsString());

        String importId = committed.bodyAsJsonObject().getString("importId");

        assertEquals(2, api.count("SELECT count(*) FROM test_notes"));

        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'import.committed'"));

        HttpResponse<Buffer> history = api.get("/api/v1/imports/history", hr);

        assertEquals(importId, history.bodyAsJsonObject().getJsonArray("items").getJsonObject(0).getString("id"));

        HttpResponse<Buffer> exported = api.get("/api/v1/imports/test_notes/export", hr);

        assertEquals(200, exported.statusCode(), exported.bodyAsString());

        assertTrue(exported.getHeader("Content-Type").startsWith("text/csv"));

        assertTrue(exported.bodyAsString().contains("\"'=HYPERLINK(\"\"http://x\"\")\""), exported.bodyAsString());

        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'data.export'"));

        HttpResponse<Buffer> undone = api.post("/api/v1/imports/history/" + importId + "/undo", hr, new JsonObject());

        assertEquals(200, undone.statusCode(), undone.bodyAsString());

        assertEquals(0, api.count("SELECT count(*) FROM test_notes"));

        HttpResponse<Buffer> again = api.post("/api/v1/imports/history/" + importId + "/undo", hr, new JsonObject());

        assertEquals(409, again.statusCode());

        assertEquals("already_undone", TestApi.error(again).getString("code"));
    }

    @Test
    void anotherUsersCheckAndAnUnknownCheckGetTheSameAnswer() throws Exception
    {
        TestApi.Session hr = RoleMatrix.session(api, RoleMatrix.ADMIN);

        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        String checkId = uploadCsv(hr, "", "title\nMine\n".getBytes(StandardCharsets.UTF_8), "text/csv")
                .bodyAsJsonObject().getString("checkId");

        HttpResponse<Buffer> stolen = api.post("/api/v1/imports/test_notes/commit", rahul, new JsonObject().put("checkId", checkId));

        HttpResponse<Buffer> unknown = api.post("/api/v1/imports/test_notes/commit", rahul,
                new JsonObject().put("checkId", "00000000-0000-0000-0000-000000000000"));

        assertEquals(404, stolen.statusCode());

        RoleMatrix.assertSameRefusal(stolen, unknown);

        assertEquals(404, delete(rahul, "/api/v1/imports/checks/" + checkId).statusCode());

        assertEquals(204, delete(hr, "/api/v1/imports/checks/" + checkId).statusCode());
    }

    @Test
    void badUploadsAreRefusedWithTheErrorContract() throws Exception
    {
        TestApi.Session hr = RoleMatrix.session(api, RoleMatrix.ADMIN);

        HttpResponse<Buffer> json = uploadCsv(hr, "", "{}".getBytes(StandardCharsets.UTF_8), "application/json");

        assertEquals(400, json.statusCode());

        assertEquals("invalid_body", TestApi.error(json).getString("code"));

        HttpResponse<Buffer> missing = uploadCsv(hr, "", "due\n2026-09-01\n".getBytes(StandardCharsets.UTF_8), "text/csv");

        assertEquals(422, missing.statusCode());

        assertEquals("missing_columns", TestApi.error(missing).getString("code"));

        HttpResponse<Buffer> badMode = uploadCsv(hr, "?mode=explode", "title\nA\n".getBytes(StandardCharsets.UTF_8), "text/csv");

        assertEquals(400, badMode.statusCode());

        // Spec "Oversized file": refused while streaming, before it is fully buffered.
        byte[] sixMegabytes = new byte[6 * 1024 * 1024];

        Arrays.fill(sixMegabytes, (byte) 'a');

        HttpResponse<Buffer> oversized = uploadCsv(hr, "", sixMegabytes, "text/csv");

        assertEquals(413, oversized.statusCode());
    }
}
