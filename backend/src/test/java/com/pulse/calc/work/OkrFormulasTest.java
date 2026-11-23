package com.pulse.calc.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
import com.pulse.modules.okrs.OkrsModule;
import com.pulse.modules.work.WorkModule;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 14.2: the OKR formulas through CalcService on the migrated demo seed (S210 equals the
 * prototype's OKRS), compared with the oracle's {@code okrScore} for every seeded person, read by
 * {@code core.pillars}, and dropped from the cache by the {@code work.okrs} topic.
 */
class OkrFormulasTest
{

    private static final String TODAY = "2026-09-28";

    private static TestDb db;

    private static SqlExecutor owner;

    private static CalcService calc;

    private static Oracle oracle;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(OkrFormulasTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule(), new OkrsModule())));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));

        await(MigrationRunner.seed(db.ownerPool(), catalog, true));

        owner = new SqlExecutor(db.ownerPool());

        calc = new CalcService(new SqlExecutor(db.pool(Db.DEFAULT_APP_ROLE)),
                PulseClock.of(Clock.systemUTC(), TODAY, false), FormulaRegistry.discover());

        oracle = Oracle.start();
    }

    @AfterAll
    static void tearDown() throws Exception
    {
        if (oracle != null)
        {
            oracle.close();
        }

        if (db != null)
        {
            db.close();
        }
    }

    @Test
    void pillarFromTheDatabaseMatchesOkrScoreForEverySeededPerson() throws Exception
    {
        JsonArray people = await(owner.queryList("SELECT id, external_id FROM people WHERE source = 'seed' ORDER BY external_id"));

        assertFalse(people.isEmpty());

        int withOkrs = 0;

        for (Object item : people)
        {
            JsonObject person = (JsonObject) item;

            Object port = await(calc.get(OkrFormulas.PILLAR, quarter(person.getString("id"))));

            Object prototype = oracle.call("okrScore", person.getString("external_id"));

            String who = person.getString("external_id");

            if (prototype == null)
            {
                assertTrue(ResultGuard.isNotApplicable(port), who + ": no OKRs in Q3, so no OKR pillar: " + port);

                continue;
            }

            withOkrs++;

            assertInstanceOf(Number.class, port, who);

            assertEquals(Oracle.display(((Number) prototype).doubleValue(), 1), Oracle.display(((Number) port).doubleValue(), 1), who);
        }

        assertEquals(3, withOkrs, "Priya, Arjun and Neha own Q3 objectives in the seed");
    }

    @Test
    void corePillarsReadTheOkrPillar() throws Exception
    {
        String priya = personId("ps");

        double port = ((Number) await(calc.get(OkrFormulas.PILLAR, quarter(priya)))).doubleValue();

        JsonObject pillars = (JsonObject) await(calc.get("core.pillars", quarter(priya)));

        assertEquals(port, pillars.getDouble("okr"), 1e-9);
    }

    @Test
    void summaryGivesTheKpiRowForAPersonAndForTheTeam() throws Exception
    {
        JsonObject priya = (JsonObject) await(calc.get(OkrFormulas.SUMMARY, quarter(personId("ps"))));

        assertEquals(2, priya.getInteger("objectives"));
        assertEquals(5, priya.getInteger("keyResults"));
        assertEquals("2026-Q3", priya.getString("quarter"));
        assertEquals(2, priya.getJsonArray("items").size());
        assertEquals(priya.getDouble("pillar"), ((Number) await(calc.get(OkrFormulas.PILLAR, quarter(personId("ps"))))).doubleValue(), 1e-9);

        JsonObject team = (JsonObject) await(calc.get(OkrFormulas.SUMMARY,
                new JsonObject().put("team", "Platform").put("period", "q:Q3")));

        assertEquals(1, team.getInteger("objectives"));
        assertEquals(3, team.getInteger("keyResults"));
        assertTrue(ResultGuard.isNotApplicable(team.getValue("pillar")) || team.getValue("pillar") == null,
                "a team has no OKR pillar");

        JsonObject empty = (JsonObject) await(calc.get(OkrFormulas.SUMMARY, new JsonObject()
                .put("person", personId("ps")).put("period", "q:2027-Q2")));

        assertEquals(0, empty.getInteger("objectives"));
    }

    @Test
    void anOkrChangeIsSeenAfterTheTopicIsInvalidated() throws Exception
    {
        String neha = personId("nj");

        double before = ((Number) await(calc.get(OkrFormulas.PILLAR, quarter(neha)))).doubleValue();

        String nehasKeyResults = "objective_id IN (SELECT id FROM objectives WHERE title = 'Own the trap-filter module')";

        JsonArray saved = await(owner.queryList("SELECT id, current_value FROM key_results WHERE " + nehasKeyResults));

        try
        {
            await(owner.execute("UPDATE key_results SET current_value = target_value WHERE " + nehasKeyResults,
                    Tuple.tuple()));

            assertEquals(before, ((Number) await(calc.get(OkrFormulas.PILLAR, quarter(neha)))).doubleValue(), 1e-9,
                    "cached until the OKR topic is published");

            calc.invalidate(OkrFormulas.OKRS);

            double after = ((Number) await(calc.get(OkrFormulas.PILLAR, quarter(neha)))).doubleValue();

            assertNotEquals(before, after);

            assertEquals(5.0, after, 1e-9, "every key result achieved");
        }
        finally
        {
            for (Object item : saved)
            {
                JsonObject kr = (JsonObject) item;

                await(owner.execute("UPDATE key_results SET current_value = $2 WHERE id = $1::uuid",
                        Tuple.of(kr.getString("id"), kr.getValue("current_value"))));
            }

            calc.invalidate(OkrFormulas.OKRS);
        }
    }

    @Test
    void badParametersAreRefused()
    {
        ExecutionException month = assertThrows(ExecutionException.class,
                () -> await(calc.get(OkrFormulas.PILLAR, new JsonObject().put("person", personId("ps")).put("period", "m:2026-09"))));

        assertInstanceOf(IllegalArgumentException.class, month.getCause());

        ExecutionException both = assertThrows(ExecutionException.class,
                () -> await(calc.get(OkrFormulas.SUMMARY, quarter(personId("ps")).put("team", "Platform"))));

        assertInstanceOf(IllegalArgumentException.class, both.getCause());
    }

    private static JsonObject quarter(String personId)
    {
        return new JsonObject().put("person", personId).put("period", "q:Q3");
    }

    private static String personId(String externalId) throws Exception
    {
        return await(owner.queryOne("SELECT id FROM people WHERE source = 'seed' AND external_id = '" + externalId + "'"))
                .getString("id");
    }
}
