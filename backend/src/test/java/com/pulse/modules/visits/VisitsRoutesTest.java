package com.pulse.modules.visits;

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

/** Spec time/customer-visits (task 18.1), through the real server with the demo seed. */
class VisitsRoutesTest
{
    private static final int PORT = 18_320;

    private static final String SANA = "sana@pulse.demo";

    private static final String KAVYA = "kavya@pulse.demo";

    private static TestApi api;

    private static WebClient client;

    private static String priyaPerson;

    private static String sanaUser;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);

        for (String script : new String[] {"modules/visits/migrations/V320__visits.sql", "modules/visits/seed/S320__demo_visits.sql"})
        {
            try (InputStream in = VisitsRoutesTest.class.getClassLoader().getResourceAsStream(script))
            {
                api.exec(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        priyaPerson = api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = 'priya@pulse.demo'")
                .getString("id");

        sanaUser = api.queryOne("SELECT id FROM users WHERE email = 'sana@pulse.demo'").getString("id");

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

    private static JsonObject visit(String from, String to)
    {
        return new JsonObject()
                .put("personId", priyaPerson)
                .put("customer", "Initech")
                .put("city", "Pune")
                .put("purpose", "Escalation")
                .put("from", from)
                .put("to", to)
                .put("requestedBy", "Sana Mirza · account manager")
                .put("ownerId", sanaUser)
                .put("reference", "DEAL-3150");
    }

    private static JsonObject ok(HttpResponse<Buffer> res, int status)
    {
        assertEquals(status, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static String code(HttpResponse<Buffer> res)
    {
        return TestApi.error(res).getString("code");
    }

    private static JsonObject report()
    {
        return new JsonObject()
                .put("done", "Fixed leader election and re-ran the drill.")
                .put("onsiteHours", 14)
                .put("travelHours", 5)
                .put("outcome", "Resolved")
                .put("contact", "CIO office");
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
    void visitsFollowVisibility() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/visits/items?personId={person:priya@pulse.demo}", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 403));
    }

    @Test
    void tooLongAndOverlappingVisitsAreRefused() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        HttpResponse<Buffer> tooLong = api.post("/api/v1/visits/items", rahul, visit("2027-02-01", "2027-02-15"));

        assertEquals(422, tooLong.statusCode());

        assertEquals("A visit can be at most 14 days.", TestApi.error(tooLong).getString("message"));

        // VIS-104: Priya at Acme Retail on 15-16 Sep 2026.
        HttpResponse<Buffer> overlap = api.post("/api/v1/visits/items", rahul, visit("2026-09-16", "2026-09-17"));

        assertEquals(422, overlap.statusCode());

        assertEquals("visit_overlap", code(overlap));

        assertTrue(TestApi.error(overlap).getString("message").contains("Acme Retail"), overlap.bodyAsString());
    }

    @Test
    void lifecycleRunsInOrderAndOutOfOrderActionsAreRefused() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        TestApi.Session sana = RoleMatrix.session(api, SANA);

        String last = "2026-06-12";

        String first = "2026-06-10";

        // The engineer logs a past visit: it is due a report.
        JsonObject logged = ok(api.post("/api/v1/visits/items", priya, visit(first, last)), 201);

        assertEquals("report_due", logged.getString("status"));

        String id = logged.getString("id");

        // Spec "Out-of-order action": verifying a visit with no report is refused.
        HttpResponse<Buffer> early = api.post("/api/v1/visits/items/" + id + "/verify", rahul, new JsonObject().put("version", 1));

        assertEquals(409, early.statusCode());

        assertEquals("out_of_order", code(early));

        // Spec "Missing fields": no hours on site.
        JsonObject incomplete = report().put("version", 1);

        incomplete.remove("onsiteHours");

        HttpResponse<Buffer> missing = api.post("/api/v1/visits/items/" + id + "/report", priya, incomplete);

        assertEquals(400, missing.statusCode());

        assertEquals("onsiteHours", TestApi.error(missing).getString("field"));

        JsonObject reported = ok(api.post("/api/v1/visits/items/" + id + "/report", priya, report().put("version", 1)), 200);

        assertEquals("feedback_pending", reported.getString("status"));

        // Spec order: verification waits for the customer's feedback.
        assertEquals(409, api.post("/api/v1/visits/items/" + id + "/verify", rahul, new JsonObject().put("version", 2)).statusCode());

        JsonObject withFeedback = ok(api.post("/api/v1/visits/items/" + id + "/feedback", sana, new JsonObject()
                .put("csat", 4.8).put("askAgain", true).put("source", "Customer call").put("feedbackOn", "2026-06-15")
                .put("quote", "Stayed until it worked.").put("version", 2)), 200);

        assertEquals("awaiting_manager", withFeedback.getString("status"));

        // The engineer cannot verify (no effort.confirm); the manager can.
        assertEquals(403, api.post("/api/v1/visits/items/" + id + "/verify", priya, new JsonObject().put("version", 3)).statusCode());

        JsonObject verified = ok(api.post("/api/v1/visits/items/" + id + "/verify", rahul, new JsonObject().put("version", 3)), 200);

        assertEquals("verified", verified.getString("status"));

        // Points from calc/people: escalation + 2 extra days + CSAT 4.8 (weekend days depend on today's date).
        double total = verified.getJsonObject("points").getDouble("total");

        assertTrue(total >= 1.5 + 1.0 + 0.5 + 1, verified.encodePrettily());

        assertTrue(verified.getJsonArray("values").contains("Resilience"), verified.encodePrettily());

        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'visit.verify' AND outcome = 'success'"));

        // A reported visit can no longer be deleted.
        HttpResponse<Buffer> delete = await(client.delete(PORT, "localhost", "/api/v1/visits/items/" + id)
                .putHeader("X-Real-IP", TestApi.newIp())
                .putHeader("Cookie", priya.cookie())
                .putHeader(SessionHandler.CSRF_HEADER, priya.csrf())
                .putHeader("If-Match", "4")
                .send());

        assertEquals(409, delete.statusCode());
    }

    @Test
    void accountOwnersSeeAndRecordFeedbackOnlyForTheirOwnVisits() throws Exception
    {
        TestApi.Session sana = RoleMatrix.session(api, SANA);

        TestApi.Session kavya = RoleMatrix.session(api, KAVYA);

        JsonArray owned = ok(api.get("/api/v1/visits/items?owned=true&from=2026-01-01&to=2026-12-31", sana), 200).getJsonArray("items");

        for (Object item : owned)
        {
            assertEquals("Sana Mirza", ((JsonObject) item).getString("ownerName"));
        }

        // VIS-105 (Dev at Globex): reported, no feedback yet, owned by Sana.
        String vis105 = api.queryOne("SELECT id FROM visits WHERE external_id = 'VIS-105'").getString("id");

        HttpResponse<Buffer> notOwner = api.post("/api/v1/visits/items/" + vis105 + "/feedback", kavya, new JsonObject()
                .put("csat", 5).put("askAgain", true).put("source", "Email").put("feedbackOn", "2026-09-20").put("version", 1));

        // Spec "Not the owner": access is refused (the visit is not even visible to Kavya).
        assertEquals(404, notOwner.statusCode());

        JsonObject detail = ok(api.get("/api/v1/visits/items/" + vis105, sana), 200);

        assertEquals("feedback_pending", detail.getString("status"));

        assertTrue(detail.getJsonArray("actions").contains("feedback"), detail.encodePrettily());
    }

    @Test
    void plannedVisitsCanBeEditedAndDeletedBeforeTheReport() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        JsonObject planned = ok(api.post("/api/v1/visits/items", rahul, visit("2027-03-01", "2027-03-02")), 201);

        assertEquals("planned", planned.getString("status"));

        assertTrue(planned.getJsonArray("actions").contains("edit"));

        // Not started yet: the engineer cannot report.
        HttpResponse<Buffer> tooSoon = api.post("/api/v1/visits/items/" + planned.getString("id") + "/report", priya,
                report().put("version", 1));

        assertEquals(422, tooSoon.statusCode());

        JsonObject changes = visit("2027-03-02", "2027-03-04").put("version", 1);

        changes.remove("personId");

        JsonObject edited = ok(api.put("/api/v1/visits/items/" + planned.getString("id"), rahul, changes), 200);

        assertEquals(3, edited.getInteger("days"));

        HttpResponse<Buffer> delete = await(client.delete(PORT, "localhost", "/api/v1/visits/items/" + planned.getString("id"))
                .putHeader("X-Real-IP", TestApi.newIp())
                .putHeader("Cookie", priya.cookie())
                .putHeader(SessionHandler.CSRF_HEADER, priya.csrf())
                .putHeader("If-Match", "2")
                .send());

        assertEquals(204, delete.statusCode(), delete.bodyAsString());

        assertEquals(0, api.count("SELECT count(*) FROM visits WHERE id = '" + planned.getString("id") + "'"));
    }

    @Test
    void peersCannotPlanForEachOther() throws Exception
    {
        HttpResponse<Buffer> res = api.post("/api/v1/visits/items", RoleMatrix.session(api, RoleMatrix.PEER), visit("2027-04-01", "2027-04-01"));

        assertEquals(403, res.statusCode());
    }

    @Test
    void statsComeFromTheCalculationService() throws Exception
    {
        // Priya in Q3 2026 (seed): VIS-102 health check on a Thursday, CSAT 4 (1.5 points) and VIS-104
        // troubleshooting Tuesday-Wednesday, CSAT 5 (1.5 + 0.5 + 1 = 3), both verified.
        HttpResponse<Buffer> res = api.get("/api/v1/visits/stats?personId=" + priyaPerson + "&period=q:2026-Q3",
                RoleMatrix.session(api, RoleMatrix.MANAGER));

        JsonObject stats = ok(res, 200);

        assertEquals(2, stats.getInteger("reported"), stats.encodePrettily());

        assertEquals(4.5, stats.getDouble("points"));

        assertEquals(4.5, stats.getDouble("pointsConfirmed"));

        assertEquals(4.5, stats.getDouble("csat"));

        assertEquals(403, api.get("/api/v1/visits/stats?personId=" + priyaPerson, RoleMatrix.session(api, RoleMatrix.PEER)).statusCode());

        assertEquals(400, api.get("/api/v1/visits/stats?personId=" + priyaPerson + "&period=nonsense",
                RoleMatrix.session(api, RoleMatrix.MANAGER)).statusCode());
    }

    // ------------------------------------------------------------------ notifications and badge

    @Test
    void theEngineerHearsOfPlannedVisitsAndTheManagerRemindsOfOverdueReports() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        long planned = notices(RoleMatrix.EMPLOYEE, "visit.planned");

        // The manager plans a visit for the engineer: the engineer is told.
        ok(api.post("/api/v1/visits/items", rahul, visit("2026-11-09", "2026-11-10")), 201);

        assertEquals(planned + 1, notices(RoleMatrix.EMPLOYEE, "visit.planned"));

        // The engineer logs their own past visit: no "planned" notice to themselves.
        String id = ok(api.post("/api/v1/visits/items", priya, visit("2026-06-22", "2026-06-23")), 201).getString("id");

        assertEquals(planned + 1, notices(RoleMatrix.EMPLOYEE, "visit.planned"));

        long due = notices(RoleMatrix.EMPLOYEE, "visit.report_due");

        assertEquals(403, api.post("/api/v1/visits/items/" + id + "/remind", priya, new JsonObject()).statusCode());

        HttpResponse<Buffer> reminded = api.post("/api/v1/visits/items/" + id + "/remind", rahul, new JsonObject());

        assertEquals(200, reminded.statusCode(), reminded.bodyAsString());

        assertEquals(due + 1, notices(RoleMatrix.EMPLOYEE, "visit.report_due"));
    }

    @Test
    void theVisitsBadgeCountsTheEngineersOverdueReports() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        HttpResponse<Buffer> list = api.get("/api/v1/visits/items", priya);

        assertEquals(200, list.statusCode(), list.bodyAsString());

        long overdue = list.bodyAsJsonObject().getJsonArray("items").stream()
                .map(x -> (JsonObject) x)
                .filter(v -> priyaPerson.equals(v.getString("personId")) && "report_due".equals(v.getString("status"))).count();

        assertTrue(overdue > 0, list.bodyAsString());

        // Priya owns no accounts and verifies nothing, so her badge is her overdue reports.
        assertEquals(overdue, badge(priya, "visits"));
    }

    @Test
    void theTeamViewListsEveryoneAndAveragesWithoutNewJoiners() throws Exception
    {
        // Spec "Person with no visits": the team table lists people with no visits too.
        HttpResponse<Buffer> res = api.get("/api/v1/visits/team?period=all", RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject team = res.bodyAsJsonObject();

        boolean withoutVisits = false;

        for (Object o : team.getJsonArray("people"))
        {
            JsonObject p = (JsonObject) o;

            withoutVisits |= p.getJsonObject("stats").getInteger("visits", 0) == 0;
        }

        assertTrue(withoutVisits, team.encodePrettily());

        assertTrue(team.getJsonObject("average").containsKey("points"));

        assertEquals(403, api.get("/api/v1/visits/team", RoleMatrix.session(api, RoleMatrix.EMPLOYEE)).statusCode());

        assertEquals(400, api.get("/api/v1/visits/team?period=nonsense", RoleMatrix.session(api, RoleMatrix.MANAGER)).statusCode());
    }
}
