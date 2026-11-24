package com.pulse.calc.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.modules.okrs.OkrsModule;
import com.pulse.modules.progress.ProgressModule;
import com.pulse.modules.work.WorkModule;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 13.3: {@code work.achievements}, {@code work.nextSteps} and {@code work.progressTrends} through
 * CalcService on the prototype's work, PR and OKR data, with the stand-in Track 3 ports, compared with the
 * prototype's {@code achievements} and {@code nextActions} as {@code rahul@} (a manager) and as the
 * person, and the trends' order (spec "Biggest gain first").
 */
class ProgressFormulasTest
{

    private static TestDb db;

    private static CalcService calc;

    private static Oracle oracle;

    private static PrototypeWork prototype;

    private static List<String> visible;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(ProgressFormulasTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule(), new ProgressModule(), new OkrsModule())));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));

        await(MigrationRunner.seed(db.ownerPool(), catalog, true));

        SqlExecutor owner = new SqlExecutor(db.ownerPool());

        oracle = Oracle.start();

        oracle.eval("S.uid='u-rn'");

        prototype = new PrototypeWork(oracle, owner);

        prototype.loadWork();

        prototype.loadPrs();

        prototype.loadExtraItems();

        prototype.loadOnboarding();

        visible = new ArrayList<>();

        ((JsonArray) oracle.eval("visiblePeople().map(function(p){return p.id})")).forEach(id -> visible.add((String) id));

        List<FormulaModule> modules = new ArrayList<>(FormulaRegistry.discover().modules());

        prototype.replacePorts(modules);

        calc = new CalcService(new SqlExecutor(db.pool(Db.DEFAULT_APP_ROLE)),
                PulseClock.of(Clock.systemUTC(), "2026-09-28", false), FormulaRegistry.of(modules));
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

    @AfterEach
    void signInAsRahulAgain()
    {
        oracle.eval("S.uid='u-rn'");
    }

    @Test
    void achievementsMatchThePrototype() throws Exception
    {
        int earned = 0;

        for (String id : visible)
        {
            JsonArray expected = (JsonArray) oracle.eval("achievements(person('" + id + "')).map(function(a){return a[0]+' | '+a[1]+' | '+a[2]})");

            JsonArray actual = new JsonArray();

            ((JsonArray) await(calc.get(ProgressFormulas.ACHIEVEMENTS, ref(id)))).forEach(a ->
            {
                JsonObject x = (JsonObject) a;

                actual.add(x.getString("title") + " | " + x.getString("detail") + " | " + x.getString("value"));
            });

            assertEquals(expected, actual, "achievements of " + id);

            earned += actual.size();
        }

        assertTrue(earned > 0, "the demo team has achievements");
    }

    @Test
    void nextStepsMatchNextActionsForTheManager() throws Exception
    {
        for (String id : visible)
        {
            JsonArray expected = (JsonArray) oracle.eval("nextActions(person('" + id + "')).map(function(x){return x[0]})");

            JsonArray actual = new JsonArray();

            ((JsonArray) await(calc.get(ProgressFormulas.NEXT_STEPS, ref(id)))).stream().map(s -> (JsonObject) s)
                    .filter(s -> !s.getBoolean("selfOnly"))
                    .forEach(s -> actual.add(s.getString("text")));

            assertEquals(expected, actual, "next steps of " + id + " as the manager");
        }
    }

    @Test
    void thePersonAlsoSeesThePulseCheckInAndReviewsToGive() throws Exception
    {
        oracle.eval("S.uid='u-ps'");

        JsonArray expected = (JsonArray) oracle.eval("nextActions(person('ps')).map(function(x){return x[0]})");

        JsonArray actual = new JsonArray();

        JsonArray steps = (JsonArray) await(calc.get(ProgressFormulas.NEXT_STEPS, ref("ps")));

        steps.forEach(s -> actual.add(((JsonObject) s).getString("text")));

        // the stand-in port answers as the manager (no reviews to give); the prototype adds them for Priya
        List<String> wanted = expected.stream().map(String::valueOf).filter(t -> !t.contains("to give")).toList();

        assertEquals(wanted, actual.stream().map(String::valueOf).toList());
        assertTrue(actual.contains("Weekly pulse check-in for this week"), "this week's pulse is open");

        JsonObject pulse = steps.stream().map(s -> (JsonObject) s).filter(s -> s.getString("text").startsWith("Weekly pulse"))
                .findFirst().orElseThrow();

        assertTrue(pulse.getBoolean("selfOnly"));
        assertEquals("progress", pulse.getJsonObject("target").getString("module"));
    }

    @Test
    void trendsHaveFourQuartersTheOkrsAndThePrStandardByMonth() throws Exception
    {
        JsonObject trends = (JsonObject) await(calc.get(ProgressFormulas.TRENDS, ref("ps")));

        JsonArray index = trends.getJsonArray("index");

        assertEquals(List.of("Q4 25", "Q1 26", "Q2 26", "Q3 26"), index.stream().map(q -> ((JsonObject) q).getString("quarter")).toList());

        JsonArray okrs = (JsonArray) oracle.eval("OKRS.filter(function(o){return o.owner==='ps'&&o.q==='Q3 2026'}).map(function(o){"
                + "return {t:o.obj,p:Math.round(okrProg(o)*100)}})");

        // the seeded objectives share a creation time, so they are compared by title
        java.util.Map<String, Integer> expected = new java.util.TreeMap<>();

        okrs.forEach(o -> expected.put(((JsonObject) o).getString("t"), ((JsonObject) o).getInteger("p")));

        java.util.Map<String, Integer> actual = new java.util.TreeMap<>();

        trends.getJsonArray("okrs").forEach(o -> actual.put(((JsonObject) o).getString("title"),
                (int) Math.round(((JsonObject) o).getDouble("progress") * 100)));

        assertEquals(expected, actual);

        JsonArray months = (JsonArray) oracle.eval("['2026-07','2026-08','2026-09'].map(function(m){return prStats('ps',m).comp})");

        JsonArray prByMonth = trends.getJsonArray("prByMonth");

        assertEquals(List.of("Jul", "Aug", "Sep"), prByMonth.stream().map(m -> ((JsonObject) m).getString("label")).toList());

        for (int i = 0; i < months.size(); i++)
        {
            assertEquals(months.getValue(i), prByMonth.getJsonObject(i).getValue("comp"), "PR standard in " + prByMonth.getJsonObject(i).getString("label"));
        }

        assertFalse(trends.getJsonArray("training").isEmpty() && !((JsonArray) oracle.eval("trPerson('ps').as")).isEmpty());
    }

    @Test
    void cultureValuesAreSortedByBiggestGainFirst() throws Exception
    {
        JsonArray culture = ((JsonObject) await(calc.get(ProgressFormulas.TRENDS, ref("ps")))).getJsonArray("culture");

        double previous = Double.POSITIVE_INFINITY;

        for (Object o : culture)
        {
            Object gain = ((JsonObject) o).getValue("gain");

            double g = gain instanceof Number n ? n.doubleValue() : Double.NEGATIVE_INFINITY;

            assertTrue(g <= previous, "biggest gain first: " + culture.encode());

            previous = g;
        }
    }

    private static JsonObject ref(String prototypeId)
    {
        return new JsonObject().put("person", prototype.people.get(prototypeId));
    }
}
