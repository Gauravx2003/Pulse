package com.pulse.modules.effort;

import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 11.2: confirm and reject of extra-mile items on a real server with the Track 2 demo seed: the
 * role matrix, version check, audit, separation of duties, Done-only confirmation and Confirm all. Each
 * test decides its own items. The culture bonus and the caps are covered in {@code EffortCultureTest}.
 */
class EffortRoutesTest
{

    private static final int PORT = 18_250;

    private static final String API = "/api/v1/effort/items";

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress");
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    @Test
    void onlyEffortConfirmHoldersDecide() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "POST " + API + "/confirm-all", new JsonObject().put("ids", new JsonArray()), Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));
    }

    @Test
    void theTeamTabIsForManagersAndThePersonTabFollowsVisibility() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/effort/team?period=m:2026-09", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));

        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/effort/people/{person:priya@pulse.demo}?period=m:2026-09", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 403));
    }

    @Test
    void thePersonTabHasTimeTheDailyLogItemsAndThePeriodTable() throws Exception
    {
        String priya = api.queryOne("SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");

        HttpResponse<Buffer> res = api.get("/api/v1/effort/people/" + priya + "?period=m:2026-09", RoleMatrix.session(api, RoleMatrix.EMPLOYEE));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject body = res.bodyAsJsonObject();

        assertEquals(30, body.getJsonObject("dailyLog").getJsonArray("days").size(), "every day of September");
        assertTrue(body.getJsonObject("effort").getDouble("logged") > 0);
        assertFalse(body.getJsonObject("effort").getJsonArray("items").isEmpty());
        assertEquals("m:2026-09", body.getJsonArray("periods").getJsonObject(0).getString("period"));
        assertFalse(body.getBoolean("canConfirm"), "Priya cannot confirm her own items");
    }

    @Test
    void theTeamTabListsPeopleRequestersAndTheItemsToReview() throws Exception
    {
        HttpResponse<Buffer> res = api.get("/api/v1/effort/team?period=m:2026-09", RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject body = res.bodyAsJsonObject();

        assertEquals(8, body.getJsonArray("people").size());
        assertFalse(body.getJsonArray("requesters").isEmpty());
        assertTrue(body.getJsonArray("review").stream().allMatch(i -> "pending".equals(((JsonObject) i).getString("state"))
                && ((JsonObject) i).getInteger("version") != null));
        assertTrue(body.getBoolean("canConfirm"));
    }

    @Test
    void aManagerConfirmsADoneItemOnceWithItsVersion() throws Exception
    {
        JsonObject item = item("sk", "PLAT-2266");

        HttpResponse<Buffer> stale = decide(item, "confirm", item.getInteger("version") + 1);

        assertEquals(409, stale.statusCode(), stale.bodyAsString());
        assertEquals("version_conflict", TestApi.error(stale).getString("code"));

        HttpResponse<Buffer> res = decide(item, "confirm", item.getInteger("version"));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject saved = res.bodyAsJsonObject();

        assertEquals("confirmed", saved.getString("state"));
        assertEquals(item.getInteger("version") + 1, saved.getInteger("version"));
        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'effort.confirm' AND outcome = 'success'"
                + " AND target_id = '" + item.getString("id") + "'"));

        HttpResponse<Buffer> again = decide(item, "reject", saved.getInteger("version"));

        assertEquals(422, again.statusCode(), again.bodyAsString());
        assertEquals("already_decided", TestApi.error(again).getString("code"));
    }

    @Test
    void workThatIsNotDoneCanBeRejectedButNotConfirmed() throws Exception
    {
        JsonObject item = item("am", "PLAT-2244");

        HttpResponse<Buffer> confirm = decide(item, "confirm", item.getInteger("version"));

        assertEquals(422, confirm.statusCode(), confirm.bodyAsString());
        assertEquals("not_done", TestApi.error(confirm).getString("code"));

        HttpResponse<Buffer> reject = decide(item, "reject", item.getInteger("version"));

        assertEquals(200, reject.statusCode(), reject.bodyAsString());
        assertEquals("rejected", reject.bodyAsJsonObject().getString("state"));
        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'effort.reject'"
                + " AND target_id = '" + item.getString("id") + "'"));
    }

    @Test
    void nobodyConfirmsTheirOwnPoints() throws Exception
    {
        // The demo managers have no work of their own: give Rahul a person record and one done item for this test.
        String rahul = api.queryOne("""
                INSERT INTO people (user_id, name, source, external_id)
                SELECT id, name, 'test', 'effort-self' FROM users WHERE lower(email) = 'rahul@pulse.demo'
                RETURNING id""").getString("id");

        try
        {
            JsonObject own = api.queryOne("""
                    INSERT INTO extra_items (detection_key, person_id, kind, ref, title, requested_by, detection_note,
                                             evidence_source, occurred_on, hours, is_done, values_credited)
                    VALUES ('help:SELF-1:' || '%s', '%s', 'help', 'SELF-1', 'Own item', 'Self-initiated', 'test', 'Jira',
                            '2026-09-24', 2, true, ARRAY['Collaboration'])
                    RETURNING id, version""".formatted(rahul, rahul));

            HttpResponse<Buffer> res = decide(own, "confirm", own.getInteger("version"));

            assertEquals(422, res.statusCode(), res.bodyAsString());
            assertEquals("self_action_refused", TestApi.error(res).getString("code"));
            assertEquals("pending", api.queryOne("SELECT state FROM extra_items WHERE id = '" + own.getString("id") + "'")
                    .getString("state"));

            JsonObject all = confirmAll(new JsonArray().add(own.getString("id")));

            assertEquals("own", all.getJsonArray("skipped").getJsonObject(0).getString("reason"));
        }
        finally
        {
            api.exec("DELETE FROM extra_items WHERE person_id = '" + rahul + "'");

            api.exec("DELETE FROM people WHERE id = '" + rahul + "'");
        }
    }

    @Test
    void confirmAllConfirmsDoneItemsAndSkipsTheRest() throws Exception
    {
        JsonObject done = item("kr", "SUP-8831");

        JsonObject notDone = item("kr", "PLAT-2238");

        String missing = UUID.randomUUID().toString();

        JsonObject result = confirmAll(new JsonArray().add(done.getString("id")).add(notDone.getString("id")).add(missing));

        assertEquals(new JsonArray().add(done.getString("id")), result.getJsonArray("confirmed"));

        JsonArray skipped = result.getJsonArray("skipped");

        assertEquals(2, skipped.size());
        assertTrue(skipped.stream().anyMatch(s -> "not_done".equals(((JsonObject) s).getString("reason"))));
        assertTrue(skipped.stream().anyMatch(s -> "not_found".equals(((JsonObject) s).getString("reason"))));

        JsonObject repeat = confirmAll(new JsonArray().add(done.getString("id")));

        assertEquals("decided", repeat.getJsonArray("skipped").getJsonObject(0).getString("reason"));
    }

    @Test
    void anUnknownItemAnswers404AndAMissingVersion400() throws Exception
    {
        JsonObject ghost = new JsonObject().put("id", UUID.randomUUID().toString());

        assertEquals(404, decide(ghost, "confirm", 1).statusCode());

        HttpResponse<Buffer> res = api.post(API + "/" + item("nj", "PLAT-2266").getString("id") + "/reject",
                RoleMatrix.session(api, RoleMatrix.MANAGER), new JsonObject());

        assertEquals(400, res.statusCode(), res.bodyAsString());
        assertEquals("version", TestApi.error(res).getString("field"));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject item(String person, String ref) throws Exception
    {
        return api.queryOne("""
                SELECT x.id, x.version FROM extra_items x JOIN people p ON p.id = x.person_id
                WHERE p.source = 'seed' AND p.external_id = '%s' AND x.ref = '%s'""".formatted(person, ref));
    }

    private static HttpResponse<Buffer> decide(JsonObject item, String decision, int version) throws Exception
    {
        return api.post(API + "/" + item.getString("id") + "/" + decision, RoleMatrix.session(api, RoleMatrix.MANAGER),
                new JsonObject().put("version", version));
    }

    private static JsonObject confirmAll(JsonArray ids) throws Exception
    {
        HttpResponse<Buffer> res = api.post(API + "/confirm-all", RoleMatrix.session(api, RoleMatrix.MANAGER),
                new JsonObject().put("ids", ids));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }
}
