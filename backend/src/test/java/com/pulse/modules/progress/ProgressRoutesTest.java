package com.pulse.modules.progress;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 13.3: Progress and the weekly pulse on a real server with the Track 2 demo seed and "today" pinned
 * to Monday 28 Sep 2026: who may open a Progress page and the pulse answers (a peer is refused), both
 * answers required, a low answer notifying the manager and showing in the Action center, no note text in
 * the logs, the same KPIs for the manager and the employee, Team wins for a teammate and the notes
 * removed on user deletion. The Friday prompt job: {@link PulsePromptJobTest}.
 */
class ProgressRoutesTest
{

    private static final int PORT = 18_272;

    private static final String API = "/api/v1/progress";

    private static final String PRIYA = "{person:priya@pulse.demo}";

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress",
                "okrs", "roadmap", "action_center");
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    @Test
    void progressIsForThePersonTheirManagerAndAllView() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/people/" + PRIYA, Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 403));
    }

    @Test
    void aPeerIsRefusedAnothersPulse() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/people/" + PRIYA + "/pulse", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 403));
    }

    @Test
    void bothAnswersAreRequired() throws Exception
    {
        HttpResponse<Buffer> res = api.post(API + "/pulse", session(RoleMatrix.PEER), new JsonObject().put("mood", 3));

        assertEquals(400, res.statusCode(), res.bodyAsString());
        assertEquals("Answer both questions.", TestApi.error(res).getString("message"));
        assertEquals("workload", TestApi.error(res).getString("field"));

        assertEquals(400, api.post(API + "/pulse", session(RoleMatrix.PEER), new JsonObject().put("workload", 3)).statusCode());
    }

    @Test
    void aLowAnswerNotifiesTheManagerAddsADoTodayItemAndNeverReachesTheLogs() throws Exception
    {
        String note = "note-" + UUID.randomUUID();

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        ListAppender<ILoggingEvent> logs = new ListAppender<>();

        logs.start();

        root.addAppender(logs);

        HttpResponse<Buffer> res;

        try
        {
            res = api.post(API + "/pulse", session(RoleMatrix.EMPLOYEE), new JsonObject().put("mood", 2).put("workload", 5).put("note", note));
        }
        finally
        {
            root.detachAppender(logs);
        }

        assertEquals(201, res.statusCode(), res.bodyAsString());
        assertEquals("2026-09-28", res.bodyAsJsonObject().getString("weekStart"));
        assertTrue(res.bodyAsJsonObject().getBoolean("low"));

        // spec "Log check": logs contain no note text
        assertFalse(logs.list.isEmpty(), "the request was logged");
        assertTrue(logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains(note)), "no note text in the logs");

        JsonArray notifications = api.get("/api/v1/notifications", session(RoleMatrix.MANAGER)).bodyAsJsonObject().getJsonArray("items");

        assertTrue(notifications.stream().map(n -> ((JsonObject) n).getString("text"))
                .anyMatch(t -> t.equals("Priya Shah's weekly pulse is low · week of 28 Sep — check in")), notifications.encode());
        assertTrue(notifications.stream().map(n -> ((JsonObject) n).getString("text")).noneMatch(t -> t.contains(note)));

        JsonArray items = api.get("/api/v1/action_center/items", session(RoleMatrix.MANAGER)).bodyAsJsonObject().getJsonArray("items");

        assertTrue(items.stream().map(i -> (JsonObject) i).anyMatch(i -> "progress.low-pulse".equals(i.getString("key"))
                && "Priya Shah".equals(i.getString("personName")) && i.getString("text").startsWith("Weekly pulse: mood 2/5, workload 5/5")),
                items.encode());

        // the answer is this week's: the form is closed and the KPI shows it
        JsonObject progress = progress(RoleMatrix.EMPLOYEE);

        assertFalse(progress.getJsonObject("pulse").getBoolean("open"));
        assertEquals(2, progress.getJsonObject("kpis").getJsonObject("pulse").getInteger("mood"));

        // the manager reads it; the note is rendered by the screen as text
        JsonArray answers = api.get(API + "/people/" + priyaId() + "/pulse", session(RoleMatrix.MANAGER)).bodyAsJsonObject().getJsonArray("items");

        assertEquals(note, answers.getJsonObject(0).getString("note"));
    }

    @Test
    void theManagerAndTheEmployeeSeeTheSameNumbers() throws Exception
    {
        JsonObject manager = progress(RoleMatrix.MANAGER);

        JsonObject employee = progress(RoleMatrix.EMPLOYEE);

        assertEquals(manager.getJsonObject("kpis"), employee.getJsonObject("kpis"));
        assertEquals(manager.getJsonArray("achievements"), employee.getJsonArray("achievements"));
        assertEquals(manager.getJsonArray("nextSteps").size(), employee.getJsonArray("nextSteps").size());

        assertTrue(manager.getJsonObject("can").getBoolean("startOneOnOne"));
        assertFalse(manager.getJsonObject("can").getBoolean("pulse"));
        assertFalse(employee.getJsonObject("can").getBoolean("startOneOnOne"));
        assertTrue(employee.getJsonObject("can").getBoolean("pulse"));

        // a self-only step (this week's pulse) is in both lists, actionable only for the person
        for (Object o : manager.getJsonArray("nextSteps"))
        {
            JsonObject step = (JsonObject) o;

            if (step.getBoolean("selfOnly"))
            {
                assertFalse(step.getBoolean("actionable"));
            }
        }

        assertEquals(4, manager.getJsonObject("trends").getJsonArray("index").size());
    }

    @Test
    void aTeammateSeesTeamWinsWithoutPrivateData() throws Exception
    {
        HttpResponse<Buffer> res = api.get(API + "/wins", session(RoleMatrix.PEER));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonArray items = res.bodyAsJsonObject().getJsonArray("items");

        assertTrue(items.stream().map(i -> (JsonObject) i).anyMatch(i -> "proactive-fix".equals(i.getString("kind"))), items.encode());

        for (Object o : items)
        {
            JsonObject win = (JsonObject) o;

            for (String key : List.of("mood", "workload", "note", "ctc", "salary"))
            {
                assertFalse(win.containsKey(key), key + " in " + win.encode());
            }
        }
    }

    @Test
    void aDeletedUsersPulseNotesAreRemoved() throws Exception
    {
        JsonObject row = api.queryOne("SELECT p.id FROM people p WHERE p.source = 'seed' AND p.external_id = 'nj'");

        assertNotNull(row);

        long notes = api.count("SELECT count(*) FROM weekly_pulse WHERE person_id = '" + row.getString("id") + "' AND note <> ''");

        assertTrue(notes > 0, "Neha has notes in the seed");

        TestApi.await(api.sql().inTransaction(tx -> new ProgressModule().erasePersonalData(tx, new JsonObject().put("personId", row.getString("id")))));

        assertEquals(0, api.count("SELECT count(*) FROM weekly_pulse WHERE person_id = '" + row.getString("id") + "' AND note <> ''"));
        assertTrue(api.count("SELECT count(*) FROM weekly_pulse WHERE person_id = '" + row.getString("id") + "'") > 0, "the scores stay");
    }

    private static JsonObject progress(String who) throws Exception
    {
        HttpResponse<Buffer> res = api.get(API + "/people/" + priyaId(), session(who));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static String priyaId() throws Exception
    {
        return api.queryOne("SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");
    }

    private static TestApi.Session session(String who) throws Exception
    {
        return RoleMatrix.session(api, who);
    }
}
