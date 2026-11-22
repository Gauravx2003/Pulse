package com.pulse.modules.training;

import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec growth/training-mentoring "Mentoring" and "Manager views and suggestions", and checkpoint
 * requests through the 360° form (task 21.1 part D), with the demo seed.
 */
class TrainingMentoringRoutesTest
{
    private static final int PORT = 18_353;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private static TestApi api;

    /** ta1kk9: Rohit on Software Engineer onboarding, mentored by Priya, with an open session request. */
    private static String rohitPlan;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);

        for (String script : new String[] {"modules/reviews/migrations/V340__review_360.sql", "modules/reviews/migrations/V345__review_cycle_kinds.sql",
                "modules/training/migrations/V350__training.sql", "modules/training/seed/S350__demo_training.sql"})
        {
            try (InputStream in = TrainingMentoringRoutesTest.class.getClassLoader().getResourceAsStream(script))
            {
                api.exec(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        // Read by the platform's test-only ProbeModule relation on every training scope.
        api.exec("CREATE TABLE IF NOT EXISTS probe_mentors (mentor_id uuid NOT NULL, mentee_id uuid NOT NULL)");

        rohitPlan = api.queryOne("SELECT id FROM training_assignments WHERE external_id = 'ta1kk9'").getString("id");
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    private static TestApi.Session as(String email) throws Exception
    {
        return RoleMatrix.session(api, email);
    }

    private static JsonArray pendingOf(TestApi.Session session) throws Exception
    {
        HttpResponse<Buffer> res = api.get("/api/v1/training/mentoring/pending", session);

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject().getJsonArray("items");
    }

    private static JsonObject find(JsonArray items, String type, String assignmentId)
    {
        for (Object item : items)
        {
            JsonObject x = (JsonObject) item;

            if (type.equals(x.getString("type")) && assignmentId.equals(x.getString("assignmentId")))
            {
                return x;
            }
        }

        return null;
    }

    private static JsonObject session(String topics, LocalDate on)
    {
        return new JsonObject().put("heldOn", on.toString()).put("durationMin", 30).put("mode", "In person").put("topics", topics)
                .put("nextSteps", "Pair on the first bug.");
    }

    /** Notifications of this kind the user has. */
    private static long notices(String email, String kind) throws Exception
    {
        return api.count("SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id WHERE u.email = '" + email
                + "' AND n.kind = '" + kind + "'");
    }

    /** The user's menu badge for a screen (0 when absent). */
    private static int badge(TestApi.Session session, String screen) throws Exception
    {
        HttpResponse<Buffer> res = api.get("/api/v1/notifications/badges", session);

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject().getJsonObject("badges").getInteger(screen, 0);
    }

    @Test
    void theMentorAnswersASessionRequestByLoggingASession() throws Exception
    {
        TestApi.Session priya = as("priya@pulse.demo");

        JsonObject request = find(pendingOf(priya), "Session requested", rohitPlan);

        assertTrue(request != null, pendingOf(priya).encodePrettily());

        String path = "/api/v1/training/assignments/" + rohitPlan + "/sessions";

        assertEquals(422, api.post(path, priya, session("Builds", TODAY)).statusCode());

        assertEquals(422, api.post(path, priya, session("Local build issues and how to debug them", TODAY.plusDays(1))).statusCode());

        assertEquals(404, api.post(path, as(RoleMatrix.PEER), session("Local build issues and how to debug them", TODAY)).statusCode());

        HttpResponse<Buffer> logged = api.post(path, priya, session("Local build issues and how to debug them", TODAY)
                .put("blocker", true).put("blockerNote", "Needs VPN access").put("requestId", request.getString("requestId")));

        assertEquals(200, logged.statusCode(), logged.bodyAsString());

        assertTrue(logged.bodyAsJsonObject().getJsonArray("sessions").getJsonObject(0).getBoolean("blocker"));

        assertEquals(null, find(pendingOf(priya), "Session requested", rohitPlan));
    }

    @Test
    void theTraineeAsksForASession() throws Exception
    {
        HttpResponse<Buffer> asked = api.post("/api/v1/training/assignments/" + rohitPlan + "/session-requests", as("rohit@pulse.demo"),
                new JsonObject().put("note", "Can we go over the on-call runbook?"));

        assertEquals(200, asked.statusCode(), asked.bodyAsString());

        assertTrue(pendingOf(as("priya@pulse.demo")).encode().contains("on-call runbook"));

        assertEquals(403, api.post("/api/v1/training/assignments/" + rohitPlan + "/session-requests", as("priya@pulse.demo"),
                new JsonObject().put("note", "Not mine to ask.")).statusCode());
    }

    @Test
    void aCheckpointGoesToSelfManagerAndMentorOnThe360Form() throws Exception
    {
        TestApi.Session rahul = as(RoleMatrix.MANAGER);

        HttpResponse<Buffer> requested = api.post("/api/v1/training/assignments/" + rohitPlan + "/checkpoints/14", rahul, new JsonObject());

        assertEquals(200, requested.statusCode(), requested.bodyAsString());

        assertEquals(409, api.post("/api/v1/training/assignments/" + rohitPlan + "/checkpoints/14", rahul, new JsonObject()).statusCode());

        assertEquals(422, api.post("/api/v1/training/assignments/" + rohitPlan + "/checkpoints/45", rahul, new JsonObject()).statusCode());

        assertEquals(3, api.count("SELECT count(*) FROM review_requests r JOIN review_cycles c ON c.id = r.cycle_id "
                + "WHERE c.kind = 'training' AND c.assignment_id = '" + rohitPlan + "' AND c.checkpoint_day = 14"));

        // The mentor sees it in To give (tagged with the day) and in Pending.
        JsonArray toGive = api.get("/api/v1/reviews/360/to-give", as("priya@pulse.demo")).bodyAsJsonObject().getJsonArray("items");

        assertTrue(toGive.encode().contains("\"kind\":\"training\"") && toGive.encode().contains("\"checkpointDay\":14"), toGive.encodePrettily());

        assertTrue(find(pendingOf(as("priya@pulse.demo")), "Checkpoint rating to give", rohitPlan) != null);

        // A 360° cycle can still be created while checkpoint cycles run.
        HttpResponse<Buffer> cycle = api.post("/api/v1/reviews/360/cycles", as(RoleMatrix.ADMIN), new JsonObject()
                .put("name", "Q4 2026 360° review").put("quarter", "Q4 2026").put("dueOn", TODAY.plusDays(30).toString()));

        assertEquals(201, cycle.statusCode(), cycle.bodyAsString());
    }

    @Test
    void managersSeeTheMentorsTable() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/training/mentors", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));

        String body = api.get("/api/v1/training/mentors", as(RoleMatrix.MANAGER)).bodyAsString();

        assertTrue(body.contains("Priya Shah") && body.contains("Sara Khan"), body);
    }

    @Test
    void aNewJoinerWithoutAPlanIsOfferedOnboarding() throws Exception
    {
        // Spec "New joiner suggestion": joined 2 months ago, no plan.
        api.exec("UPDATE people SET joined_on = current_date - 60 WHERE external_id = 'am'");

        JsonArray items = api.get("/api/v1/training/suggestions/onboarding", as(RoleMatrix.MANAGER)).bodyAsJsonObject().getJsonArray("items");

        JsonObject arjun = null;

        for (Object item : items)
        {
            if ("Arjun Mehta".equals(((JsonObject) item).getString("name")))
            {
                arjun = (JsonObject) item;
            }
        }

        assertTrue(arjun != null, items.encodePrettily());

        assertEquals("Software Engineer onboarding", arjun.getJsonObject("suggestedPlan").getString("name"));

        assertEquals("Assign", arjun.getString("action"));

        // Rohit joined recently too, but already has a plan.
        assertTrue(!items.encode().contains("Rohit Kulkarni"), items.encodePrettily());
    }

    // ------------------------------------------------------------------ notifications and badge

    @Test
    void sessionsAndCheckpointsNotifyTheRightPeople() throws Exception
    {
        long logged = notices("rohit@pulse.demo", "mentoring.session_logged");

        long blocked = notices(RoleMatrix.MANAGER, "mentoring.session_blocked");

        HttpResponse<Buffer> res = api.post("/api/v1/training/assignments/" + rohitPlan + "/sessions", as("priya@pulse.demo"),
                session("Release process and the deploy checklist", TODAY).put("blocker", true).put("blockerNote", "Needs prod access"));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        assertEquals(logged + 1, notices("rohit@pulse.demo", "mentoring.session_logged"));

        assertEquals(blocked + 1, notices(RoleMatrix.MANAGER, "mentoring.session_blocked"));

        long requested = notices("priya@pulse.demo", "mentoring.session_requested");

        assertEquals(200, api.post("/api/v1/training/assignments/" + rohitPlan + "/session-requests", as("rohit@pulse.demo"),
                new JsonObject().put("note", "Walk me through code review norms?")).statusCode());

        assertEquals(requested + 1, notices("priya@pulse.demo", "mentoring.session_requested"));

        long checkpoint = api.count("SELECT count(*) FROM notifications WHERE kind = 'training.checkpoint_requested'");

        assertEquals(200, api.post("/api/v1/training/assignments/" + rohitPlan + "/checkpoints/30", as(RoleMatrix.MANAGER),
                new JsonObject()).statusCode());

        assertEquals(checkpoint + 3, api.count("SELECT count(*) FROM notifications WHERE kind = 'training.checkpoint_requested'"));
    }

    @Test
    void theTrainingBadgeMatchesThePendingList() throws Exception
    {
        TestApi.Session priya = as("priya@pulse.demo");

        assertEquals(pendingOf(priya).size(), badge(priya, "training"));
    }
}
