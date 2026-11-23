package com.pulse.modules.effort;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.work.EffortFormulas;
import com.pulse.modules.work.WorkModule;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 11.2: confirming through {@link EffortService} moves the culture bonus on the next culture read
 * (the cache is dropped), and the monthly cap keeps the excess from scoring. Runs on the Track 2 seed with
 * a real CalcService; Dev Patel has no confirmed points in September, Priya Shah already has 5.5.
 */
class EffortCultureTest
{

    private static final String QUARTER = "q:Q3";

    private static TestDb db;

    private static SqlExecutor owner;

    private static CalcService calc;

    private static EffortService effort;

    private static JsonObject rahul;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(EffortCultureTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule(), new EffortModule())));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));

        await(MigrationRunner.seed(db.ownerPool(), catalog, true));

        owner = new SqlExecutor(db.ownerPool());

        PgPool app = db.pool(Db.DEFAULT_APP_ROLE);

        calc = new CalcService(new SqlExecutor(app), PulseClock.of(Clock.systemUTC(), "2026-09-28", false), FormulaRegistry.discover());

        PolicyService policy = new PolicyService(new SqlExecutor(app), Set.of(), List.of(), false);

        effort = new EffortService(new ModuleContext(db.vertx(), new JsonObject(), app, policy, calc, null));

        rahul = await(owner.queryOne("""
                SELECT u.id, u.name, r.name AS role, p.id AS "personId",
                       array(SELECT permission FROM role_permissions WHERE role_id = r.id) AS permissions
                FROM users u JOIN roles r ON r.id = u.role_id LEFT JOIN people p ON p.user_id = u.id
                WHERE u.email = 'rahul@pulse.demo'""", Tuple.tuple()));
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
    void confirmingA1PointItemCrediting2ValuesRaisesBothOnTheNextCultureRead() throws Exception
    {
        String dev = person("dp");

        JsonArray before = culture(dev);

        double portBefore = scoredPoints(dev, "2026-09");

        confirm("dp", "PR #799");

        JsonArray after = culture(dev);

        assertEquals(portBefore + 1, scoredPoints(dev, "2026-09"), 1e-9, "the whole point scores: Dev is far below 6");

        // Resourcefulness (index 9) and Risk Appetite (index 4) each gain 0.5 point × 0.05 = 0.025, shown to 2 decimals
        for (int value : new int[] {4, 9})
        {
            double gain = after.getDouble(value) - before.getDouble(value);

            assertTrue(gain > 0.01 && gain < 0.04, "value " + value + " gained " + gain);
        }

        for (int value = 0; value < 11; value++)
        {
            if (value != 4 && value != 9)
            {
                assertEquals(before.getDouble(value), after.getDouble(value), 1e-9, "value " + value + " is not credited");
            }
        }
    }

    @Test
    void pointsAboveSixInAMonthDoNotScore() throws Exception
    {
        String priya = person("ps");

        assertEquals(5.5, scoredPoints(priya, "2026-09"), 1e-9, "the seed's confirmed September points");

        confirm("ps", "PLAT-2263");

        assertEquals(6.0, scoredPoints(priya, "2026-09"), 1e-9, "only 0.5 of the 1-point item fits under the cap");
        assertEquals(0.5, cappedPoints(priya, "2026-09"), 1e-9);

        JsonArray before = culture(priya);

        confirm("ps", "PR #805");

        assertEquals(6.0, scoredPoints(priya, "2026-09"), 1e-9, "a full month scores nothing more");
        assertEquals(1.5, cappedPoints(priya, "2026-09"), 1e-9);
        assertEquals(before, culture(priya), "Resourcefulness does not move once the month is full");
    }

    // ------------------------------------------------------------------ helpers

    private static void confirm(String person, String ref) throws Exception
    {
        JsonObject item = await(owner.queryOne("""
                SELECT x.id, x.version FROM extra_items x JOIN people p ON p.id = x.person_id
                WHERE p.source = 'seed' AND p.external_id = $1 AND x.ref = $2""", Tuple.of(person, ref)));

        JsonObject saved = await(effort.decide(rahul, UUID.fromString(item.getString("id")), EffortService.CONFIRMED,
                item.getInteger("version"), new JsonObject()));

        assertEquals("confirmed", saved.getString("state"));
    }

    private static JsonArray culture(String person) throws Exception
    {
        return (JsonArray) await(calc.get("core.culture", new JsonObject().put("person", person).put("period", QUARTER)));
    }

    private static double scoredPoints(String person, String month) throws Exception
    {
        return sum(person, month, "points");
    }

    private static double cappedPoints(String person, String month) throws Exception
    {
        return sum(person, month, "capped");
    }

    /**
     * The sum of a field over the person's confirmed items of a month, from the culture port.
     */
    private static double sum(String person, String month, String field) throws Exception
    {
        JsonArray items = (JsonArray) await(calc.get(EffortFormulas.CULTURE_ITEMS,
                new JsonObject().put("person", person).put("period", "m:" + month)));

        double total = 0;

        for (Object item : items)
        {
            JsonObject it = (JsonObject) item;

            if ("confirmed".equals(it.getString("state")))
            {
                total += it.getDouble(field);
            }
        }

        return total;
    }

    private static String person(String externalId) throws Exception
    {
        return await(owner.queryOne("SELECT id FROM people WHERE source = 'seed' AND external_id = $1", Tuple.of(externalId)))
                .getString("id");
    }
}
