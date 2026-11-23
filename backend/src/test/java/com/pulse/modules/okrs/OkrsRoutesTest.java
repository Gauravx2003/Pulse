package com.pulse.modules.okrs;

import com.pulse.platform.auth.SessionHandler;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 14.2 (and Track 2's endpoint for the backend guide, task 3.9): the OKR API on a real server with
 * the demo seed. Contract tests per role with {@link RoleMatrix}, the form's error messages, the
 * version check, check-in with the new progress, and the audit trail.
 */
class OkrsRoutesTest
{

    private static final int PORT = 18_210;

    private static final String API = "/api/v1/okrs";

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null, "okrs");
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    // ------------------------------------------------------------------ role matrix

    @Test
    void aPersonsOkrsFollowVisibility() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/people/{person:priya@pulse.demo}/objectives?quarter=2026-Q3", Map.of(
                RoleMatrix.ADMIN, 200,       // hr@: all.view
                RoleMatrix.MANAGER, 200,     // rahul@: Priya's manager
                RoleMatrix.EMPLOYEE, 200,    // priya@: herself
                RoleMatrix.PEER, 403));      // arjun@: a peer
    }

    @Test
    void teamOkrsAreVisibleToEveryone() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/teams/Platform/objectives?quarter=Q3%202026", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 200));
    }

    @Test
    void oneObjectiveIsHiddenFromAPeer() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/objectives/" + seeded("Make ingestion safe for noisy tenants"), Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 404));      // the same answer as a missing objective
    }

    @Test
    void onlyTheOwnerOrTheirManagerChecksIn() throws Exception
    {
        String keyResult = api.queryOne("SELECT k.id FROM key_results k JOIN objectives o ON o.id = k.objective_id "
                + "WHERE o.title = 'Grow as a technical lead' AND k.position = 1").getString("id");

        RoleMatrix.assertRoleMatrix(api, "POST " + API + "/key-results/" + keyResult + "/check-ins",
                new JsonObject().put("value", 1), Map.of(
                        RoleMatrix.ADMIN, 200,
                        RoleMatrix.MANAGER, 200,
                        RoleMatrix.EMPLOYEE, 200,
                        RoleMatrix.PEER, 403));
    }

    // ------------------------------------------------------------------ reads

    @Test
    void theListCarriesProgressAndTheKpiRow() throws Exception
    {
        HttpResponse<Buffer> res = api.get(API + "/people/" + person("priya@pulse.demo") + "/objectives?quarter=Q3%202026",
                RoleMatrix.session(api, RoleMatrix.EMPLOYEE));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject body = res.bodyAsJsonObject();

        JsonObject summary = body.getJsonObject("summary");

        assertEquals(2, summary.getInteger("objectives"));
        assertEquals(5, summary.getInteger("keyResults"));
        assertTrue(summary.getValue("pillar") instanceof Number, summary.encode());

        JsonObject first = body.getJsonArray("items").getJsonObject(0);

        assertTrue(first.containsKey("progress") && first.containsKey("status") && first.containsKey("keyResults"), first.encode());
        assertTrue(first.getJsonArray("keyResults").getJsonObject(0).containsKey("startValue"), "camelCase fields");

        HttpResponse<Buffer> noQuarter = api.get(API + "/people/" + person("priya@pulse.demo") + "/objectives",
                RoleMatrix.session(api, RoleMatrix.EMPLOYEE));

        assertEquals(400, noQuarter.statusCode());
        assertEquals("quarter", TestApi.error(noQuarter).getString("field"));
    }

    // ------------------------------------------------------------------ writes

    @Test
    void theFormsErrorMessagesComeBackPerField() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        HttpResponse<Buffer> noQuarter = api.post(API + "/objectives", priya, objective("Ship it", null));

        assertEquals(400, noQuarter.statusCode());
        assertEquals("invalid_field", TestApi.error(noQuarter).getString("code"));
        assertEquals("quarter", TestApi.error(noQuarter).getString("field"));
        assertEquals("Select a quarter.", TestApi.error(noQuarter).getString("message"));

        HttpResponse<Buffer> flat = api.post(API + "/objectives", priya, objective("Ship it", "2026-Q4")
                .put("keyResults", new JsonArray().add(kr("Flat", 3, 3))));

        assertEquals("Target must differ from start.", TestApi.error(flat).getString("message"));
        assertEquals("keyResults", TestApi.error(flat).getString("field"));

        HttpResponse<Buffer> unknown = api.post(API + "/objectives", priya, objective("Ship it", "2026-Q4").put("progress", 1));

        assertEquals(400, unknown.statusCode(), "the schema rejects unknown fields");
    }

    @Test
    void createEditConflictAndDeleteAnObjective() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        HttpResponse<Buffer> created = api.post(API + "/objectives", priya, objective("Cut alert noise", "2026-Q4")
                .put("keyResults", new JsonArray().add(kr("False-positive alerts", 30, 10).put("currentValue", 20))));

        assertEquals(201, created.statusCode(), created.bodyAsString());

        JsonObject objective = created.bodyAsJsonObject();

        assertEquals(0.5, objective.getDouble("progress"), 1e-9);
        assertEquals("At risk", objective.getString("status"));
        assertEquals(person("priya@pulse.demo"), objective.getString("ownerId"));

        String id = objective.getString("id");

        JsonObject keyResult = objective.getJsonArray("keyResults").getJsonObject(0);

        JsonObject edit = new JsonObject()
                .put("title", "Cut alert noise for NOC teams")
                .put("quarter", "2026-Q4")
                .put("version", objective.getInteger("version"))
                .put("keyResults", new JsonArray().add(new JsonObject()
                        .put("id", keyResult.getString("id"))
                        .put("description", keyResult.getString("description"))
                        .put("startValue", 30)
                        .put("targetValue", 10)
                        .put("currentValue", 12)));

        HttpResponse<Buffer> saved = api.put(API + "/objectives/" + id, priya, edit);

        assertEquals(200, saved.statusCode(), saved.bodyAsString());
        assertEquals(objective.getInteger("version") + 1, saved.bodyAsJsonObject().getInteger("version"));
        assertEquals("On track", saved.bodyAsJsonObject().getString("status"));

        HttpResponse<Buffer> stale = api.put(API + "/objectives/" + id, priya, edit);

        assertEquals(409, stale.statusCode());
        assertEquals("version_conflict", TestApi.error(stale).getString("code"));
        assertEquals(objective.getInteger("version") + 1, TestApi.error(stale).getInteger("currentVersion"));

        HttpResponse<Buffer> duplicate = api.post(API + "/objectives", priya, objective("cut alert noise for noc TEAMS", "2026-Q4"));

        assertEquals(409, duplicate.statusCode());
        assertEquals("okr_exists", TestApi.error(duplicate).getString("code"));

        HttpResponse<Buffer> deleted = delete(API + "/objectives/" + id, priya);

        assertEquals(200, deleted.statusCode(), deleted.bodyAsString());
        assertEquals(1, deleted.bodyAsJsonObject().getInteger("keyResults"));

        assertEquals(404, api.get(API + "/objectives/" + id, priya).statusCode());

        assertEquals(3L, api.count("SELECT count(*) FROM audit_log WHERE target_id = '" + id + "' AND action IN "
                + "('okr.objective.created', 'okr.objective.updated', 'okr.objective.deleted')"));
    }

    @Test
    void onlyOkrTeamCreatesTeamObjectives() throws Exception
    {
        JsonObject team = objective("Faster Platform reviews", "2026-Q4").put("level", "Team").put("team", "Platform");

        assertEquals(403, api.post(API + "/objectives", RoleMatrix.session(api, RoleMatrix.EMPLOYEE), team).statusCode());

        assertEquals(201, api.post(API + "/objectives", RoleMatrix.session(api, RoleMatrix.MANAGER), team).statusCode());
    }

    @Test
    void aCheckInAnswersWithTheObjectivesNewProgress() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        JsonObject created = api.post(API + "/objectives", priya, objective("Write two ADRs", "2027-Q1")
                .put("keyResults", new JsonArray().add(kr("ADRs accepted", 0, 2)))).bodyAsJsonObject();

        String keyResult = created.getJsonArray("keyResults").getJsonObject(0).getString("id");

        HttpResponse<Buffer> res = api.post(API + "/key-results/" + keyResult + "/check-ins", priya,
                new JsonObject().put("value", 1).put("note", "First ADR merged"));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject body = res.bodyAsJsonObject();

        assertEquals(0.5, body.getJsonObject("objective").getDouble("progress"), 1e-9);
        assertNotNull(body.getJsonObject("keyResult").getValue("currentValue"));

        assertEquals(1L, api.count("SELECT count(*) FROM key_result_checkins WHERE key_result_id = '" + keyResult
                + "' AND via = 'manual'"));

        assertEquals(1L, api.count("SELECT count(*) FROM audit_log WHERE action = 'okr.key_result.checked_in' "
                + "AND target_id = '" + created.getString("id") + "'"));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject objective(String title, String quarter)
    {
        JsonObject body = new JsonObject()
                .put("title", title)
                .put("keyResults", new JsonArray().add(kr("Done", 0, 1)));

        return quarter == null ? body : body.put("quarter", quarter);
    }

    private static JsonObject kr(String description, Number start, Number target)
    {
        return new JsonObject().put("description", description).put("startValue", start).put("targetValue", target);
    }

    private static String seeded(String title) throws Exception
    {
        return api.queryOne("SELECT id FROM objectives WHERE title = '" + title + "'").getString("id");
    }

    private static String person(String email) throws Exception
    {
        return api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = '" + email + "'")
                .getString("id");
    }

    /**
     * DELETE with the session's cookie and CSRF token ({@link TestApi} has no delete helper).
     */
    private static HttpResponse<Buffer> delete(String path, TestApi.Session session) throws Exception
    {
        return await(WebClient.create(api.vertx()).delete(PORT, "localhost", path)
                .putHeader("X-Real-IP", TestApi.newIp())
                .putHeader("Cookie", session.cookie())
                .putHeader(SessionHandler.CSRF_HEADER, session.csrf())
                .send());
    }
}
