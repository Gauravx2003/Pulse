package com.pulse.platform.db;

import com.pulse.modules.okrs.OkrsModule;
import com.pulse.modules.roadmap.RoadmapModule;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 15.1: V220 applied after V210, and the demo road map seeded for Priya as in the prototype.
 */
class RoadmapSeedTest
{

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String CHECK_VIOLATION = "23514";

    private static final String PRIYA = "SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'";

    private static TestDb db;

    private static PgPool owner;

    private static PgPool app;

    private static List<SqlScript> migrated;

    private static List<SqlScript> seeded;

    @BeforeAll
    static void load() throws Exception
    {
        db = TestDb.open();

        owner = db.ownerPool();

        ScriptCatalog catalog = ScriptCatalog.load(RoadmapSeedTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule(), new OkrsModule(), new RoadmapModule())));

        migrated = await(MigrationRunner.migrate(owner, catalog));

        seeded = await(MigrationRunner.seed(owner, catalog));

        app = db.pool(Db.DEFAULT_APP_ROLE);
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
    void v220AppliesAfterV210AndItsSeedAfterThePeopleSeed()
    {
        assertEquals(List.of(100, 200, 210, 220), migrated.stream().map(SqlScript::version).toList());

        List<Integer> seeds = seeded.stream().map(SqlScript::version).toList();

        assertTrue(seeds.contains(110) && seeds.indexOf(110) < seeds.indexOf(220), seeds.toString());
    }

    @Test
    void priyaHasTheSeededRoadMapInPrototypeOrder() throws Exception
    {
        JsonArray prototype = json("roadmap.json");

        List<String> expected = new ArrayList<>();

        for (Object item : prototype)
        {
            JsonObject m = (JsonObject) item;

            if ("ps".equals(m.getString("person")))
            {
                expected.add(m.getString("title") + "|" + m.getString("when") + "|" + m.getString("st") + "|"
                        + m.getValue("prog") + "|" + m.getString("value"));
            }
        }

        List<String> actual = new ArrayList<>();

        for (Row r : rows("SELECT title, when_label, stage, progress_pct, culture_value FROM milestones "
                + "WHERE person_id = (" + PRIYA + ") ORDER BY sort_order"))
        {
            actual.add(r.getString(0) + "|" + r.getString(1) + "|" + r.getString(2) + "|"
                    + r.getValue(3) + "|" + r.getString(4));
        }

        assertTrue(!expected.isEmpty(), "the prototype road map for Priya should not be empty");

        assertEquals(expected, actual);
    }

    @Test
    void seededMilestonesAreConfirmedByTheCurrentManager() throws Exception
    {
        assertEquals(0L, count("SELECT count(*) FROM milestones m WHERE m.status <> 'confirmed' "
                + "OR m.confirmed_by IS DISTINCT FROM (SELECT id FROM users WHERE email = 'rahul@pulse.demo')"));

        Row linked = rows("SELECT linked_issues FROM milestones WHERE cardinality(linked_issues) > 0").iterator().next();

        assertEquals(List.of("PLAT-2231", "PLAT-2201"), List.of(linked.getArrayOfStrings(0)));
    }

    @Test
    void priyaTargetsSeniorEngineerL4InApril2027() throws Exception
    {
        Row target = rows("SELECT target_level, target_title, target_on::text, cross_team_epic_key "
                + "FROM promotion_targets WHERE person_id = (" + PRIYA + ")").iterator().next();

        assertEquals("L4", target.getString(0));
        assertEquals("Senior Engineer", target.getString(1));
        assertEquals("2027-04-01", target.getString(2));
        assertEquals(null, target.getString(3), "the cross-team epic criterion starts Open");
    }

    @Test
    void skillsMatrixEqualsThePrototype() throws Exception
    {
        List<String> expected = new ArrayList<>();

        for (Object item : json("skills.json"))
        {
            JsonObject s = (JsonObject) item;

            expected.add(s.getString("person") + "|" + s.getString("skill") + "|" + s.getInteger("level") + "|"
                    + s.getInteger("target"));
        }

        List<String> actual = new ArrayList<>();

        for (Row r : rows("SELECT p.external_id, s.skill, s.current_level, s.needed_level FROM skills_matrix s "
                + "JOIN people p ON p.id = s.person_id ORDER BY p.external_id, s.sort_order"))
        {
            actual.add(r.getString(0) + "|" + r.getString(1) + "|" + r.getInteger(2) + "|" + r.getInteger(3));
        }

        assertEquals(expected, actual);
    }

    @Test
    void constraintsGuardTheRoadMap()
    {
        assertSqlState(CHECK_VIOLATION, () -> await(app.query("INSERT INTO milestones "
                + "(person_id, sort_order, title, when_label, progress_pct) "
                + "SELECT (" + PRIYA + "), 9, 'Too far', 'Q1 2027', 120").execute()));

        assertSqlState(CHECK_VIOLATION, () -> await(app.query("INSERT INTO milestones "
                + "(person_id, sort_order, title, when_label, status, confirmed_at) "
                + "SELECT (" + PRIYA + "), 9, 'Proposed but dated', 'Q1 2027', 'proposed', now()").execute()));

        assertSqlState(UNIQUE_VIOLATION, () -> await(app.query("INSERT INTO skills_matrix "
                + "(person_id, sort_order, skill, current_level, needed_level) "
                + "SELECT (" + PRIYA + "), 9, 'SYSTEM DESIGN', 3, 4").execute()));

        assertSqlState(CHECK_VIOLATION, () -> await(app.query("INSERT INTO skills_matrix "
                + "(person_id, sort_order, skill, current_level, needed_level) "
                + "SELECT (" + PRIYA + "), 9, 'Kubernetes', 6, 4").execute()));

        assertSqlState(UNIQUE_VIOLATION, () -> await(app.query("INSERT INTO promotion_targets "
                + "(person_id, target_level, target_title, target_on) "
                + "SELECT (" + PRIYA + "), 'L5', 'Staff Engineer', '2028-04-01'").execute()));
    }

    private static JsonArray json(String file) throws Exception
    {
        return new JsonArray(Files.readString(Path.of("..", "seed", "extracted", file)));
    }

    private static Iterable<Row> rows(String sql) throws Exception
    {
        return await(owner.query(sql).execute());
    }

    private static long count(String sql) throws Exception
    {
        return rows(sql).iterator().next().getLong(0);
    }

    private static void assertSqlState(String expected, ThrowingRunnable action)
    {
        ExecutionException e = assertThrows(ExecutionException.class, action::run);

        assertTrue(e.getCause() instanceof PgException, String.valueOf(e.getCause()));

        assertEquals(expected, ((PgException) e.getCause()).getSqlState(), e.getCause().getMessage());
    }

    @FunctionalInterface
    private interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
