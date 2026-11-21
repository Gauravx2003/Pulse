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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec growth/training-mentoring "Assignment", "Lifecycle order", "Check-ins", "Assessment and
 * reviews", "Sign-off and acknowledgement" (task 21.1 part B), with the demo seed.
 */
class TrainingAssignmentsRoutesTest
{
    private static final int PORT = 18_351;

    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);

        for (String script : new String[] {"modules/training/migrations/V350__training.sql", "modules/training/seed/S350__demo_training.sql"})
        {
            try (InputStream in = TrainingAssignmentsRoutesTest.class.getClassLoader().getResourceAsStream(script))
            {
                api.exec(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        // The platform's test-only ProbeModule declares a training-domain "mentor" relation over
        // probe_mentors, which only PolicyServiceTest creates; every training scope runs it.
        api.exec("CREATE TABLE IF NOT EXISTS probe_mentors (mentor_id uuid NOT NULL, mentee_id uuid NOT NULL)");
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    private static String person(String email) throws Exception
    {
        return api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = '" + email + "'").getString("id");
    }

    private static String user(String email) throws Exception
    {
        return api.queryOne("SELECT id FROM users WHERE email = '" + email + "'").getString("id");
    }

    private static String plan(String externalId) throws Exception
    {
        return api.queryOne("SELECT id FROM training_plans WHERE external_id = '" + externalId + "'").getString("id");
    }

    private static TestApi.Session as(String email) throws Exception
    {
        return RoleMatrix.session(api, email);
    }

    private static JsonObject assign(String traineeEmail, String planExternalId, String mentorEmail, LocalDate start) throws Exception
    {
        HttpResponse<Buffer> res = api.post("/api/v1/training/assignments", as(RoleMatrix.MANAGER), new JsonObject()
                .put("personIds", JsonArray.of(person(traineeEmail)))
                .put("planIds", JsonArray.of(plan(planExternalId)))
                .put("mentorId", user(mentorEmail))
                .put("startOn", start.toString()));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static JsonObject detail(TestApi.Session session, String id) throws Exception
    {
        HttpResponse<Buffer> res = api.get("/api/v1/training/assignments/" + id, session);

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static HttpResponse<Buffer> post(TestApi.Session session, String path, JsonObject body) throws Exception
    {
        return api.post("/api/v1/training" + path, session, body);
    }

    private static String code(HttpResponse<Buffer> res)
    {
        return TestApi.error(res).getString("code");
    }

    private static JsonObject review(String role, Integer rating)
    {
        return new JsonObject().put("role", role).put("rating", rating)
                .put("good", "Clear and careful work throughout.").put("improve", "Ask for help a little earlier.");
    }

    // ------------------------------------------------------------------ access and assignment

    @Test
    void mentorsSeeOnlyTheirMenteesTraining() throws Exception
    {
        // ta69fv: Kiran's plan, mentored by Sara (a peer, not Kiran's manager).
        String id = api.queryOne("SELECT id FROM training_assignments WHERE external_id = 'ta69fv'").getString("id");

        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/training/assignments/" + id, Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 404,      // priya@: neither Kiran nor his mentor
                RoleMatrix.PEER, 404));

        JsonObject asMentor = detail(as("sara@pulse.demo"), id);

        assertTrue(asMentor.getJsonArray("roles").contains("mentor"));
    }

    @Test
    void peopleAlreadyOnAPlanAreSkippedAndNobodyMentorsThemselves() throws Exception
    {
        LocalDate start = TODAY.plusDays(7);

        JsonObject first = assign("dev@pulse.demo", "tp4", "priya@pulse.demo", start);

        assertEquals(1, first.getJsonArray("assigned").size(), first.encodePrettily());

        // Spec "Already assigned": skipped and the result says so.
        JsonObject again = assign("dev@pulse.demo", "tp4", "priya@pulse.demo", start);

        assertEquals(0, again.getJsonArray("assigned").size());

        assertTrue(again.getJsonArray("skipped").encode().contains("Already on this plan"), again.encodePrettily());

        JsonObject self = assign("priya@pulse.demo", "tp4", "priya@pulse.demo", start);

        assertTrue(self.getJsonArray("skipped").encode().contains("can't mentor themselves"), self.encodePrettily());

        assertEquals("Not started", detail(as(RoleMatrix.MANAGER), first.getJsonArray("assigned").getJsonObject(0).getString("id"))
                .getString("stage"));
    }

    @Test
    void aHeavyLoadIsAWarningNotABlock() throws Exception
    {
        HttpResponse<Buffer> heavy = api.post("/api/v1/training/plans", as(RoleMatrix.MANAGER), new JsonObject()
                .put("name", "Crash course").put("weeks", 1)
                .put("modules", JsonArray.of(new JsonObject().put("title", "Everything").put("type", "Course").put("week", 1).put("hours", 30))));

        assertEquals(201, heavy.statusCode(), heavy.bodyAsString());

        HttpResponse<Buffer> res = api.post("/api/v1/training/assignments", as(RoleMatrix.MANAGER), new JsonObject()
                .put("personIds", JsonArray.of(person("neha@pulse.demo")))
                .put("planIds", JsonArray.of(heavy.bodyAsJsonObject().getString("id")))
                .put("mentorId", user("priya@pulse.demo"))
                .put("startOn", TODAY.plusDays(30).toString()));

        assertEquals(1, res.bodyAsJsonObject().getJsonArray("assigned").size());

        assertTrue(res.bodyAsJsonObject().getJsonArray("warnings").encode().contains("more than 16"), res.bodyAsString());
    }

    // ------------------------------------------------------------------ check-ins

    @Test
    void checkInsAreSubmittedReviewedAndResent() throws Exception
    {
        String id = assign("sara@pulse.demo", "tp1", "priya@pulse.demo", TODAY.minusDays(10)).getJsonArray("assigned")
                .getJsonObject(0).getString("id");

        TestApi.Session sara = as("sara@pulse.demo");

        TestApi.Session priya = as("priya@pulse.demo");

        JsonObject a = detail(sara, id);

        assertEquals("In training", a.getString("stage"));

        String period = a.getJsonArray("periods").getJsonObject(0).getString("key");

        JsonObject checkIn = new JsonObject().put("periodStart", period).put("done", "Set up the laptop and read the handbook.").put("hours", 9)
                .put("confidence", 3);

        assertEquals(200, post(sara, "/assignments/" + id + "/checkins", checkIn).statusCode());

        assertEquals(404, post(as(RoleMatrix.PEER), "/assignments/" + id + "/checkins", checkIn).statusCode());

        assertEquals(422, post(sara, "/assignments/" + id + "/checkins", checkIn.copy().put("periodStart", TODAY.plusDays(60).toString()))
                .statusCode());

        JsonObject submitted = detail(priya, id).getJsonArray("checkIns").getJsonObject(0);

        // Spec "Needs work without comment": the form requires a comment.
        HttpResponse<Buffer> noComment = post(priya, "/checkins/" + submitted.getString("id") + "/review",
                new JsonObject().put("decision", "Needs work").put("version", submitted.getInteger("version")));

        assertEquals(422, noComment.statusCode());

        assertEquals("comment_required", code(noComment));

        assertEquals(200, post(priya, "/checkins/" + submitted.getString("id") + "/review", new JsonObject().put("decision", "Needs work")
                .put("comment", "Add the hours per module.").put("version", submitted.getInteger("version"))).statusCode());

        // Resend after Needs work, then verified.
        assertEquals(200, post(sara, "/assignments/" + id + "/checkins", checkIn.copy().put("done", "Laptop, handbook, 3 h each.")).statusCode());

        JsonObject resent = detail(priya, id).getJsonArray("checkIns").getJsonObject(0);

        assertEquals("Submitted", resent.getString("status"));

        assertEquals(200, post(priya, "/checkins/" + resent.getString("id") + "/review", new JsonObject().put("decision", "Verified")
                .put("comment", "Good detail now.").put("version", resent.getInteger("version"))).statusCode());

        assertEquals(409, post(sara, "/assignments/" + id + "/checkins", checkIn).statusCode());

        // The comment is feedback to acknowledge; disagreeing needs a comment, the mentor may reply.
        JsonObject latest = detail(sara, id);

        assertTrue(latest.getJsonArray("feedbackKeys").contains("ci:" + period), latest.encodePrettily());

        assertEquals(422, post(sara, "/assignments/" + id + "/acknowledgements", new JsonObject().put("key", "ci:" + period)
                .put("agree", "Disagreed").put("version", latest.getInteger("version"))).statusCode());

        assertEquals(200, post(sara, "/assignments/" + id + "/acknowledgements", new JsonObject().put("key", "ci:" + period)
                .put("agree", "Disagreed").put("comment", "I logged hours in Jira already.").put("version", latest.getInteger("version")))
                .statusCode());

        assertEquals(200, post(priya, "/assignments/" + id + "/acknowledgements/ci:" + period + "/reply", new JsonObject()
                .put("text", "Fair, let's link Jira then.").put("version", latest.getInteger("version") + 1)).statusCode());
    }

    // ------------------------------------------------------------------ assessment, reviews, sign-off

    @Test
    void aFailedAssessmentCannotBeCompletedAndExtendKeepsHistory() throws Exception
    {
        // tp5 is 2 weeks: started 60 days ago, the training period is over (stage Assessment).
        String id = assign("neha@pulse.demo", "tp5", "priya@pulse.demo", TODAY.minusDays(60)).getJsonArray("assigned")
                .getJsonObject(0).getString("id");

        TestApi.Session priya = as("priya@pulse.demo");

        TestApi.Session rahul = as(RoleMatrix.MANAGER);

        JsonObject a = detail(rahul, id);

        assertEquals("Assessment", a.getString("stage"));

        // Out of order: no final review before the assessment.
        assertEquals(409, post(rahul, "/assignments/" + id + "/final-review", review("manager", 3).put("decision", "Completed")
                .put("version", a.getInteger("version"))).statusCode());

        assertEquals(200, post(priya, "/assignments/" + id + "/assessment", new JsonObject().put("date", TODAY.toString()).put("score", 55)
                .put("notes", "Labels were right but half the comments gave no reason.").put("version", a.getInteger("version"))).statusCode());

        a = detail(rahul, id);

        assertEquals("Final review", a.getString("stage"));

        assertEquals("Fail", a.getJsonObject("assessment").getString("result"));

        assertEquals(200, post(priya, "/assignments/" + id + "/final-review", review("mentor", 3).put("version", a.getInteger("version")))
                .statusCode());

        int version = detail(rahul, id).getInteger("version");

        // Spec "Failed assessment": Completed returns 422.
        HttpResponse<Buffer> completed = post(rahul, "/assignments/" + id + "/final-review", review("manager", 3).put("decision", "Completed")
                .put("version", version));

        assertEquals(422, completed.statusCode());

        assertEquals("failed_assessment", code(completed));

        // Spec "Reason required".
        HttpResponse<Buffer> noReason = post(rahul, "/assignments/" + id + "/final-review", review("manager", 3).put("decision", "Not completed")
                .put("version", version));

        assertEquals(422, noReason.statusCode());

        assertEquals("reason_required", code(noReason));

        HttpResponse<Buffer> extend = post(rahul, "/assignments/" + id + "/final-review", review("manager", 3).put("decision", "Extend")
                .put("reason", "Needs another round of real reviews.").put("newEnd", TODAY.plusDays(14).toString()).put("version", version));

        assertEquals(200, extend.statusCode(), extend.bodyAsString());

        JsonObject extended = extend.bodyAsJsonObject();

        assertEquals("In training", extended.getString("stage"));

        assertEquals(2, extended.getInteger("attempt"));

        assertEquals(1, extended.getJsonArray("history").size());

        assertTrue(extended.getJsonArray("feedbackKeys").contains("as:1"));
    }

    @Test
    void signOffEndsTheTrainingAndADisagreementFlagsTheManager() throws Exception
    {
        TestApi.Session rahul = as(RoleMatrix.MANAGER);

        TestApi.Session priya = as("priya@pulse.demo");

        TestApi.Session anjali = as("anjali@pulse.demo");

        String id = assign("anjali@pulse.demo", "tp4", "priya@pulse.demo", TODAY.minusDays(40)).getJsonArray("assigned")
                .getJsonObject(0).getString("id");

        int v = detail(rahul, id).getInteger("version");

        assertEquals(200, post(rahul, "/assignments/" + id + "/assessment", new JsonObject().put("date", TODAY.toString()).put("score", 80)
                .put("notes", "Clear options and a rollback plan in the ADR.").put("version", v)).statusCode());

        assertEquals(200, post(priya, "/assignments/" + id + "/final-review", review("mentor", 4).put("version", v + 1)).statusCode());

        assertEquals(200, post(rahul, "/assignments/" + id + "/final-review", review("manager", 4).put("decision", "Not completed")
                .put("reason", "The presentation was not given yet.").put("version", v + 2)).statusCode());

        JsonObject a = detail(anjali, id);

        assertEquals("Trainee sign-off", a.getString("stage"));

        assertTrue(a.getJsonArray("actions").contains("signOff"));

        // Spec "Disagree": a comment is required and the manager is flagged.
        assertEquals(422, post(anjali, "/assignments/" + id + "/sign-off", new JsonObject().put("agree", "Disagreed")
                .put("planRating", 3).put("mentorRating", 5).put("version", a.getInteger("version"))).statusCode());

        HttpResponse<Buffer> signed = post(anjali, "/assignments/" + id + "/sign-off", new JsonObject().put("agree", "Disagreed")
                .put("comment", "I gave the presentation on Friday.").put("planRating", 3).put("mentorRating", 5)
                .put("version", a.getInteger("version")));

        assertEquals(200, signed.statusCode(), signed.bodyAsString());

        assertEquals("Not completed", signed.bodyAsJsonObject().getString("stage"));

        JsonObject managerView = detail(rahul, id);

        assertEquals(true, managerView.getBoolean("disagreementFlagged"));

        assertTrue(managerView.getJsonArray("actions").contains("markDiscussed"));

        assertEquals(200, post(rahul, "/assignments/" + id + "/discussed", new JsonObject().put("version", managerView.getInteger("version")))
                .statusCode());

        assertEquals(false, detail(rahul, id).getBoolean("disagreementFlagged"));
    }
}
