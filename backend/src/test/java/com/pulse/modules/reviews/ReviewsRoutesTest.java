package com.pulse.modules.reviews;

import com.pulse.platform.ingest.IngestService;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec culture/one-on-ones-kudos (task 20.2), through the real server with the demo seed. */
class ReviewsRoutesTest
{
    private static final int PORT = 18_330;

    private static TestApi api;

    private static String priyaPerson;

    private static String priyaUser;

    private static String nehaPerson;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);

        // TestApi applies the platform scripts only; add this module's schema and demo data.
        for (String script : new String[] {"modules/reviews/migrations/V330__one_on_ones_kudos.sql",
                "modules/reviews/seed/S330__demo_one_on_ones_kudos.sql"})
        {
            try (InputStream in = ReviewsRoutesTest.class.getClassLoader().getResourceAsStream(script))
            {
                api.exec(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        priyaPerson = api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = 'priya@pulse.demo'")
                .getString("id");

        priyaUser = api.queryOne("SELECT id FROM users WHERE email = 'priya@pulse.demo'").getString("id");

        nehaPerson = api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = 'neha@pulse.demo'")
                .getString("id");
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    private static JsonObject created(HttpResponse<Buffer> res)
    {
        assertEquals(201, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static String code(HttpResponse<Buffer> res)
    {
        return TestApi.error(res).getString("code");
    }

    // ------------------------------------------------------------------ 1:1s

    @Test
    void notesArePrivateToTheTwoPeopleAndHr() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/reviews/one-on-ones?personId={person:priya@pulse.demo}", Map.of(
                RoleMatrix.ADMIN, 200,       // hr@: all.view
                RoleMatrix.MANAGER, 200,     // rahul@: Priya's manager
                RoleMatrix.EMPLOYEE, 200,    // priya@: herself
                RoleMatrix.PEER, 403));      // arjun@: spec "Peer access"
    }

    @Test
    void pastOneOnOnesListNotesAndActionItemsNewestFirst() throws Exception
    {
        HttpResponse<Buffer> res = api.get("/api/v1/reviews/one-on-ones?personId=" + priyaPerson,
                RoleMatrix.session(api, RoleMatrix.EMPLOYEE));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonArray items = res.bodyAsJsonObject().getJsonArray("items");

        assertTrue(items.size() >= 2);

        JsonObject seeded = null;

        for (Object item : items)
        {
            if ("2026-09-15".equals(((JsonObject) item).getString("heldOn")))
            {
                seeded = (JsonObject) item;
            }
        }

        assertTrue(seeded != null, items.encodePrettily());

        assertEquals(2, seeded.getJsonArray("actions").size());

        assertEquals("Rahul Nair", seeded.getString("managerName"));
    }

    @Test
    void bothParticipantsEditNotesWithOptimisticLockingAndHrOnlyReads() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        JsonObject meeting = created(api.post("/api/v1/reviews/one-on-ones", rahul, new JsonObject()
                .put("personId", priyaPerson).put("heldOn", "2026-10-01").put("agenda", JsonArray.of("Workload", "Growth"))));

        String path = "/api/v1/reviews/one-on-ones/" + meeting.getString("id") + "/notes";

        HttpResponse<Buffer> byPriya = api.put(path, priya, new JsonObject().put("notes", "Talked about the incident.").put("version", 1));

        assertEquals(200, byPriya.statusCode(), byPriya.bodyAsString());

        assertEquals(2, byPriya.bodyAsJsonObject().getInteger("version"));

        HttpResponse<Buffer> stale = api.put(path, rahul, new JsonObject().put("notes", "Overwrite").put("version", 1));

        assertEquals(409, stale.statusCode());

        assertEquals("version_conflict", code(stale));

        HttpResponse<Buffer> byRahul = api.put(path, rahul, new JsonObject().put("notes", "Agreed next steps.").put("version", 2));

        assertEquals(200, byRahul.statusCode(), byRahul.bodyAsString());

        HttpResponse<Buffer> byHr = api.put(path, RoleMatrix.session(api, RoleMatrix.ADMIN),
                new JsonObject().put("notes", "HR edit").put("version", 3));

        assertEquals(403, byHr.statusCode());

        HttpResponse<Buffer> byPeer = api.put(path, RoleMatrix.session(api, RoleMatrix.PEER),
                new JsonObject().put("notes", "Peer edit").put("version", 3));

        assertEquals(404, byPeer.statusCode());

        assertTrue(api.count("SELECT count(*) FROM audit_log WHERE action = 'oneonone.notes_updated'") >= 2);
    }

    @Test
    void longNoteIsRejectedWithTheLimitStated() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        JsonObject meeting = created(api.post("/api/v1/reviews/one-on-ones", rahul,
                new JsonObject().put("personId", priyaPerson).put("heldOn", "2026-10-08")));

        HttpResponse<Buffer> res = api.put("/api/v1/reviews/one-on-ones/" + meeting.getString("id") + "/notes", rahul,
                new JsonObject().put("notes", "x".repeat(ReviewsRoutes.NOTES_MAX + 1)).put("version", 1));

        assertEquals(400, res.statusCode());

        assertEquals("notes", TestApi.error(res).getString("field"));

        assertTrue(TestApi.error(res).getString("message").contains("4000"), res.bodyAsString());
    }

    @Test
    void actionItemsHaveOwnerDueAndDone() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        JsonObject meeting = created(api.post("/api/v1/reviews/one-on-ones", rahul,
                new JsonObject().put("personId", priyaPerson).put("heldOn", "2026-10-15")));

        String actions = "/api/v1/reviews/one-on-ones/" + meeting.getString("id") + "/actions";

        JsonObject action = created(api.post(actions, priya,
                new JsonObject().put("text", "Write the ADR").put("ownerId", priyaUser).put("dueOn", "2026-10-20")));

        assertEquals(priyaUser, action.getString("ownerId"));

        HttpResponse<Buffer> done = api.put("/api/v1/reviews/actions/" + action.getString("id"), rahul,
                new JsonObject().put("text", "Write the ADR").put("ownerId", priyaUser).put("done", true).put("version", 1));

        assertEquals(200, done.statusCode(), done.bodyAsString());

        assertTrue(done.bodyAsJsonObject().getBoolean("done"));

        String outsider = api.queryOne("SELECT id FROM users WHERE email = 'arjun@pulse.demo'").getString("id");

        HttpResponse<Buffer> badOwner = api.post(actions, rahul, new JsonObject().put("text", "Someone else").put("ownerId", outsider));

        assertEquals(400, badOwner.statusCode());

        assertEquals("ownerId", TestApi.error(badOwner).getString("field"));
    }

    @Test
    void nobodyHoldsAOneOnOneWithThemselves() throws Exception
    {
        HttpResponse<Buffer> res = api.post("/api/v1/reviews/one-on-ones", RoleMatrix.session(api, RoleMatrix.EMPLOYEE),
                new JsonObject().put("personId", priyaPerson).put("heldOn", "2026-10-01"));

        assertEquals(422, res.statusCode());

        assertEquals("one_on_one_with_self", code(res));
    }

    // ------------------------------------------------------------------ kudos

    @Test
    void everyRoleHoldingOneononeMayGiveKudos() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "POST /api/v1/reviews/kudos", new JsonObject()
                .put("personId", nehaPerson).put("value", "Collaboration").put("message", "Thanks for the review."), Map.of(
                RoleMatrix.ADMIN, 201,
                RoleMatrix.MANAGER, 201,
                RoleMatrix.EMPLOYEE, 201,
                RoleMatrix.PEER, 201));
    }

    @Test
    void selfKudosAreRefused() throws Exception
    {
        HttpResponse<Buffer> res = api.post("/api/v1/reviews/kudos", RoleMatrix.session(api, RoleMatrix.EMPLOYEE),
                new JsonObject().put("personId", priyaPerson).put("value", "Resilience").put("message", "Me!"));

        assertEquals(422, res.statusCode());

        assertEquals("self_kudos_refused", code(res));
    }

    @Test
    void kudosGoToTheFeedAndTheSlackCopyToTheOutbox() throws Exception
    {
        long outbox = api.count("SELECT count(*) FROM outbox WHERE channel = 'slack'");

        JsonObject given = created(api.post("/api/v1/reviews/kudos", RoleMatrix.session(api, RoleMatrix.PEER), new JsonObject()
                .put("personId", priyaPerson).put("value", "Problem Solving").put("message", "Found the leak.").put("shareTo", "slack")));

        assertEquals("slack", given.getString("sharedTo"));

        assertEquals(outbox + 1, api.count("SELECT count(*) FROM outbox WHERE channel = 'slack'"));

        HttpResponse<Buffer> feed = api.get("/api/v1/reviews/kudos?personId=" + priyaPerson,
                RoleMatrix.session(api, RoleMatrix.EMPLOYEE));

        assertEquals(200, feed.statusCode(), feed.bodyAsString());

        JsonObject newest = feed.bodyAsJsonObject().getJsonArray("items").getJsonObject(0);

        assertEquals("Found the leak.", newest.getString("message"));

        assertEquals("Arjun Mehta", newest.getString("fromName"));

        // A peer's feed holds only kudos they may see (their own), never Priya's.
        HttpResponse<Buffer> peerFeed = api.get("/api/v1/reviews/kudos", RoleMatrix.session(api, RoleMatrix.PEER));

        assertTrue(!peerFeed.bodyAsString().contains("Found the leak."), peerFeed.bodyAsString());
    }

    @Test
    void kudosInputIsChecked() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        HttpResponse<Buffer> badValue = api.post("/api/v1/reviews/kudos", priya,
                new JsonObject().put("personId", nehaPerson).put("value", "Speed").put("message", "Fast"));

        assertEquals(400, badValue.statusCode());

        HttpResponse<Buffer> tooLong = api.post("/api/v1/reviews/kudos", priya, new JsonObject()
                .put("personId", nehaPerson).put("value", "Collaboration").put("message", "x".repeat(ReviewsRoutes.KUDOS_MAX + 1)));

        assertEquals(400, tooLong.statusCode());

        assertTrue(TestApi.error(tooLong).getString("message").contains("500"), tooLong.bodyAsString());

        HttpResponse<Buffer> nobody = api.post("/api/v1/reviews/kudos", priya, new JsonObject()
                .put("personId", "00000000-0000-0000-0000-000000000000").put("value", "Collaboration").put("message", "Hi"));

        assertEquals(404, nobody.statusCode());
    }

    // ------------------------------------------------------------------ overdue 1:1s (task 20.2)

    @Test
    void elevenWorkingDaysIsOverdueAndTenIsNot()
    {
        // Spec "Overdue": the last 1:1 was 11 working days ago (no holidays in these weeks).
        LocalDate last = LocalDate.of(2026, 11, 2);

        assertEquals(10, OneOnOneActionProvider.daysSince(last, LocalDate.of(2026, 11, 16), Set.of()));

        assertEquals(11, OneOnOneActionProvider.daysSince(last, LocalDate.of(2026, 11, 17), Set.of()));
    }

    @Test
    void theManagerGetsAMediumItemForAnOverdueOneOnOne() throws Exception
    {
        String neha = api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = 'neha@pulse.demo'").getString("id");

        String rahul = api.queryOne("SELECT id FROM users WHERE email = 'rahul@pulse.demo'").getString("id");

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        api.exec("DELETE FROM one_on_ones WHERE person_id = '" + neha + "' AND NOT EXISTS (SELECT 1 FROM one_on_one_actions a "
                + "WHERE a.one_on_one_id = one_on_ones.id)");

        api.exec("INSERT INTO one_on_ones (person_id, manager_id, held_on) VALUES ('" + neha + "', '" + rahul + "', '" + today.minusDays(40) + "')");

        JsonObject item = overdueFor(neha);

        assertTrue(item != null, "an overdue item for Neha");

        assertEquals("Medium", item.getString("priority"));

        assertTrue(item.getString("text").startsWith("Last 1:1 "), item.encode());

        // A recent 1:1 clears it.
        api.exec("INSERT INTO one_on_ones (person_id, manager_id, held_on) VALUES ('" + neha + "', '" + rahul + "', '" + today.minusDays(1) + "')");

        assertEquals(null, overdueFor(neha));

        // An employee manages nobody, so has no 1:1 items.
        HttpResponse<Buffer> employee = api.get("/api/v1/reviews/reminders", RoleMatrix.session(api, RoleMatrix.EMPLOYEE));

        assertTrue(employee.statusCode() == 403 || employee.bodyAsJsonObject().getJsonArray("items").isEmpty(), employee.bodyAsString());
    }

    private static JsonObject overdueFor(String personId) throws Exception
    {
        HttpResponse<Buffer> res = api.get("/api/v1/reviews/reminders", RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        for (Object o : res.bodyAsJsonObject().getJsonArray("items"))
        {
            JsonObject x = (JsonObject) o;

            if (personId.equals(x.getString("personId")))
            {
                return x;
            }
        }

        return null;
    }

    @Test
    void slackKudosArriveThroughTheIngestSeamAndShowInTheFeed() throws Exception
    {
        // Spec "Kudos": Slack /kudos messages are ingested (design.md decision 6) — kudos carries the ingest columns.
        String priya = api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = 'priya@pulse.demo'").getString("id");

        JsonObject batch = new JsonObject().put("source", "slack").put("entities", new JsonArray().add(new JsonObject()
                .put("entity", "kudos")
                .put("records", new JsonArray().add(new JsonObject()
                        .put("external_id", "slack-msg-42")
                        .put("person_id", priya)
                        .put("culture_value", "Collaboration")
                        .put("message", "Paired with me on the flaky test until it was green.")))));

        TestApi.await(new IngestService(api.sql()).ingest(batch));

        // Running the same batch again creates no duplicate.
        TestApi.await(new IngestService(api.sql()).ingest(batch));

        assertEquals(1, api.count("SELECT count(*) FROM kudos WHERE source = 'slack' AND external_id = 'slack-msg-42'"));

        HttpResponse<Buffer> feed = api.get("/api/v1/reviews/kudos?personId=" + priya, RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertEquals(200, feed.statusCode(), feed.bodyAsString());

        assertTrue(feed.bodyAsString().contains("flaky test until it was green"), feed.bodyAsString());
    }
}
