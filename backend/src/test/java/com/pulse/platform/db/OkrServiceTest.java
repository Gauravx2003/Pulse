package com.pulse.platform.db;

import com.pulse.modules.okrs.OkrException;
import com.pulse.modules.okrs.OkrService;
import com.pulse.modules.okrs.OkrsModule;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.modules.work.WorkModule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 14.2: the OKR service against PostgreSQL with the base and demo OKR seed, covering the
 * scenarios of spec growth/okrs that do not need CalcService (progress and pillar come in 4.10).
 * Visibility is a stub of the policy service (task 3.5): Managers and Admins see everyone,
 * Employees only themselves.
 */
class OkrServiceTest
{

    private static TestDb db;

    private static PgPool owner;

    private static OkrService service;

    private static JsonObject priya;

    private static JsonObject rahul;

    private static JsonObject arjunPerson;

    @BeforeAll
    static void load() throws Exception
    {
        AuditEvents.register(new OkrsModule().auditEvents());

        db = TestDb.open();

        owner = db.ownerPool();

        ScriptCatalog catalog = ScriptCatalog.load(OkrServiceTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule(), new OkrsModule())));

        await(MigrationRunner.migrate(owner, catalog));

        await(MigrationRunner.seed(owner, catalog));

        PgPool app = db.pool(Db.DEFAULT_APP_ROLE);

        service = new OkrService(new SqlExecutor(app), (actor, personId) ->
                Future.succeededFuture(!"Employee".equals(actor.getString("role"))));

        priya = actor("priya@pulse.demo");

        rahul = actor("rahul@pulse.demo");

        arjunPerson = new JsonObject().put("id", personId("am"));
    }

    @AfterAll
    static void tearDown() throws Exception
    {
        if (db != null)
        {
            db.close();
        }
    }

    @Test
    void createValidatesLikeTheObjectiveForm()
    {
        assertRefused(400, "Select a quarter.", service.create(priya, objective("Ship it", null)));

        assertRefused(400, "Write the objective.", service.create(priya, objective("  ", "2026-Q4")));

        assertRefused(400, "Every key result needs a description.", service.create(priya,
                objective("Ship it", "2026-Q4").put("key_results", new JsonArray().add(kr("", 0, 1)))));

        assertRefused(400, "Target must differ from start.", service.create(priya,
                objective("Ship it", "2026-Q4").put("key_results", new JsonArray().add(kr("Flat", 3, 3)))));

        assertRefused(400, "Add 1 to 5 key results.", service.create(priya,
                objective("Ship it", "2026-Q4").put("key_results", new JsonArray())));

        JsonArray six = new JsonArray();

        for (int i = 1; i <= 6; i++)
        {
            six.add(kr("Result " + i, 0, i));
        }

        assertRefused(400, "Add 1 to 5 key results.", service.create(priya,
                objective("Ship it", "2026-Q4").put("key_results", six)));
    }

    @Test
    void employeeCreatesOwnObjectiveWithAFallingTarget() throws Exception
    {
        JsonObject created = await(service.create(priya, objective("Cut ingestion latency", "2026-Q4")
                .put("key_results", new JsonArray().add(kr("Ingestion p95 latency", 900, 400).put("current_value", 650)))));

        assertEquals(priya.getString("personId"), created.getString("owner_id"));

        assertEquals(priya.getString("userId"), created.getString("created_by"));

        JsonObject latency = created.getJsonArray("key_results").getJsonObject(0);

        assertEquals(1, latency.getInteger("position"));

        assertEquals(0, new BigDecimal("650").compareTo(new BigDecimal(latency.getValue("current_value").toString())));
    }

    @Test
    void employeeCannotEditAPeersObjective() throws Exception
    {
        JsonObject arjuns = seeded("Ship tenant quota enforcement");

        assertRefused(403, null, service.update(priya, arjuns.getString("id"),
                editOf(arjuns).put("title", "Taken over")));

        assertRefused(403, null, service.delete(priya, arjuns.getString("id")));

        assertRefused(404, null, service.get(priya, arjuns.getString("id")));

        // A missing objective gets the same answer as a forbidden one.
        assertRefused(403, null, service.delete(priya, "00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void managerEditsAReportsObjectiveAndOnlyOkrTeamCreatesTeamObjectives() throws Exception
    {
        JsonObject arjuns = seeded("Ship tenant quota enforcement");

        JsonObject updated = await(service.update(rahul, arjuns.getString("id"),
                editOf(arjuns).put("epic_key", "PLAT-2202")));

        assertEquals("PLAT-2202", updated.getString("epic_key"));

        assertEquals(arjuns.getInteger("version") + 1, updated.getInteger("version"));

        JsonObject team = objective("Halve the stale share", "2026-Q4").put("level", "Team").put("team", "Platform")
                .put("key_results", new JsonArray().add(kr("Stale issues share", 22, 10)));

        assertRefused(403, null, service.create(priya, team.copy()));

        JsonObject created = await(service.create(rahul, team));

        assertEquals("Team", created.getString("level"));

        assertEquals(null, created.getString("owner_id"));

        JsonObject forArjun = await(service.create(rahul, objective("Run the quota rollout", "2026-Q4")
                .put("owner_id", arjunPerson.getString("id"))));

        assertEquals(arjunPerson.getString("id"), forArjun.getString("owner_id"));
    }

    @Test
    void checkInRecordsTheChangeAndMovesTheCurrentValue() throws Exception
    {
        JsonObject latency = seeded("Make ingestion safe for noisy tenants").getJsonArray("key_results").getJsonObject(1);

        JsonObject after = await(service.checkIn(priya, latency.getString("id"),
                new JsonObject().put("value", 480).put("note", "Load test on staging")));

        assertEquals(0, new BigDecimal("480").compareTo(new BigDecimal(after.getValue("current_value").toString())));

        Row logged = await(owner.preparedQuery("SELECT previous_value, new_value, note, via FROM key_result_checkins "
                + "WHERE key_result_id = $1::uuid").execute(Tuple.of(latency.getString("id")))).iterator().next();

        assertEquals(0, new BigDecimal("520").compareTo(logged.getBigDecimal(0)));
        assertEquals(0, new BigDecimal("480").compareTo(logged.getBigDecimal(1)));
        assertEquals("Load test on staging", logged.getString(2));
        assertEquals("manual", logged.getString(3));

        assertRefused(400, "Enter the current value.", service.checkIn(priya, latency.getString("id"), new JsonObject()));

        JsonObject arjunsKr = seeded("Ship tenant quota enforcement").getJsonArray("key_results").getJsonObject(0);

        assertRefused(403, null, service.checkIn(priya, arjunsKr.getString("id"), new JsonObject().put("value", 1)));
    }

    @Test
    void aStaleVersionIsAConflict() throws Exception
    {
        JsonObject mine = await(service.create(priya, objective("Write two ADRs", "2027-Q1")));

        await(service.update(priya, mine.getString("id"), editOf(mine).put("title", "Write three ADRs")));

        assertRefused(409, null, service.update(priya, mine.getString("id"), editOf(mine).put("title", "Write four ADRs")));
    }

    @Test
    void editingKeyResultsRemovesAddsAndRefusesReordering() throws Exception
    {
        JsonObject mine = await(service.create(priya, objective("Own the alert editor", "2027-Q1")
                .put("key_results", new JsonArray()
                        .add(kr("First", 0, 1))
                        .add(kr("Second", 0, 2))
                        .add(kr("Third", 0, 3)))));

        JsonArray krs = mine.getJsonArray("key_results");

        JsonObject reordered = editOf(mine).put("key_results", new JsonArray()
                .add(krs.getJsonObject(2))
                .add(krs.getJsonObject(0)));

        assertRefused(400, "Key results cannot be reordered.", service.update(priya, mine.getString("id"), reordered));

        JsonObject edited = await(service.update(priya, mine.getString("id"), editOf(mine).put("key_results", new JsonArray()
                .add(krs.getJsonObject(0))
                .add(krs.getJsonObject(2).copy().put("description", "Third, renamed"))
                .add(kr("Fourth", 0, 4)))));

        JsonArray after = edited.getJsonArray("key_results");

        assertEquals(3, after.size());
        assertEquals("First", after.getJsonObject(0).getString("description"));
        assertEquals("Third, renamed", after.getJsonObject(1).getString("description"));
        assertEquals(2, after.getJsonObject(1).getInteger("position"));
        assertEquals(krs.getJsonObject(2).getString("id"), after.getJsonObject(1).getString("id"));
        assertEquals("Fourth", after.getJsonObject(2).getString("description"));
    }

    @Test
    void deleteRemovesKeyResultsAndCheckInsAndReportsTheCount() throws Exception
    {
        JsonObject mine = await(service.create(priya, objective("Pair with a new joiner", "2027-Q1")
                .put("key_results", new JsonArray().add(kr("Sessions held", 0, 6)).add(kr("Tickets closed", 0, 4)))));

        String krId = mine.getJsonArray("key_results").getJsonObject(0).getString("id");

        await(service.checkIn(priya, krId, new JsonObject().put("value", 2)));

        JsonObject result = await(service.delete(priya, mine.getString("id")));

        assertEquals(2, result.getInteger("key_results"));

        assertRefused(404, null, service.get(priya, mine.getString("id")));

        assertEquals(0L, await(owner.preparedQuery("SELECT count(*) FROM key_result_checkins WHERE key_result_id = $1::uuid")
                .execute(Tuple.of(krId))).iterator().next().getLong(0));
    }

    @Test
    void theSameObjectiveTwiceIsAConflict() throws Exception
    {
        await(service.create(priya, objective("Improve trap parsing", "2027-Q1")));

        assertRefused(409, null, service.create(priya, objective("improve TRAP parsing", "2027-Q1")));
    }

    @Test
    void listsPeopleAndTeamObjectivesWithVisibility() throws Exception
    {
        JsonArray priyasQ3 = await(service.listForPerson(priya, priya.getString("personId"), "2026-Q3"));

        assertEquals(2, priyasQ3.size());

        assertRefused(404, null, service.listForPerson(priya, arjunPerson.getString("id"), "2026-Q3"));

        assertEquals(1, await(service.listForPerson(rahul, arjunPerson.getString("id"), "2026-Q3")).size());

        JsonArray team = await(service.listForTeam(priya, "Platform", "2026-Q3"));

        assertTrue(team.stream().allMatch(o -> "Team".equals(((JsonObject) o).getString("level"))), team.encode());

        assertEquals(1, team.size());
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject objective(String title, String quarter)
    {
        return new JsonObject()
                .put("title", title)
                .put("quarter", quarter)
                .put("key_results", new JsonArray().add(kr("Done", 0, 1)));
    }

    private static JsonObject kr(String description, Number start, Number target)
    {
        return new JsonObject().put("description", description).put("start_value", start).put("target_value", target);
    }

    private static JsonObject editOf(JsonObject objective)
    {
        return new JsonObject()
                .put("title", objective.getString("title"))
                .put("quarter", objective.getString("quarter"))
                .put("epic_key", objective.getString("epic_key"))
                .put("culture_value", objective.getString("culture_value"))
                .put("version", objective.getInteger("version"))
                .put("key_results", objective.getJsonArray("key_results").copy());
    }

    private static JsonObject seeded(String title) throws Exception
    {
        String id = await(owner.preparedQuery("SELECT id::text FROM objectives WHERE title = $1")
                .execute(Tuple.of(title))).iterator().next().getString(0);

        return await(service.get(rahul, id));
    }

    private static JsonObject actor(String email) throws Exception
    {
        Row row = await(owner.preparedQuery("""
                        SELECT u.id::text, p.id::text, r.name,
                               array(SELECT permission FROM role_permissions WHERE role_id = r.id) AS permissions
                        FROM users u JOIN roles r ON r.id = u.role_id LEFT JOIN people p ON p.user_id = u.id
                        WHERE u.email = $1""")
                .execute(Tuple.of(email))).iterator().next();

        return new JsonObject()
                .put("userId", row.getString(0))
                .put("personId", row.getString(1))
                .put("role", row.getString(2))
                .put("permissions", new JsonArray(List.of(row.getArrayOfStrings(3))));
    }

    private static String personId(String externalId) throws Exception
    {
        return await(owner.preparedQuery("SELECT id::text FROM people WHERE source = 'seed' AND external_id = $1")
                .execute(Tuple.of(externalId))).iterator().next().getString(0);
    }

    private static void assertRefused(int status, String message, Future<?> future)
    {
        ExecutionException e = assertThrows(ExecutionException.class, () -> await(future));

        assertTrue(e.getCause() instanceof OkrException, String.valueOf(e.getCause()));

        OkrException refused = (OkrException) e.getCause();

        assertEquals(status, refused.status(), refused.getMessage());

        if (message != null)
        {
            assertEquals(message, refused.getMessage());
        }
    }
}
