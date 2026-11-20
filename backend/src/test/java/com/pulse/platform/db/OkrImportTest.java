package com.pulse.platform.db;

import com.pulse.modules.okrs.OkrException;
import com.pulse.modules.okrs.OkrImportPort;
import com.pulse.modules.okrs.OkrImportService;
import com.pulse.modules.okrs.OkrService;
import com.pulse.modules.okrs.OkrsModule;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 14.3: the OKR import and export port that Track 3's CSV engine calls, against PostgreSQL with
 * the base and demo OKR seed. {@link #commitFile} plays Track 3's stub: it groups CSV rows the way the
 * OKR import definition will and commits the whole file in one transaction.
 */
class OkrImportTest
{

    /**
     * Policy stub (task 3.5): Managers and Admins see everyone, Employees only themselves.
     */
    private static final BiFunction<JsonObject, String, Future<Boolean>> VISIBILITY = (actor, personId) ->
            Future.succeededFuture(!"Employee".equals(actor.getString("role")));

    private static TestDb db;

    private static PgPool owner;

    private static SqlExecutor sql;

    private static OkrImportPort port;

    private static JsonObject priya;

    private static JsonObject rahul;

    @BeforeAll
    static void load() throws Exception
    {
        db = TestDb.open();

        owner = db.ownerPool();

        ScriptCatalog catalog = ScriptCatalog.load(OkrImportTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule(), new OkrsModule())));

        await(MigrationRunner.migrate(owner, catalog));

        await(MigrationRunner.seed(owner, catalog));

        sql = new SqlExecutor(db.pool(Db.DEFAULT_APP_ROLE));

        port = new OkrImportService(sql, VISIBILITY);

        priya = actor("priya@pulse.demo");

        rahul = actor("rahul@pulse.demo");
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
    void quartersConvertBetweenTheCsvFormAndTheKey()
    {
        assertEquals("2026-Q4", OkrImportService.quarterKey("Q4 2026"));
        assertEquals("2026-Q4", OkrImportService.quarterKey(" q4  2026 "));
        assertEquals("2026-Q4", OkrImportService.quarterKey("2026-q4"));
        assertNull(OkrImportService.quarterKey("Q5 2026"));
        assertNull(OkrImportService.quarterKey("Autumn"));
        assertEquals("Q3 2026", OkrImportService.quarterLabel("2026-Q3"));
    }

    @Test
    void findOwnerExplainsEveryRefusal() throws Exception
    {
        assertEquals("No user with nobody@pulse.demo",
                await(port.findOwner(rahul, "nobody@pulse.demo")).getString("error"));

        assertEquals("Rahul Nair has no performance profile, so can't own individual OKRs",
                await(port.findOwner(rahul, "rahul@pulse.demo")).getString("error"));

        assertEquals("You can't manage OKRs for Arjun Mehta",
                await(port.findOwner(priya, "ARJUN@pulse.demo")).getString("error"));

        JsonObject arjun = await(port.findOwner(rahul, "arjun@pulse.demo"));

        assertNull(arjun.getString("error"));

        assertEquals(personId("am"), arjun.getString("person_id"));

        assertEquals(personId("ps"), await(port.findOwner(priya, "priya@pulse.demo")).getString("person_id"));
    }

    @Test
    void anUpdateOfAMatchingObjectiveActsAsACheckIn() throws Exception
    {
        List<Map<String, String>> file = List.of(
                row("Q3 2026", "arjun@pulse.demo", "ship tenant quota enforcement", "Quota breaches auto-throttled", "0", "95", "75"),
                row("Q3 2026", "arjun@pulse.demo", "Ship tenant quota enforcement", "Quota dashboards per tenant", "0", "10", "4"));

        JsonObject matching = await(port.findMatching(rahul, new JsonObject()
                .put("quarter", "2026-Q3").put("owner_person_id", personId("am"))
                .put("title", "ship tenant QUOTA enforcement")));

        assertTrue(matching.getBoolean("editable"));

        assertEquals("Ship tenant quota enforcement", matching.getJsonObject("objective").getString("title"));

        JsonObject result = commitFile(rahul, file, OkrImportService.MODE_UPSERT).get(0);

        assertEquals("update", result.getString("action"));

        assertEquals(1, result.getJsonArray("checkin_ids").size());

        assertEquals(1, result.getJsonArray("added_key_results").size());

        Row checkIn = await(owner.preparedQuery("""
                        SELECT c.previous_value, c.new_value, c.via, k.current_value
                        FROM key_result_checkins c JOIN key_results k ON k.id = c.key_result_id
                        WHERE c.id = $1::uuid""")
                .execute(Tuple.of(result.getJsonArray("checkin_ids").getString(0)))).iterator().next();

        assertEquals(0, new BigDecimal("60").compareTo(checkIn.getBigDecimal(0)));
        assertEquals(0, new BigDecimal("75").compareTo(checkIn.getBigDecimal(1)));
        assertEquals("import", checkIn.getString(2));
        assertEquals(0, new BigDecimal("75").compareTo(checkIn.getBigDecimal(3)));

        assertEquals(3L, count("SELECT count(*) FROM key_results k JOIN objectives o ON o.id = k.objective_id "
                + "WHERE o.title = 'Ship tenant quota enforcement'"));
    }

    @Test
    void addNewOnlySkipsExistingObjectivesAndCreatesNewOnes() throws Exception
    {
        List<Map<String, String>> file = List.of(
                row("Q3 2026", "neha@pulse.demo", "Own the trap-filter module", "Unit test coverage", "35", "80", "70"),
                row("Q4 2026", "neha@pulse.demo", "Learn the NetFlow pipeline", "Pipeline docs read", "0", "5", "1"));

        List<JsonObject> results = commitFile(rahul, file, OkrImportService.MODE_NEW);

        assertEquals("skip", results.get(0).getString("action"));

        assertEquals("new", results.get(1).getString("action"));

        assertEquals(0, new BigDecimal("62").compareTo(new BigDecimal(await(owner.query(
                "SELECT k.current_value FROM key_results k JOIN objectives o ON o.id = k.objective_id "
                        + "WHERE o.title = 'Own the trap-filter module' AND k.description = 'Unit test coverage'")
                .execute()).iterator().next().getValue(0).toString())));

        Row created = await(owner.query("SELECT o.quarter, k.unit FROM objectives o JOIN key_results k ON k.objective_id = o.id "
                + "WHERE o.title = 'Learn the NetFlow pipeline'").execute()).iterator().next();

        assertEquals("2026-Q4", created.getString(0));

        assertEquals("%", created.getString(1), "the import's default unit");
    }

    @Test
    void teamRowsNeedOkrTeamAndAFailingGroupRollsBackTheWholeFile() throws Exception
    {
        Map<String, String> teamRow = row("Q4 2026", "", "Faster Platform reviews", "Median first review (h)", "20", "8", "20");

        teamRow.put("level", "Team");

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> commitFile(priya, List.of(teamRow), OkrImportService.MODE_UPSERT));

        assertEquals(403, ((OkrException) refused.getCause()).status());

        Map<String, String> good = row("Q4 2026", "kiran@pulse.demo", "Stabilise the flow collector", "Memory leaks open", "3", "0", "3");

        Map<String, String> bad = row("Q4 2026", "kiran@pulse.demo", "Cut collector restarts", "Restarts per week", "4", "4", "4");

        ExecutionException invalid = assertThrows(ExecutionException.class,
                () -> commitFile(rahul, List.of(good, bad), OkrImportService.MODE_UPSERT));

        assertEquals("Target must differ from start.", invalid.getCause().getMessage());

        assertEquals(0L, count("SELECT count(*) FROM objectives WHERE title = 'Stabilise the flow collector'"));

        assertEquals("new", commitFile(rahul, List.of(teamRow), OkrImportService.MODE_UPSERT).get(0).getString("action"));
    }

    @Test
    void sameKeyResultTwiceInOneObjectiveIsRefused()
    {
        ExecutionException refused = assertThrows(ExecutionException.class, () -> commitFile(rahul, List.of(
                row("Q1 2027", "dev@pulse.demo", "Harden agent upgrades", "Failed upgrades", "8", "1", "8"),
                row("Q1 2027", "dev@pulse.demo", "Harden agent upgrades", "failed upgrades", "8", "2", "8")),
                OkrImportService.MODE_UPSERT));

        assertEquals("Same key result twice in one objective", refused.getCause().getMessage());
    }

    @Test
    void undoRestoresTheBeforeImageUnlessTheObjectiveChangedSince() throws Exception
    {
        JsonObject result = commitFile(rahul, List.of(
                row("Q3 2026", "neha@pulse.demo", "Own the trap-filter module", "Bugs fixed without reopen", "0", "6", "5"),
                row("Q3 2026", "neha@pulse.demo", "Own the trap-filter module", "Trap filter docs", "0", "1", "1")),
                OkrImportService.MODE_UPSERT).get(0);

        await(sql.inTransaction(tx -> new OkrImportService(tx, VISIBILITY).undoUpsert(rahul, result)));

        assertEquals(0, new BigDecimal("4").compareTo(new BigDecimal(await(owner.query(
                "SELECT k.current_value FROM key_results k JOIN objectives o ON o.id = k.objective_id "
                        + "WHERE o.title = 'Own the trap-filter module' AND k.description = 'Bugs fixed without reopen'")
                .execute()).iterator().next().getValue(0).toString())));

        assertEquals(0L, count("SELECT count(*) FROM key_results WHERE description = 'Trap filter docs'"));

        assertEquals(0L, count("SELECT count(*) FROM key_result_checkins WHERE via = 'import' AND key_result_id IN "
                + "(SELECT k.id FROM key_results k JOIN objectives o ON o.id = k.objective_id "
                + "WHERE o.title = 'Own the trap-filter module')"));

        JsonObject again = commitFile(rahul, List.of(
                row("Q3 2026", "neha@pulse.demo", "Own the trap-filter module", "Bugs fixed without reopen", "0", "6", "5")),
                OkrImportService.MODE_UPSERT).get(0);

        JsonObject later = await(new OkrService(sql, VISIBILITY).get(rahul, again.getString("objective_id")));

        await(new OkrService(sql, VISIBILITY).update(rahul, again.getString("objective_id"), new JsonObject()
                .put("title", later.getString("title"))
                .put("quarter", later.getString("quarter"))
                .put("version", later.getInteger("version"))
                .put("key_results", later.getJsonArray("key_results"))));

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> await(sql.inTransaction(tx -> new OkrImportService(tx, VISIBILITY).undoUpsert(rahul, again))));

        assertEquals(409, ((OkrException) refused.getCause()).status());
    }

    @Test
    void exportShowsVisibleRowsInImportOrderAndRoundTrips() throws Exception
    {
        JsonArray mine = await(port.listForExport(priya, "Q2 2026"));

        assertEquals(List.of("quarter", "level", "owner_email", "objective", "key_result", "start", "target",
                "current", "unit", "value", "epic"), new ArrayList<>(mine.getJsonObject(0).fieldNames()));

        assertTrue(mine.stream().allMatch(r -> ((JsonObject) r).getString("quarter").equals("Q2 2026")));

        assertTrue(mine.stream().map(r -> ((JsonObject) r).getString("owner_email"))
                .allMatch(email -> email.isEmpty() || email.equals("priya@pulse.demo")), mine.encode());

        assertEquals(3, mine.size(), "Priya's Q2 objective has 2 key results and the Team objective 1");

        List<Map<String, String>> file = new ArrayList<>();

        for (Object item : await(port.listForExport(rahul, "Q2 2026")))
        {
            JsonObject r = (JsonObject) item;

            Map<String, String> csv = row(r.getString("quarter"), r.getString("owner_email"), r.getString("objective"),
                    r.getString("key_result"), r.getValue("start").toString(), r.getValue("target").toString(),
                    r.getValue("current").toString());

            csv.put("level", r.getString("level"));

            csv.put("unit", r.getString("unit"));

            file.add(csv);
        }

        for (JsonObject result : commitFile(rahul, file, OkrImportService.MODE_UPSERT))
        {
            assertEquals("update", result.getString("action"));

            assertEquals(0, result.getJsonArray("checkin_ids").size(), "an unchanged file records no check-ins");

            assertEquals(0, result.getJsonArray("added_key_results").size());
        }
    }

    // ------------------------------------------------------------------ Track 3 stub

    /**
     * What Track 3's OKR import definition does on Commit: resolve owners, group rows by quarter,
     * owner and objective, then upsert every group inside one transaction.
     */
    private static List<JsonObject> commitFile(JsonObject actor, List<Map<String, String>> rows, String mode)
            throws Exception
    {
        Map<String, JsonObject> groups = new LinkedHashMap<>();

        for (Map<String, String> csv : rows)
        {
            boolean team = "Team".equalsIgnoreCase(csv.getOrDefault("level", "Individual"));

            JsonObject group = new JsonObject()
                    .put("quarter", OkrImportService.quarterKey(csv.get("quarter")))
                    .put("level", team ? "Team" : "Individual")
                    .put("title", csv.get("objective"));

            if (team)
            {
                group.put("team", "Platform");
            }
            else
            {
                JsonObject found = await(port.findOwner(actor, csv.get("owner_email")));

                assertNull(found.getString("error"), found.encode());

                group.put("owner_person_id", found.getString("person_id"));
            }

            String key = group.getString("quarter") + "|" + group.getString("owner_person_id", "team")
                    + "|" + csv.get("objective").toLowerCase();

            JsonObject kr = new JsonObject()
                    .put("description", csv.get("key_result"))
                    .put("start_value", new BigDecimal(csv.get("start")))
                    .put("target_value", new BigDecimal(csv.get("target")))
                    .put("current_value", new BigDecimal(csv.get("current")))
                    .put("unit", csv.get("unit"));

            groups.computeIfAbsent(key, k -> group.put("key_results", new JsonArray()))
                    .getJsonArray("key_results").add(kr);
        }

        return await(sql.inTransaction(tx ->
        {
            OkrImportPort inTx = new OkrImportService(tx, VISIBILITY);

            List<JsonObject> results = new ArrayList<>();

            Future<Void> chain = Future.succeededFuture();

            for (JsonObject group : groups.values())
            {
                chain = chain.compose(v -> inTx.upsertObjective(actor, group, mode).map(result ->
                {
                    results.add(result);

                    return null;
                }));
            }

            return chain.map(v -> results);
        }));
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> row(String quarter, String ownerEmail, String objective, String keyResult,
                                           String start, String target, String current)
    {
        Map<String, String> csv = new LinkedHashMap<>();

        csv.put("quarter", quarter);
        csv.put("owner_email", ownerEmail);
        csv.put("objective", objective);
        csv.put("key_result", keyResult);
        csv.put("start", start);
        csv.put("target", target);
        csv.put("current", current);

        return csv;
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

    private static long count(String sql) throws Exception
    {
        return await(owner.query(sql).execute()).iterator().next().getLong(0);
    }
}
