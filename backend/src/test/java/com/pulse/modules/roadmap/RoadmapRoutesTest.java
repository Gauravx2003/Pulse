package com.pulse.modules.roadmap;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 15.2: the road map on a real server with the Track 2 demo seed and "today" pinned to 28 Sep 2026:
 * the scenarios of spec growth/roadmap (propose to Proposed with the manager's Action center item, the
 * manager confirms, promotion readiness from the calc service, the skills gap, a peer refused) and
 * separation of duties. The criteria maths is in {@code PromotionReadinessTest}.
 */
class RoadmapRoutesTest
{

    private static final int PORT = 18_290;

    private static final String API = "/api/v1/roadmap";

    private static TestApi api;

    private static String priya;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress",
                "okrs", "roadmap", "action_center");

        priya = api.queryOne("SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    @Test
    void onlyThePersonTheirManagerAndAllViewSeeARoadMap() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/people/{person:priya@pulse.demo}", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 403));
    }

    @Test
    void theRoadMapHasMilestonesReadinessAndTheSkillsGap() throws Exception
    {
        JsonObject body = get(API + "/people/" + priya, RoleMatrix.EMPLOYEE);

        JsonArray milestones = body.getJsonArray("milestones");

        assertTrue(milestones.size() >= 5, "the 5 seeded milestones, plus any another test proposed");
        assertEquals("Joined Platform team \u00b7 Engineer II", milestones.getJsonObject(0).getString("title"));

        JsonObject readiness = body.getJsonObject("readiness");

        assertEquals("L4", readiness.getJsonObject("target").getString("level"));
        assertEquals(4, readiness.getJsonArray("criteria").size());
        assertEquals("Open", criterion(readiness, "epic").getString("status"), "no cross-team epic recorded");

        JsonArray skills = body.getJsonObject("skills").getJsonArray("items");

        assertEquals("−2", skill(skills, "Mentoring").getString("gap"));
        assertEquals("Ready", skill(skills, "Go / backend").getString("gap"));

        assertFalse(body.getJsonObject("can").getBoolean("confirm"), "Priya cannot confirm her own");
        assertTrue(get(API + "/people/" + priya, RoleMatrix.MANAGER).getJsonObject("can").getBoolean("confirm"));
    }

    @Test
    void aProposedMilestoneWaitsForTheManagerWhoConfirmsIt() throws Exception
    {
        HttpResponse<Buffer> missing = post(API + "/people/" + priya + "/milestones", RoleMatrix.EMPLOYEE, new JsonObject().put("title", "x"));

        assertEquals(400, missing.statusCode());

        HttpResponse<Buffer> proposed = post(API + "/people/" + priya + "/milestones", RoleMatrix.EMPLOYEE,
                new JsonObject().put("title", "Author 2 ADRs for the event bus").put("when", "Q1 2027").put("value", "Decision Making"));

        assertEquals(201, proposed.statusCode(), proposed.bodyAsString());

        JsonObject milestone = proposed.bodyAsJsonObject();

        assertEquals("proposed", milestone.getString("status"));
        assertEquals("Proposed", milestone.getString("note"));

        // the manager is told to confirm: an item in their Action center
        HttpResponse<Buffer> actions = api.get("/api/v1/action_center/items", RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertTrue(actions.bodyAsJsonObject().getJsonArray("items").stream().map(i -> (JsonObject) i)
                .anyMatch(i -> "roadmap.milestone-proposed".equals(i.getString("key"))
                        && i.getString("text").equals("Milestone proposed: Author 2 ADRs for the event bus (Q1 2027)")), actions.bodyAsString());

        String confirm = API + "/milestones/" + milestone.getString("id") + "/confirm";

        assertEquals(403, post(confirm, RoleMatrix.EMPLOYEE, new JsonObject().put("version", 1)).statusCode());
        assertEquals(409, post(confirm, RoleMatrix.MANAGER, new JsonObject().put("version", milestone.getInteger("version") + 1)).statusCode());

        HttpResponse<Buffer> confirmed = post(confirm, RoleMatrix.MANAGER, new JsonObject().put("version", milestone.getInteger("version")));

        assertEquals(200, confirmed.statusCode(), confirmed.bodyAsString());
        assertEquals("confirmed", confirmed.bodyAsJsonObject().getString("status"));
        assertEquals("Planned", confirmed.bodyAsJsonObject().getString("note"));
        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'roadmap.milestone.confirmed' AND target_id = '"
                + milestone.getString("id") + "'"));

        assertEquals("already_confirmed", TestApi.error(post(confirm, RoleMatrix.MANAGER,
                new JsonObject().put("version", confirmed.bodyAsJsonObject().getInteger("version")))).getString("code"));
    }

    @Test
    void aPeerCannotProposeOnAnotherRoadMap() throws Exception
    {
        assertEquals(403, post(API + "/people/" + priya + "/milestones", RoleMatrix.PEER,
                new JsonObject().put("title", "Something for Priya").put("when", "Q2 2027")).statusCode());
    }

    @Test
    void theManagerRatesSkillsAndAGapOfOneReadsMinusOne() throws Exception
    {
        assertEquals(403, put(API + "/people/" + priya + "/skills", RoleMatrix.EMPLOYEE,
                new JsonObject().put("skill", "Kafka").put("current", 2).put("needed", 3)).statusCode());

        HttpResponse<Buffer> rated = put(API + "/people/" + priya + "/skills", RoleMatrix.MANAGER,
                new JsonObject().put("skill", "Kafka").put("current", 2).put("needed", 3));

        assertEquals(200, rated.statusCode(), rated.bodyAsString());
        assertEquals("−1", rated.bodyAsJsonObject().getString("gap"));

        HttpResponse<Buffer> again = put(API + "/people/" + priya + "/skills", RoleMatrix.MANAGER,
                new JsonObject().put("skill", "kafka").put("current", 3).put("needed", 3));

        assertEquals("Ready", again.bodyAsJsonObject().getString("gap"));
        assertEquals(rated.bodyAsJsonObject().getString("id"), again.bodyAsJsonObject().getString("id"), "same skill, any case");

        HttpResponse<Buffer> removed = api.delete(API + "/people/" + priya + "/skills/" + rated.bodyAsJsonObject().getString("id"),
                RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertEquals(204, removed.statusCode());
    }

    @Test
    void aManagerNeverConfirmsTheirOwnMilestone() throws Exception
    {
        String rahul = api.queryOne("""
                INSERT INTO people (user_id, name, source, external_id)
                SELECT id, name, 'test', 'roadmap-self' FROM users WHERE lower(email) = 'rahul@pulse.demo'
                RETURNING id""").getString("id");

        try
        {
            JsonObject own = post(API + "/people/" + rahul + "/milestones", RoleMatrix.MANAGER,
                    new JsonObject().put("title", "Run the platform guild").put("when", "Q1 2027")).bodyAsJsonObject();

            HttpResponse<Buffer> res = post(API + "/milestones/" + own.getString("id") + "/confirm", RoleMatrix.MANAGER,
                    new JsonObject().put("version", own.getInteger("version")));

            assertEquals(422, res.statusCode(), res.bodyAsString());
            assertEquals("self_action_refused", TestApi.error(res).getString("code"));
        }
        finally
        {
            api.exec("DELETE FROM milestones WHERE person_id = '" + rahul + "'");

            api.exec("DELETE FROM people WHERE id = '" + rahul + "'");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject criterion(JsonObject readiness, String key)
    {
        return readiness.getJsonArray("criteria").stream().map(c -> (JsonObject) c).filter(c -> key.equals(c.getString("key")))
                .findFirst().orElseThrow();
    }

    private static JsonObject skill(JsonArray skills, String name)
    {
        return skills.stream().map(s -> (JsonObject) s).filter(s -> name.equals(s.getString("skill"))).findFirst().orElseThrow();
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

    private static HttpResponse<Buffer> put(String path, String email, JsonObject body) throws Exception
    {
        return api.put(path, RoleMatrix.session(api, email), body);
    }
}
