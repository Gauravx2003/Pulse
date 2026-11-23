package com.pulse.calc.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 10.2: the {@code work.disciplineInputs} port through {@code core.discipline} on the prototype's
 * work data, PRs and Discipline seed (V250/S250), compared with the prototype's {@code discMetrics} for
 * every person: daily-update compliance, base band, stale tickets, rules c-g and the adjustments of
 * rules a-g. Rule h (attendance) is Track 3's port and is left out. Spec work/pull-requests "Discipline
 * g": a PR standard below 85% that takes process adherence under 85% shows rule g as Improve.
 */
class DisciplineFormulasTest
{

    /**
     * Rule h's labels: attendance comes from Track 3's people.attendanceDiscipline port.
     */
    private static final Set<String> ATTENDANCE = Set.of("Attendance & leave", "Absent without leave");

    private static TestDb db;

    private static CalcService calc;

    private static Oracle oracle;

    private static PrototypeWork prototype;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(DisciplineFormulasTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule())));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));

        await(MigrationRunner.seed(db.ownerPool(), catalog, true));

        SqlExecutor owner = new SqlExecutor(db.ownerPool());

        oracle = Oracle.start();

        oracle.eval("S.uid='u-rn'");

        prototype = new PrototypeWork(oracle, owner);

        prototype.loadWork();

        prototype.loadPrs();

        List<FormulaModule> modules = new ArrayList<>(FormulaRegistry.discover().modules());

        modules.add(prototype.ports());

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

    @Test
    void disciplineMatchesDiscMetricsForEveryPerson() throws Exception
    {
        JsonArray expected = (JsonArray) oracle.eval("Object.keys(DISC).map(function(id){var dm=discMetrics(person(id));"
                + "return {id:id,comp:dm.comp,base:dm.base,stale:dm.stale,replan:dm.replan,status:dm.status,logSame:dm.logSame,"
                + "bulk:dm.bulk,block:dm.block,proc:dm.proc,adj:dm.adj.map(function(a){return a[0]})}})");

        for (Object item : expected)
        {
            JsonObject p = (JsonObject) item;

            JsonObject m = metrics(p.getString("id"));

            String who = p.getString("id");

            assertEquals(p.getInteger("comp"), m.getInteger("compliance"), who + " compliance");
            assertEquals(p.getInteger("base"), m.getInteger("base"), who + " base");
            assertEquals(p.getInteger("stale"), m.getInteger("stale"), who + " stale");
            assertEquals(p.getInteger("replan"), m.getInteger("replan"), who + " replan");
            assertEquals(p.getInteger("status"), m.getInteger("status"), who + " status");
            assertEquals(p.getInteger("logSame"), m.getInteger("logSame"), who + " logSame");
            assertEquals(p.getInteger("bulk"), m.getInteger("bulk"), who + " bulk");
            assertEquals(p.getInteger("block"), m.getInteger("block"), who + " block");
            assertEquals(p.getInteger("proc"), m.getInteger("process"), who + " process");
            assertEquals(labels(p.getJsonArray("adj")), labels(m.getJsonArray("adjustments").stream()
                    .map(a -> ((JsonObject) a).getString("label")).toList()), who + " adjustments");
        }
    }

    @Test
    void aPrStandardBelow85ShowsRuleGAsImprove() throws Exception
    {
        JsonObject priya = metrics("ps");

        assertTrue(review("ps") < 85, "Priya's PR standard this month");
        assertTrue(priya.getInteger("process") < 85);
        assertTrue(rules(priya).contains("g"), "rule g: Improve");

        JsonObject sara = metrics("sk");

        assertTrue(review("sk") < 85, "Sara's PR standard is under 85 too");
        assertTrue(sara.getInteger("process") >= 85, "but DoD and stand-up keep her process adherence at target");
        assertFalse(rules(sara).contains("g"));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject metrics(String id) throws Exception
    {
        JsonObject discipline = (JsonObject) await(calc.get("core.discipline", new JsonObject().put("person", prototype.people.get(id))));

        return discipline.getJsonObject("metrics");
    }

    private static int review(String id) throws Exception
    {
        JsonObject inputs = (JsonObject) await(calc.get(DisciplineFormulas.INPUTS, new JsonObject().put("person", prototype.people.get(id))));

        return inputs.getInteger("review");
    }

    private static List<String> rules(JsonObject metrics)
    {
        return metrics.getJsonArray("adjustments").stream().map(a -> ((JsonObject) a).getString("rule")).toList();
    }

    private static List<String> labels(Iterable<?> labels)
    {
        List<String> out = new ArrayList<>();

        labels.forEach(l -> out.add(String.valueOf(l)));

        out.removeIf(ATTENDANCE::contains);

        return out;
    }
}
