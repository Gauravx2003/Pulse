package com.pulse.platform.db;

import com.pulse.modules.okrs.OkrsModule;
import com.pulse.modules.work.WorkModule;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 14.1: V210 applied after V100 and V200, and the demo OKR seed equal to the prototype's OKRS. */
class OkrSeedTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";

    private static TestDb db;
    private static PgPool owner;
    private static PgPool app;
    private static List<SqlScript> migrated;
    private static List<SqlScript> seeded;
    private static JsonArray prototype;

    @BeforeAll
    static void load() throws Exception {
        prototype = new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "okrs.json")));
        db = TestDb.open();
        owner = db.ownerPool();
        ScriptCatalog catalog = ScriptCatalog.load(OkrSeedTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule(), new OkrsModule())));
        migrated = await(MigrationRunner.migrate(owner, catalog));
        seeded = await(MigrationRunner.seed(owner, catalog));
        app = db.pool(Db.DEFAULT_APP_ROLE);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void v210AppliesAfterV100AndV200AndItsSeedAfterThePeopleSeed() {
        assertEquals(List.of(100, 200, 210), migrated.stream().map(SqlScript::version).toList());
        List<Integer> seeds = seeded.stream().map(SqlScript::version).toList();
        assertTrue(seeds.contains(110) && seeds.indexOf(110) < seeds.indexOf(210), seeds.toString());
    }

    @Test
    void seededObjectiveAndKeyResultCountsEqualThePrototype() throws Exception {
        int keyResults = prototype.stream().mapToInt(o -> ((JsonObject) o).getJsonArray("krs").size()).sum();
        assertEquals((long) prototype.size(), count("SELECT count(*) FROM objectives"));
        assertEquals((long) keyResults, count("SELECT count(*) FROM key_results"));
    }

    @Test
    void everySeededObjectiveMatchesThePrototype() throws Exception {
        for (Object item : prototype) {
            JsonObject o = (JsonObject) item;
            boolean team = "Team".equals(o.getString("level"));
            String[] q = o.getString("q").split(" ");
            Row row = one("""
                            SELECT o.id, o.level, o.team, p.external_id AS owner, coalesce(o.epic_key, '') AS epic,
                                   o.culture_value
                            FROM objectives o LEFT JOIN people p ON p.id = o.owner_id
                            WHERE o.quarter = $1 AND o.title = $2""",
                    Tuple.of(q[1] + "-" + q[0], o.getString("obj")));
            String where = o.getString("id");
            assertEquals(o.getString("level"), row.getString("level"), where);
            assertEquals(team ? null : o.getString("owner"), row.getString("owner"), where);
            assertEquals(team ? o.getString("team") : null, row.getString("team"), where);
            assertEquals(o.getString("epic"), row.getString("epic"), where);
            assertEquals(o.getString("value"), row.getString("culture_value"), where);

            List<String> expected = new ArrayList<>();
            for (Object k : o.getJsonArray("krs")) {
                JsonObject kr = (JsonObject) k;
                expected.add(kr.getString("t") + "|" + num(kr.getValue("s")) + "|" + num(kr.getValue("g")) + "|"
                        + num(kr.getValue("c")) + "|" + kr.getString("u"));
            }
            List<String> actual = new ArrayList<>();
            for (Row kr : await(owner.preparedQuery("SELECT description, start_value, target_value, current_value, unit "
                    + "FROM key_results WHERE objective_id = $1 ORDER BY position").execute(Tuple.of(row.getUUID("id"))))) {
                actual.add(kr.getString(0) + "|" + num(kr.getValue(1)) + "|" + num(kr.getValue(2)) + "|"
                        + num(kr.getValue(3)) + "|" + kr.getString(4));
            }
            assertEquals(expected, actual, where);
        }
    }

    @Test
    void keyResultTargetMustDifferFromStart() {
        assertSqlState(CHECK_VIOLATION, () -> await(app.query("INSERT INTO key_results "
                + "(objective_id, position, description, start_value, target_value, current_value) "
                + "SELECT id, 5, 'Flat target', 10, 10, 10 FROM objectives LIMIT 1").execute()));
    }

    @Test
    void anObjectiveHoldsAtMostFiveKeyResults() {
        assertSqlState(CHECK_VIOLATION, () -> await(app.query("INSERT INTO key_results "
                + "(objective_id, position, description, start_value, target_value, current_value) "
                + "SELECT id, 6, 'Sixth key result', 0, 1, 0 FROM objectives LIMIT 1").execute()));
    }

    @Test
    void sameObjectiveTextForTheSameOwnerAndQuarterIsRejected() {
        assertSqlState(UNIQUE_VIOLATION, () -> await(app.query("INSERT INTO objectives (quarter, level, owner_id, title) "
                + "SELECT quarter, level, owner_id, upper(title) FROM objectives WHERE level = 'Individual' LIMIT 1")
                .execute()));
    }

    @Test
    void individualObjectiveNeedsAnOwnerAndQuarterUsesTheKeyFormat() {
        assertSqlState(CHECK_VIOLATION, () -> await(app.query("INSERT INTO objectives (quarter, level, title) "
                + "VALUES ('2026-Q4', 'Individual', 'No owner')").execute()));
        assertSqlState(CHECK_VIOLATION, () -> await(app.query("INSERT INTO objectives (quarter, level, team, title) "
                + "VALUES ('Q4 2026', 'Team', 'Platform', 'Old quarter format')").execute()));
    }

    private static String num(Object value) {
        return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    }

    private static Row one(String sql, Tuple params) throws Exception {
        var rows = await(owner.preparedQuery(sql).execute(params));
        assertEquals(1, rows.size(), sql + " " + params.deepToString());
        return rows.iterator().next();
    }

    private static long count(String sql) throws Exception {
        return await(owner.query(sql).execute()).iterator().next().getLong(0);
    }

    private static void assertSqlState(String expected, ThrowingRunnable action) {
        ExecutionException e = assertThrows(ExecutionException.class, action::run);
        assertTrue(e.getCause() instanceof PgException, String.valueOf(e.getCause()));
        assertEquals(expected, ((PgException) e.getCause()).getSqlState(), e.getCause().getMessage());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
