package com.pulse.modules.bugs;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 12.1: Bugs & support on a real server with the Track 2 demo seed and "today" pinned to
 * 28 Sep 2026: the scenarios of spec work/bugs-support (dispute removed and kept, with audit; take it;
 * pick up to a proactive-fix item; resolve a ticket to a support item; employee view; copy) and the
 * role matrix. Each test acts on its own bugs and tickets. Attribution is in {@code BugAttributionTest}.
 */
class BugsRoutesTest
{

    private static final int PORT = 18_260;

    private static final String API = "/api/v1/bugs";

    private static TestApi api;

    private static String priya;

    private static String arjun;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress");

        priya = person("ps");

        arjun = person("am");
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    // ------------------------------------------------------------------ access and views

    @Test
    void anEmployeeHasNoTeamTab() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/team", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));

        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/people/{person:priya@pulse.demo}", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 403));
    }

    @Test
    void openBugsReadAsALearningSignalNotBlame() throws Exception
    {
        JsonObject body = get(API + "/people/" + priya, RoleMatrix.EMPLOYEE);

        assertTrue(body.getString("subtitle").endsWith("a learning signal, not blame"));
        assertTrue(keys(body.getJsonArray("open")).contains("BUG-4050"), "in her code, fixed by Neha");
        assertTrue(keys(body.getJsonArray("queue")).contains("SUP-8872"));
        assertTrue(body.getJsonArray("toPickUp").stream().noneMatch(b -> priya.equals(((JsonObject) b).getString("originPersonId"))),
                "nothing in her own code to pick up");

        JsonObject manager = get(API + "/people/" + priya, RoleMatrix.MANAGER);

        assertEquals(null, manager.getValue("toPickUp"), "only the person sees what they could pick up");
    }

    // ------------------------------------------------------------------ disputes

    @Test
    void aRemovedDisputeTakesTheBugOutOfTheCountAndIsLogged() throws Exception
    {
        int before = get(API + "/people/" + priya, RoleMatrix.EMPLOYEE).getJsonObject("stats").getInteger("open");

        HttpResponse<Buffer> shortReason = post(API + "/items/BUG-4101/dispute", RoleMatrix.EMPLOYEE, new JsonObject().put("reason", "not me"));

        assertEquals(400, shortReason.statusCode());
        assertEquals("reason", TestApi.error(shortReason).getString("field"));

        HttpResponse<Buffer> raised = post(API + "/items/BUG-4101/dispute", RoleMatrix.EMPLOYEE,
                new JsonObject().put("reason", "The regression came from a config change in PR #799"));

        assertEquals(201, raised.statusCode(), raised.bodyAsString());

        JsonObject dispute = raised.bodyAsJsonObject();

        assertEquals(409, post(API + "/items/BUG-4101/dispute", RoleMatrix.EMPLOYEE,
                new JsonObject().put("reason", "Asking again about the same bug")).statusCode());

        assertTrue(get(API + "/team", RoleMatrix.MANAGER).getJsonArray("disputes").stream()
                .anyMatch(d -> dispute.getString("id").equals(((JsonObject) d).getString("id"))), "the manager sees it to decide");

        HttpResponse<Buffer> decided = post(API + "/disputes/" + dispute.getString("id") + "/decide", RoleMatrix.MANAGER,
                new JsonObject().put("decision", "remove").put("version", dispute.getInteger("version")));

        assertEquals(200, decided.statusCode(), decided.bodyAsString());

        JsonObject after = get(API + "/people/" + priya, RoleMatrix.EMPLOYEE);

        assertFalse(keys(after.getJsonArray("open")).contains("BUG-4101"), "no longer counted for Priya");
        assertEquals(before - 1, after.getJsonObject("stats").getInteger("open"));

        JsonObject audit = api.queryOne("SELECT outcome, summary->>'decision' AS decision FROM audit_log"
                + " WHERE action = 'bugs.dispute.resolve' AND summary->>'key' = 'BUG-4101'");

        assertEquals("success", audit.getString("outcome"));
        assertEquals("remove", audit.getString("decision"));

        assertEquals(422, post(API + "/disputes/" + dispute.getString("id") + "/decide", RoleMatrix.MANAGER,
                new JsonObject().put("decision", "keep").put("version", 2)).statusCode(), "decided once");
    }

    @Test
    void aKeptDisputeLeavesTheAttribution() throws Exception
    {
        JsonObject dispute = post(API + "/items/BUG-4088/dispute", RoleMatrix.EMPLOYEE,
                new JsonObject().put("reason", "The unit label comes from the shared formatter")).bodyAsJsonObject();

        assertEquals(200, post(API + "/disputes/" + dispute.getString("id") + "/decide", RoleMatrix.MANAGER,
                new JsonObject().put("decision", "keep").put("version", dispute.getInteger("version"))).statusCode());

        assertTrue(keys(get(API + "/people/" + priya, RoleMatrix.EMPLOYEE).getJsonArray("open")).contains("BUG-4088"));
        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'bugs.dispute.resolve' AND summary->>'key' = 'BUG-4088'"
                + " AND summary->>'decision' = 'keep'"));
    }

    @Test
    void onlyTheCodeOwnerDisputes() throws Exception
    {
        // Arjun cannot see Priya's bug: the same answer as a missing one
        assertEquals(404, post(API + "/items/BUG-4050/dispute", RoleMatrix.PEER,
                new JsonObject().put("reason", "This is not even my bug at all")).statusCode());
    }

    // ------------------------------------------------------------------ fixing

    @Test
    void theOwnerTakesTheFixOfABugInTheirCode() throws Exception
    {
        HttpResponse<Buffer> res = post(API + "/items/BUG-4050/take", RoleMatrix.EMPLOYEE, new JsonObject());

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertEquals(priya, res.bodyAsJsonObject().getString("assigneeId"));
        assertEquals("In Progress", res.bodyAsJsonObject().getString("status"));
        assertFalse(res.bodyAsJsonObject().getBoolean("proactive"));

        assertEquals("already_yours", TestApi.error(post(API + "/items/BUG-4050/take", RoleMatrix.EMPLOYEE, new JsonObject()))
                .getString("code"));
    }

    @Test
    void pickingUpAndResolvingABugInAnothersCodeAddsAProactiveItemAwaitingConfirmation() throws Exception
    {
        HttpResponse<Buffer> picked = post(API + "/items/BUG-4077/pick-up", RoleMatrix.PEER, new JsonObject());

        assertEquals(200, picked.statusCode(), picked.bodyAsString());
        assertTrue(picked.bodyAsJsonObject().getBoolean("proactive"));
        assertEquals(arjun, picked.bodyAsJsonObject().getString("assigneeId"));

        HttpResponse<Buffer> resolved = post(API + "/items/BUG-4077/resolve", RoleMatrix.PEER, new JsonObject().put("hours", 3));

        assertEquals(200, resolved.statusCode(), resolved.bodyAsString());
        assertEquals("Resolved", resolved.bodyAsJsonObject().getString("status"));
        assertEquals(arjun, resolved.bodyAsJsonObject().getString("fixedById"));
        assertTrue(resolved.bodyAsJsonObject().getBoolean("pendingItem"));

        JsonObject item = api.queryOne("SELECT kind, state, is_done, values_credited::text AS v FROM extra_items"
                + " WHERE detection_key = 'proactivefix:BUG-4077:" + arjun + "'");

        assertEquals("proactivefix", item.getString("kind"));
        assertEquals("pending", item.getString("state"));
        assertEquals("{\"Problem Solving\",Collaboration}", item.getString("v"));

        assertEquals("already_resolved", TestApi.error(post(API + "/items/BUG-4077/resolve", RoleMatrix.PEER, new JsonObject()))
                .getString("code"));
    }

    @Test
    void onlyThePersonFixingABugResolvesIt() throws Exception
    {
        HttpResponse<Buffer> res = post(API + "/items/BUG-4066/resolve", RoleMatrix.MANAGER, new JsonObject());

        assertEquals(422, res.statusCode(), res.bodyAsString());
        assertEquals("not_assigned_to_you", TestApi.error(res).getString("code"));
    }

    // ------------------------------------------------------------------ support

    @Test
    void resolvingATicketWithHoursAndANoteClosesItAndAddsAPointAwaitingConfirmation() throws Exception
    {
        HttpResponse<Buffer> taken = post(API + "/tickets/SUP-8870/take", RoleMatrix.EMPLOYEE, new JsonObject());

        assertEquals(200, taken.statusCode(), taken.bodyAsString());
        assertEquals(priya, taken.bodyAsJsonObject().getString("personId"));

        assertEquals("ticket_taken", TestApi.error(post(API + "/tickets/SUP-8870/take", RoleMatrix.PEER, new JsonObject()))
                .getString("code"));

        assertEquals(400, post(API + "/tickets/SUP-8870/resolve", RoleMatrix.EMPLOYEE,
                new JsonObject().put("hours", 0).put("note", "Fixed the trap filter")).statusCode());

        assertEquals(400, post(API + "/tickets/SUP-8870/resolve", RoleMatrix.EMPLOYEE, new JsonObject().put("hours", 2)).statusCode());

        HttpResponse<Buffer> resolved = post(API + "/tickets/SUP-8870/resolve", RoleMatrix.EMPLOYEE,
                new JsonObject().put("hours", 2).put("note", "Re-keyed the v3 auth profile for the device family"));

        assertEquals(200, resolved.statusCode(), resolved.bodyAsString());
        assertEquals("Resolved", resolved.bodyAsJsonObject().getString("status"));
        assertEquals(2.0, resolved.bodyAsJsonObject().getDouble("hours"), 1e-9);

        JsonObject item = api.queryOne("SELECT kind, state FROM extra_items WHERE detection_key = 'support:SUP-8870:" + priya + "'");

        assertNotNull(item);
        assertEquals("pending", item.getString("state"));

        assertFalse(keys(get(API + "/people/" + priya, RoleMatrix.EMPLOYEE).getJsonArray("queue")).contains("SUP-8870"));
    }

    // ------------------------------------------------------------------ helpers

    private static String person(String externalId) throws Exception
    {
        return api.queryOne("SELECT id FROM people WHERE source = 'seed' AND external_id = '" + externalId + "'").getString("id");
    }

    private static JsonObject get(String path, String email) throws Exception
    {
        HttpResponse<Buffer> res = api.get(path, RoleMatrix.session(api, email));

        assertEquals(200, res.statusCode(), path + ": " + res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static HttpResponse<Buffer> post(String path, String email, JsonObject body) throws Exception
    {
        return api.post(path, RoleMatrix.session(api, email), body);
    }

    private static List<String> keys(JsonArray items)
    {
        return items.stream().map(i -> ((JsonObject) i).getString("key")).toList();
    }
}
