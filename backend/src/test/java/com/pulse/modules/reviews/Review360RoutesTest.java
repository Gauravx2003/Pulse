package com.pulse.modules.reviews;

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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec culture/review-360 (task 20.3, the parts that do not need calc/people), with the demo seed. */
class Review360RoutesTest
{
    private static final int PORT = 18_340;

    private static TestApi api;

    private static WebClient client;

    private static String cycleId;

    private static String priyaPerson;

    private static String nehaPerson;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);

        for (String script : new String[] {"modules/reviews/migrations/V340__review_360.sql", "modules/reviews/seed/S340__demo_review_360.sql"})
        {
            try (InputStream in = Review360RoutesTest.class.getClassLoader().getResourceAsStream(script))
            {
                api.exec(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        priyaPerson = personOf("priya@pulse.demo");

        nehaPerson = personOf("neha@pulse.demo");

        // The seeded Q3 cycle is running; close it so these tests run their own cycle.
        api.exec("UPDATE review_cycles SET status = 'closed' WHERE status = 'running'");

        HttpResponse<Buffer> created = api.post("/api/v1/reviews/360/cycles", RoleMatrix.session(api, RoleMatrix.ADMIN),
                new JsonObject().put("name", "Q4 2026 360° review").put("quarter", "Q4 2026").put("dueOn", "2026-12-18")
                        .put("department", "Platform"));

        assertEquals(201, created.statusCode(), created.bodyAsString());

        cycleId = created.bodyAsJsonObject().getString("id");

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

    private static String personOf(String email) throws Exception
    {
        return api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = '" + email + "'").getString("id");
    }

    private static String userOf(String email) throws Exception
    {
        return api.queryOne("SELECT id FROM users WHERE email = '" + email + "'").getString("id");
    }

    private static JsonObject assign(TestApi.Session session, String subjectId, String... reviewerEmails) throws Exception
    {
        JsonArray reviewers = new JsonArray();

        for (String email : reviewerEmails)
        {
            reviewers.add(userOf(email));
        }

        HttpResponse<Buffer> res = api.post("/api/v1/reviews/360/cycles/" + cycleId + "/assignments", session,
                new JsonObject().put("subjectIds", JsonArray.of(subjectId)).put("reviewerIds", reviewers));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static String groupOf(JsonObject result, String reviewerName)
    {
        for (Object item : result.getJsonArray("assigned"))
        {
            if (reviewerName.equals(((JsonObject) item).getString("reviewerName")))
            {
                return ((JsonObject) item).getString("group");
            }
        }

        return null;
    }

    /** The request of {@code subject} in the test cycle that {@code reviewer} has to give. */
    private static JsonObject toGive(TestApi.Session reviewer, String subjectName) throws Exception
    {
        for (Object item : api.get("/api/v1/reviews/360/to-give", reviewer).bodyAsJsonObject().getJsonArray("items"))
        {
            JsonObject request = (JsonObject) item;

            if (cycleId.equals(request.getString("cycleId")) && subjectName.equals(request.getString("subjectName")))
            {
                return request;
            }
        }

        return null;
    }

    private static JsonObject fullAnswers()
    {
        JsonObject scores = new JsonObject();

        JsonObject examples = new JsonObject();

        for (String value : Review360Service.RATED_VALUES)
        {
            scores.put(value, 4);
        }

        scores.putNull("Risk Appetite");                       // Not observed

        scores.put("Outcome Ownership", 5);

        examples.put("Outcome Ownership", "Drove the incident fix to the end.");

        return new JsonObject().put("scores", scores).put("examples", examples)
                .put("keepDoing", "Owning problems until they are fixed.")
                .put("startDoing", "Splitting big tickets earlier.");
    }

    // ------------------------------------------------------------------ cycle

    @Test
    void onlyOneCycleRunsAndOnlyAdminCreatesCycles() throws Exception
    {
        HttpResponse<Buffer> second = api.post("/api/v1/reviews/360/cycles", RoleMatrix.session(api, RoleMatrix.ADMIN),
                new JsonObject().put("name", "Another").put("quarter", "Q4 2026").put("dueOn", "2026-12-20"));

        assertEquals(409, second.statusCode());

        assertEquals("cycle_running", TestApi.error(second).getString("code"));

        HttpResponse<Buffer> byManager = api.post("/api/v1/reviews/360/cycles", RoleMatrix.session(api, RoleMatrix.MANAGER),
                new JsonObject().put("name", "Mine").put("quarter", "Q4 2026").put("dueOn", "2026-12-20"));

        assertEquals(403, byManager.statusCode());
    }

    // ------------------------------------------------------------------ assignment

    @Test
    void groupsAreDerivedAndDuplicatesSkipped() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        JsonObject first = assign(rahul, priyaPerson, "arjun@pulse.demo", "sana@pulse.demo");

        assertEquals("self", groupOf(first, "Priya Shah"));

        assertEquals("manager", groupOf(first, "Rahul Nair"));

        assertEquals("internal", groupOf(first, "Arjun Mehta"));

        // Spec "Group derivation": a reviewer from another department is cross-department.
        assertEquals("cross", groupOf(first, "Sana Mirza"));

        JsonObject again = assign(rahul, priyaPerson, "arjun@pulse.demo");

        assertEquals(0, again.getJsonArray("assigned").size());

        assertTrue(again.getJsonArray("skipped").encode().contains("Already assigned"), again.encodePrettily());
    }

    @Test
    void nobodyAssignsThemselvesAsAPeerReviewer() throws Exception
    {
        JsonObject result = assign(RoleMatrix.session(api, RoleMatrix.ADMIN), nehaPerson, "hr@pulse.demo");

        assertTrue(result.getJsonArray("skipped").encode().contains("You can't assign yourself"), result.encodePrettily());

        assertTrue(api.count("SELECT count(*) FROM audit_log WHERE action = 'review360.reviewer.assign' AND outcome = 'refused'") >= 1);
    }

    @Test
    void assignmentViewIsForManagersAndHoldsNoAnswers() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/reviews/360/cycles/" + cycleId + "/assignments", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));

        assign(RoleMatrix.session(api, RoleMatrix.MANAGER), priyaPerson, "neha@pulse.demo");

        String body = api.get("/api/v1/reviews/360/cycles/" + cycleId + "/assignments", RoleMatrix.session(api, RoleMatrix.MANAGER))
                .bodyAsString();

        assertTrue(body.contains("Neha Joshi"), body);

        assertTrue(!body.contains("scores") && !body.contains("keepDoing"), body);
    }

    // ------------------------------------------------------------------ the form

    @Test
    void formValidatesDraftsAndLocksAfterSubmit() throws Exception
    {
        assign(RoleMatrix.session(api, RoleMatrix.MANAGER), priyaPerson, "kiran@pulse.demo");

        TestApi.Session kiran = RoleMatrix.session(api, "kiran@pulse.demo");

        JsonObject request = toGive(kiran, "Priya Shah");

        assertTrue(request != null);

        String path = "/api/v1/reviews/360/requests/" + request.getString("id");

        HttpResponse<Buffer> draft = api.put(path, kiran, new JsonObject()
                .put("scores", new JsonObject().put("Collaboration", 4)).put("version", request.getInteger("version")));

        assertEquals(200, draft.statusCode(), draft.bodyAsString());

        assertEquals("in_progress", draft.bodyAsJsonObject().getString("status"));

        int version = draft.bodyAsJsonObject().getInteger("version");

        // Spec "Rating 5 without example": submit is blocked with the requirement shown.
        JsonObject noExample = fullAnswers().put("examples", new JsonObject()).put("version", version);

        HttpResponse<Buffer> blocked = api.post(path + "/submit", kiran, noExample);

        assertEquals(422, blocked.statusCode());

        assertEquals("examples.Outcome Ownership", TestApi.error(blocked).getString("field"));

        assertTrue(TestApi.error(blocked).getString("message").contains("at least 10 characters"));

        // Discipline is not rated.
        JsonObject discipline = fullAnswers().put("version", version);

        discipline.getJsonObject("scores").put("Discipline", 3);

        assertEquals(400, api.post(path + "/submit", kiran, discipline).statusCode());

        HttpResponse<Buffer> submitted = api.post(path + "/submit", kiran, fullAnswers().put("version", version));

        assertEquals(200, submitted.statusCode(), submitted.bodyAsString());

        assertEquals("submitted", submitted.bodyAsJsonObject().getString("status"));

        HttpResponse<Buffer> edit = api.put(path, kiran, fullAnswers().put("version", version + 1));

        assertEquals(409, edit.statusCode());

        // The anonymous submission leaves no reviewer in the audit log.
        JsonObject audit = api.queryOne("SELECT actor_id, request_id, source_ip FROM audit_log WHERE action = 'review360.review_submitted' "
                + "AND target_id = '" + request.getString("id") + "'");

        assertEquals(null, audit.getString("actor_id"));

        assertEquals("", audit.getString("request_id"));

        // Another person never reads Kiran's request.
        assertEquals(404, api.get(path, RoleMatrix.session(api, RoleMatrix.PEER)).statusCode());
    }

    @Test
    void onlyPeerRequestsCanBeDeclined() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        assign(rahul, nehaPerson, "dev@pulse.demo");

        TestApi.Session dev = RoleMatrix.session(api, "dev@pulse.demo");

        JsonObject peer = toGive(dev, "Neha Joshi");

        HttpResponse<Buffer> declined = api.post("/api/v1/reviews/360/requests/" + peer.getString("id") + "/decline", dev,
                new JsonObject().put("version", peer.getInteger("version")));

        assertEquals(200, declined.statusCode(), declined.bodyAsString());

        TestApi.Session neha = RoleMatrix.session(api, "neha@pulse.demo");

        JsonObject self = toGive(neha, "Neha Joshi");

        HttpResponse<Buffer> refused = api.post("/api/v1/reviews/360/requests/" + self.getString("id") + "/decline", neha,
                new JsonObject().put("version", self.getInteger("version")));

        assertEquals(422, refused.statusCode());

        // A manager removes a request that is not submitted.
        HttpResponse<Buffer> removed = await(client.delete(PORT, "localhost", "/api/v1/reviews/360/requests/" + self.getString("id"))
                .putHeader("X-Real-IP", TestApi.newIp())
                .putHeader("Cookie", rahul.cookie())
                .putHeader(SessionHandler.CSRF_HEADER, rahul.csrf())
                .send());

        assertEquals(204, removed.statusCode(), removed.bodyAsString());
    }

    // ------------------------------------------------------------------ results

    @Test
    void employeeBeforePublishGetsCountsOnly() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        HttpResponse<Buffer> results = api.get("/api/v1/reviews/360/cycles/" + cycleId + "/results", priya);

        assertEquals(403, results.statusCode());

        assertEquals("results_not_published", TestApi.error(results).getString("code"));

        HttpResponse<Buffer> progress = api.get("/api/v1/reviews/360/cycles/" + cycleId + "/progress", priya);

        assertEquals(200, progress.statusCode(), progress.bodyAsString());

        assertEquals(false, progress.bodyAsJsonObject().getBoolean("published"));

        assertTrue(!progress.bodyAsString().contains("Arjun") && !progress.bodyAsString().contains("scores"), progress.bodyAsString());
    }
}
