package com.pulse.modules.okrs;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.modules.work.WorkModule;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import com.pulse.platform.module.ModuleContext;
import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 14.3: the off-track next step registered by {@link OkrsModule}, on the demo seed with "today"
 * pinned to 28 Sep 2026, equal to the "OKR off track" items of the prototype's {@code nextActions}.
 */
class OkrOffTrackProviderTest
{

    private static TestDb db;

    private static SqlExecutor owner;

    private static ModuleContext ctx;

    private static Oracle oracle;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(OkrOffTrackProviderTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule(), new OkrsModule())));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));

        await(MigrationRunner.seed(db.ownerPool(), catalog, true));

        owner = new SqlExecutor(db.ownerPool());

        PgPool app = db.pool(Db.DEFAULT_APP_ROLE);

        CalcService calc = new CalcService(new SqlExecutor(app), PulseClock.of(Clock.systemUTC(), "2026-09-28", false),
                FormulaRegistry.discover());

        ctx = new ModuleContext(db.vertx(), new JsonObject(), app, null, calc, null);

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
    void theModuleRegistersTheProvider()
    {
        assertTrue(new OkrsModule().actionProviders().stream().anyMatch(p -> OkrOffTrackProvider.KEY.equals(p.key())));
    }

    @Test
    void offTrackItemsMatchThePrototypeForEverySeededPerson() throws Exception
    {
        JsonArray people = await(owner.queryList("SELECT id, external_id FROM people WHERE source = 'seed' ORDER BY external_id"));

        int found = 0;

        for (Object item : people)
        {
            JsonObject person = (JsonObject) item;

            List<String> ours = new ArrayList<>();

            for (JsonObject action : await(new OkrOffTrackProvider().actionsFor(user(person.getString("id")), ctx)))
            {
                ours.add(action.getString("title"));

                assertEquals("okrs", action.getString("route"));
            }

            // nextActions needs a signed-in user; the OKR part does not depend on who it is (Rahul here).
            oracle.eval("S.uid = 'u-rn'");

            JsonArray expected = (JsonArray) oracle.eval("nextActions(person('" + person.getString("external_id") + "'))"
                    + ".filter(function(x){return x[0].indexOf('OKR off track')===0}).map(function(x){return x[0]})");

            assertEquals(expected.getList(), ours, person.getString("external_id"));

            found += ours.size();
        }

        oracle.reset();

        assertEquals(1, found, "Arjun's quota objective (32%) is the only Q3 objective off track in the seed");
    }

    @Test
    void aUserWithoutAProfileHasNoOkrSteps() throws Exception
    {
        assertTrue(await(new OkrOffTrackProvider().actionsFor(new JsonObject().put("id", "x"), ctx)).isEmpty());
    }

    private static JsonObject user(String personId)
    {
        return new JsonObject().put("personId", personId).put("permissions", new JsonArray().add("okr.own"));
    }
}
