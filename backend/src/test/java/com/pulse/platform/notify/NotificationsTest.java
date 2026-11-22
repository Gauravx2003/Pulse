package com.pulse.platform.notify;

import com.pulse.platform.policy.PolicyService;
import com.pulse.testsupport.TestApi;
import com.pulse.testsupport.TestApi.Session;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static com.pulse.testsupport.TestApi.DEMO_PASSWORD;
import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 5.3, spec platform/notifications: the bell endpoints (list, read, read all) on the demo seed, the
 * visibility and privacy rules of {@link NotificationService#notify}, a notification that rolls back with
 * its action, and the menu badges summed from module providers (the test-only probe module declares
 * them) for the screens in the caller's menu only.
 */
class NotificationsTest
{
    private static final int PORT = 18_160;

    private static TestApi api;

    private static NotificationService service;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"));
        service = new NotificationService(api.sql(), new PolicyService(api.sql(), Set.of(), List.of(), false));
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    // ------------------------------------------------------------------ bell

    @Test
    void theBellNeedsASession() throws Exception
    {
        assertEquals(401, api.get("/api/v1/notifications", null).statusCode());
        assertEquals(401, api.get("/api/v1/notifications/badges", null).statusCode());
    }

    @Test
    void eachUserSeesOnlyTheirOwnNotificationsNewestFirst() throws Exception
    {
        Session priya = api.signIn("priya@pulse.demo", DEMO_PASSWORD);

        JsonObject body = api.get("/api/v1/notifications", priya).bodyAsJsonObject();
        JsonArray items = body.getJsonArray("items");

        assertEquals(1, items.size(), "Priya's seeded 360° request");
        assertEquals("Share 360° feedback on Rohit Kulkarni · Rohit · Software Engineer onboarding · Day 14 · due 28 Sept",
                items.getJsonObject(0).getString("text"));
        assertEquals("/reviews?tab=360", items.getJsonObject(0).getString("link"));
        assertFalse(items.getJsonObject(0).getBoolean("read"));
        assertEquals(1, body.getInteger("unread"));

        Session rohit = api.signIn("rohit@pulse.demo", DEMO_PASSWORD);
        JsonArray rohits = api.get("/api/v1/notifications", rohit).bodyAsJsonObject().getJsonArray("items");

        assertTrue(rohits.size() > 1);

        for (int i = 1; i < rohits.size(); i++)
        {
            assertTrue(rohits.getJsonObject(i - 1).getString("createdAt").compareTo(rohits.getJsonObject(i).getString("createdAt")) >= 0,
                    "newest first");
        }

        assertEquals(2, api.get("/api/v1/notifications?limit=2", rohit).bodyAsJsonObject().getJsonArray("items").size());
        assertEquals(400, api.get("/api/v1/notifications?limit=many", rohit).statusCode());
    }

    @Test
    void openingAnItemMarksItReadAndOthersItemsAreNotFound() throws Exception
    {
        Session anjali = api.signIn("anjali@pulse.demo", DEMO_PASSWORD);
        Session priya = api.signIn("priya@pulse.demo", DEMO_PASSWORD);

        String id = deliver(NotificationTriggers.attendanceUploaded(userId("anjali@pulse.demo"), LocalDate.of(2026, 9, 1)));
        int before = api.get("/api/v1/notifications", anjali).bodyAsJsonObject().getInteger("unread");

        assertEquals(404, api.post("/api/v1/notifications/" + id + "/read", priya, new JsonObject()).statusCode(),
                "someone else's notification is not found");
        assertEquals(404, api.post("/api/v1/notifications/not-a-uuid/read", anjali, new JsonObject()).statusCode());

        HttpResponse<Buffer> res = api.post("/api/v1/notifications/" + id + "/read", anjali, new JsonObject());

        assertEquals(200, res.statusCode());
        assertEquals(before - 1, res.bodyAsJsonObject().getInteger("unread"));
        assertEquals(200, api.post("/api/v1/notifications/" + id + "/read", anjali, new JsonObject()).statusCode(), "idempotent");
    }

    @Test
    void markAllReadClearsTheCount() throws Exception
    {
        Session rohit = api.signIn("rohit@pulse.demo", DEMO_PASSWORD);

        assertTrue(api.get("/api/v1/notifications", rohit).bodyAsJsonObject().getInteger("unread") > 0);

        HttpResponse<Buffer> res = api.post("/api/v1/notifications/read-all", rohit, new JsonObject());

        assertEquals(200, res.statusCode());
        assertEquals(0, res.bodyAsJsonObject().getInteger("unread"));

        JsonObject after = api.get("/api/v1/notifications", rohit).bodyAsJsonObject();

        assertEquals(0, after.getInteger("unread"));
        after.getJsonArray("items").forEach(item -> assertTrue(((JsonObject) item).getBoolean("read")));
        assertEquals(1, api.get("/api/v1/notifications", api.signIn("priya@pulse.demo", DEMO_PASSWORD))
                .bodyAsJsonObject().getInteger("unread"), "other users keep their count");
    }

    @Test
    void writesNeedTheCsrfToken() throws Exception
    {
        Session sara = api.signIn("sara@pulse.demo", DEMO_PASSWORD);

        assertEquals(403, api.post("/api/v1/notifications/read-all", new Session(sara.cookie(), "forged", sara.body()),
                new JsonObject()).statusCode());
    }

    // ------------------------------------------------------------------ visibility and privacy

    @Test
    void lowPulseReachesTheManagerOnlyForTheirOwnReports() throws Exception
    {
        String rahul = userId("rahul@pulse.demo");

        JsonObject delivered = await(service.notify(api.sql(),
                NotificationTriggers.lowPulse(rahul, personId("priya@pulse.demo"), "Priya Shah", LocalDate.of(2026, 9, 21))));

        assertNotNull(delivered, "Priya reports to Rahul");
        assertEquals(NotificationTriggers.LOW_PULSE, delivered.getString("kind"));

        assertNull(await(service.notify(api.sql(),
                        NotificationTriggers.lowPulse(userId("arjun@pulse.demo"), personId("priya@pulse.demo"), "Priya Shah",
                                LocalDate.of(2026, 9, 21)))),
                "a peer sees nothing about Priya");
        assertNotNull(await(service.notify(api.sql(),
                        NotificationTriggers.lowPulse(userId("hr@pulse.demo"), personId("kiran@pulse.demo"), "Kiran Rao",
                                LocalDate.of(2026, 9, 21)))),
                "Admin sees everyone");
        assertEquals(0, api.count("SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id "
                + "WHERE n.kind = 'pulse.low' AND u.email = 'arjun@pulse.demo'"), "nothing stored for the peer");
    }

    @Test
    void deactivatedRecipientsGetNothing() throws Exception
    {
        api.exec("UPDATE users SET status = 'Deactivated' WHERE email = 'rohan@pulse.demo'");

        try
        {
            assertNull(await(service.notify(api.sql(), NotificationTriggers.staleNudge(userId("rohan@pulse.demo"), "OPS-1", 6))));
        }
        finally
        {
            api.exec("UPDATE users SET status = 'Active' WHERE email = 'rohan@pulse.demo'");
        }
    }

    @Test
    void textWithSalaryValuesOrAnExternalLinkIsRefused() throws Exception
    {
        String kiran = userId("kiran@pulse.demo");

        JsonObject salary = new JsonObject().put("userId", kiran).put("kind", "appraisal.approved")
                .put("text", "Your increment of ₹1,20,000 was approved").put("link", "/appraisal");
        JsonObject external = new JsonObject().put("userId", kiran).put("kind", "x").put("text", "Open this")
                .put("link", "https://example.com");

        assertInstanceOf(IllegalArgumentException.class, failure(service.notify(api.sql(), salary)));
        assertInstanceOf(IllegalArgumentException.class, failure(service.notify(api.sql(), external)));
        assertEquals(0, api.count("SELECT count(*) FROM notifications WHERE user_id = '" + kiran + "' AND kind IN ('appraisal.approved', 'x')"));
    }

    @Test
    void aNotificationRollsBackWithItsAction() throws Exception
    {
        String dev = userId("dev@pulse.demo");

        assertInstanceOf(IllegalStateException.class, failure(api.sql().inTransaction(tx ->
                service.notify(tx, NotificationTriggers.staleNudge(dev, "PLAT-999", 7))
                        .compose(n -> Future.failedFuture(new IllegalStateException("action failed"))))));

        assertEquals(0, api.count("SELECT count(*) FROM notifications WHERE text LIKE 'Nudge: PLAT-999%'"));

        await(api.sql().inTransaction(tx -> service.notify(tx, NotificationTriggers.staleNudge(dev, "PLAT-998", 7))));

        assertEquals(1, api.count("SELECT count(*) FROM notifications WHERE text LIKE 'Nudge: PLAT-998%'"));
    }

    // ------------------------------------------------------------------ badges

    @Test
    void badgesAddUpProvidersForScreensInTheMenuAndSkipFailures() throws Exception
    {
        JsonObject priya = api.get("/api/v1/notifications/badges", api.signIn("priya@pulse.demo", DEMO_PASSWORD))
                .bodyAsJsonObject().getJsonObject("badges");

        assertEquals(5, priya.getInteger("work-items"), "two providers for Work items add up");
        assertFalse(priya.containsKey("effort"), "a failing provider shows no badge");
        assertFalse(priya.containsKey("actions"), "the Action center is not in an Employee's menu");

        JsonObject admin = api.get("/api/v1/notifications/badges", api.signIn("hr@pulse.demo", DEMO_PASSWORD))
                .bodyAsJsonObject().getJsonObject("badges");

        assertEquals(5, admin.getInteger("work-items"), "Anita sees Work items as an organisation viewer");

        JsonObject meera = api.get("/api/v1/notifications/badges", api.signIn("sana@pulse.demo", DEMO_PASSWORD))
                .bodyAsJsonObject().getJsonObject("badges");

        assertEquals(new JsonObject(), meera, "no badge for screens outside the menu");
    }

    /** The failure of a future that must fail. */
    private static Throwable failure(Future<?> future)
    {
        ExecutionException e = assertThrows(ExecutionException.class, () -> await(future));

        return e.getCause();
    }

    private static String deliver(JsonObject notice) throws Exception
    {
        JsonObject row = await(service.notify(api.sql(), notice));

        assertNotNull(row);

        return row.getString("id");
    }

    private static String userId(String email) throws Exception
    {
        return api.queryOne("SELECT id::text AS id FROM users WHERE email = '" + email + "'").getString("id");
    }

    private static String personId(String email) throws Exception
    {
        return api.queryOne("SELECT p.id::text AS id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = '" + email + "'")
                .getString("id");
    }
}
