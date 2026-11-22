package com.pulse.platform.http;

import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3.8, spec platform/api-conventions: one contract test per error status of the error contract
 * (400, 401, 403, 404, 409, 422, 429), each with a stable code, a message and a request id; a stale
 * version answers 409 with the current version and does not overwrite; ids are UUIDs and a guessed or
 * malformed id addresses nothing; page size is capped and reported.
 */
class ApiConventionsTest
{
    private static final int PORT = 18_198;

    private static final int USER_LIMIT = 30;

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("http.userPerMinute", USER_LIMIT));

        api.exec("CREATE TABLE probe_notes (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), text text NOT NULL, "
                + "updated_at timestamptz NOT NULL DEFAULT now(), version integer NOT NULL DEFAULT 1)");
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
    void badRequest400NamesTheField() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        JsonObject body = contract(api.post("/api/v1/probe/echo", priya, new JsonObject().put("title", 5)), 400, "invalid_field");

        assertEquals("title", body.getString("field"));

        JsonObject filter = contract(api.get("/api/v1/probe/people?level=L3", priya), 400, "invalid_parameter");

        assertEquals("level", filter.getString("field"));
    }

    @Test
    void unauthenticated401() throws Exception
    {
        contract(api.get("/api/v1/me", null), 401, "unauthenticated");
    }

    @Test
    void forbidden403() throws Exception
    {
        contract(api.get("/api/v1/probe/team", RoleMatrix.session(api, RoleMatrix.EMPLOYEE)), 403, "no_access");
    }

    @Test
    void notFound404ForMissingGuessedOrMalformedIdsAndUnknownRoutes() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        String id = note("first");

        // a sequential-looking or malformed id addresses nothing
        contract(api.get("/api/v1/probe/notes/" + UUID.randomUUID(), priya), 404, "not_found");
        contract(api.get("/api/v1/probe/notes/1", priya), 404, "not_found");
        // change the last character, whatever it is, so the id is guaranteed to differ from the real one
        String guessed = id.substring(0, 35) + (id.endsWith("0") ? "1" : "0");
        contract(api.get("/api/v1/probe/notes/" + guessed, priya), 404, "not_found");

        contract(api.get("/api/v1/no-such-thing", priya), 404, "not_found");

        HttpResponse<Buffer> found = api.get("/api/v1/probe/notes/" + id, priya);

        assertEquals(200, found.statusCode());
        assertEquals(id, found.bodyAsJsonObject().getString("id"));
    }

    @Test
    void staleVersion409KeepsTheOtherSave() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        String id = note("draft");

        HttpResponse<Buffer> first = api.put("/api/v1/probe/notes/" + id, priya,
                new JsonObject().put("text", "first editor").put("version", 1));

        assertEquals(200, first.statusCode(), first.bodyAsString());
        assertEquals(2, first.bodyAsJsonObject().getInteger("version"));

        JsonObject conflict = contract(api.put("/api/v1/probe/notes/" + id, priya,
                new JsonObject().put("text", "second editor").put("version", 1)), 409, "version_conflict");

        assertEquals(2, conflict.getInteger("currentVersion"));
        assertEquals("first editor", api.queryOne("SELECT text FROM probe_notes WHERE id = '" + id + "'").getString("text"),
                "the stale write did not overwrite");

        JsonObject missingVersion = contract(api.put("/api/v1/probe/notes/" + id, priya,
                new JsonObject().put("text", "no version")), 400, "invalid_field");

        assertEquals("version", missingVersion.getString("field"));

        contract(api.put("/api/v1/probe/notes/" + UUID.randomUUID(), priya,
                new JsonObject().put("text", "x").put("version", 1)), 404, "not_found");
    }

    @Test
    void businessRule422NamesTheRule() throws Exception
    {
        api.exec("INSERT INTO people (user_id, name, source, external_id) "
                + "SELECT id, name, 'test', 'hr-422' FROM users WHERE email = 'hr@pulse.demo' "
                + "AND NOT EXISTS (SELECT 1 FROM people p WHERE p.user_id = users.id)");

        String own = api.queryOne("SELECT p.id::text AS id FROM people p JOIN users u ON u.id = p.user_id "
                + "WHERE u.email = 'hr@pulse.demo'").getString("id");

        contract(api.post("/api/v1/probe/increments/" + own + "/approve", RoleMatrix.session(api, RoleMatrix.ADMIN),
                new JsonObject()), 422, "self_action_refused");
    }

    @Test
    void rateLimit429WithRetryAfter() throws Exception
    {
        TestApi.Session kiran = api.signIn("kiran@pulse.demo", TestApi.DEMO_PASSWORD);

        for (int i = 0; i <= 2 * USER_LIMIT; i++)
        {
            HttpResponse<Buffer> res = api.get("/api/v1/me", kiran);

            if (res.statusCode() == 429)
            {
                JsonObject error = contract(res, 429, "rate_limited");

                assertNotNull(res.headers().get("Retry-After"));
                assertEquals(Integer.parseInt(res.headers().get("Retry-After")), error.getInteger("retryAfter"));

                return;
            }
        }

        throw new AssertionError("never rate limited");
    }

    @Test
    void pageSizeAboveMaximumIsCappedAndReported() throws Exception
    {
        JsonObject page = api.get("/api/v1/probe/people?pageSize=10000", RoleMatrix.session(api, RoleMatrix.ADMIN))
                .bodyAsJsonObject();

        assertEquals(200, page.getInteger("pageSize"));
        assertTrue(page.getBoolean("pageSizeCapped"));
    }

    /** Asserts the error contract and returns the error object. */
    private static JsonObject contract(HttpResponse<Buffer> res, int status, String code)
    {
        assertEquals(status, res.statusCode(), res.bodyAsString());
        assertTrue(res.headers().get("Content-Type").startsWith("application/json"), res.headers().get("Content-Type"));

        JsonObject error = res.bodyAsJsonObject().getJsonObject("error");

        assertNotNull(error, res.bodyAsString());
        assertEquals(code, error.getString("code"));
        assertTrue(error.getString("message") != null && !error.getString("message").isBlank());
        assertEquals(res.headers().get(RequestId.HEADER), error.getString("requestId"));

        return error;
    }

    private static String note(String text) throws Exception
    {
        return api.queryOne("INSERT INTO probe_notes (text) VALUES ('" + text + "') RETURNING id::text AS id").getString("id");
    }
}
