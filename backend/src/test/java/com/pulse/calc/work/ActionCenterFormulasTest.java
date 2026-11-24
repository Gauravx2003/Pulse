package com.pulse.calc.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
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
 * Task 13.2: the Action center formulas through CalcService on the prototype's work data, with the
 * stand-in Track 3 ports, compared with the prototype as {@code rahul@}: {@code velocity},
 * {@code forecastRows}, {@code capRow}, {@code signals} with {@code riskLevel}, {@code coverage},
 * {@code pulseTrend} and {@code teamWins}; and the spec's scenarios in insight/action-center.
 */
class ActionCenterFormulasTest
{

    private static TestDb db;

    private static CalcService calc;

    private static Oracle oracle;

    private static PrototypeWork prototype;

    /**
     * Rahul's visible people, prototype ids in the prototype's order.
     */
    private static List<String> visible;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(ActionCenterFormulasTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule(), new ProgressModule())));

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

    @Test
    void theNextSprintIsSprint44AfterTheActiveOne() throws Exception
    {
        JsonObject next = (JsonObject) await(calc.get(ActionCenterFormulas.NEXT_SPRINT, new JsonObject()));

        assertEquals("Sprint 44", next.getString("name"));
        assertEquals("2026-10-12", next.getString("from"));
        assertEquals("2026-10-23", next.getString("end"));
    }

    @Test
    void velocityMatchesThePrototype() throws Exception
    {
        for (String id : visible)
        {
            double expected = ((Number) oracle.eval("velocity(person('" + id + "'))")).doubleValue();

            double actual = ((Number) await(calc.get(ActionCenterFormulas.VELOCITY, ref(id)))).doubleValue();

            assertEquals(expected, actual, 0.005, "velocity of " + id);
        }
    }

    @Test
    void theForecastMatchesForecastRows() throws Exception
    {
        JsonArray rows = (JsonArray) oracle.eval("forecastRows(visiblePeople().filter(function(p){return !onboarding(p.id)}))"
                + ".map(function(r){return {id:r.p.id,rem:r.m.rem,cap:r.capH,pct:r.pct,spill:r.spill.map(function(t){return t.key})}})");

        assertFalse(rows.isEmpty());

        int carried = 0;

        for (Object o : rows)
        {
            JsonObject row = (JsonObject) o;

            JsonObject forecast = (JsonObject) await(calc.get(ActionCenterFormulas.FORECAST, ref(row.getString("id"))));

            String who = row.getString("id");

            assertEquals(row.getDouble("rem"), forecast.getDouble("remaining"), 0.05, "remaining of " + who);
            assertEquals(row.getDouble("cap"), forecast.getDouble("capacity"), 0.05, "capacity of " + who);
            assertEquals(row.getInteger("pct"), forecast.getInteger("pct"), "fits of " + who);

            JsonArray keys = new JsonArray();

            forecast.getJsonArray("carry").forEach(c -> keys.add(((JsonObject) c).getString("key")));

            // what does not fit; the order of issues due the same day is the prototype's task order, which
            // the data does not carry (ours is by key), so the carry-over is compared as a set
            assertEquals(sorted(row.getJsonArray("spill")), sorted(keys), "carry-over of " + who);

            carried += keys.size();
        }

        // spec "Does not fit": someone's remaining work exceeds the capacity left, and it is listed
        assertTrue(carried > 0, "the demo sprint has issues likely to carry over");
    }

    @Test
    void nextSprintCapacityMatchesCapRow() throws Exception
    {
        for (String id : visible)
        {
            JsonObject expected = (JsonObject) oracle.eval("(function(r){return {wd:r.wd,leave:r.leave,vis:r.vis,trh:r.trh,mnh:r.mnh,"
                    + "focusD:r.focusD,sp:r.sp,nj:!!r.nj}})(capRow(person('" + id + "')))");

            JsonObject row = (JsonObject) await(calc.get(ActionCenterFormulas.CAPACITY, ref(id)));

            assertEquals(expected.getInteger("wd"), row.getInteger("days"), "days of " + id);
            assertEquals(expected.getInteger("leave"), row.getInteger("leave"), "leave of " + id);
            assertEquals(expected.getInteger("vis"), row.getInteger("visits"), "visits of " + id);
            assertEquals(expected.getInteger("trh"), row.getInteger("trainingHours"), "training hours of " + id);
            assertEquals(expected.getDouble("mnh"), row.getDouble("mentoringHours"), 0.01, "mentoring hours of " + id);
            assertEquals(expected.getDouble("focusD"), row.getDouble("focusDays"), 0.051, "focus days of " + id);
            assertEquals(expected.getBoolean("nj"), row.getBoolean("newJoiner"), "new joiner " + id);

            if (expected.getBoolean("nj"))
            {
                assertTrue(ResultGuard.isNotApplicable(row.getValue("suggestedPoints")), "no SP suggestion for a new joiner");
            }
            else
            {
                assertEquals(expected.getInteger("sp"), row.getInteger("suggestedPoints"), "suggested SP of " + id);
            }
        }
    }

    @Test
    void peopleSignalsAndRiskMatchThePrototype() throws Exception
    {
        boolean anyHigh = false;

        for (String id : visible)
        {
            // "Performance index falling" needs core.index (task 4.3, Track 1) and is left out here; its rule
            // is checked in signalsAreConversationPromptsWithAReasonAndANextStep
            JsonObject expected = (JsonObject) oracle.eval("(function(p){var s=signals(p).filter(function(x){return x[1]!=="
                    + "'Performance index falling'});return {labels:s.map(function(x){return x[1]}),"
                    + "risk:riskLevel(s)[1],next:s.length?s[0][3]:'Keep going'}})(person('" + id + "'))");

            JsonObject actual = (JsonObject) await(calc.get(ActionCenterFormulas.SIGNALS, ref(id)));

            JsonArray labels = new JsonArray();

            JsonArray kept = new JsonArray();

            actual.getJsonArray("signals").stream().map(s -> (JsonObject) s)
                    .filter(s -> !"Performance index falling".equals(s.getString("label")))
                    .forEach(s ->
                    {
                        labels.add(s.getString("label"));

                        kept.add(s);
                    });

            String risk = ActionCenter.risk(kept).getString("risk");

            assertEquals(expected.getJsonArray("labels"), labels, "signals of " + id);
            assertEquals(expected.getString("risk"), risk, "risk of " + id);
            assertEquals(expected.getString("next"), kept.isEmpty() ? "Keep going" : kept.getJsonObject(0).getString("next"), "next step of " + id);

            anyHigh |= "High".equals(risk);
        }

        assertTrue(anyHigh, "the demo team has someone at high risk");
    }

    @Test
    void newJoinersOnOnboardingAreNotFlaggedForUtilization() throws Exception
    {
        JsonArray joiners = (JsonArray) oracle.eval("visiblePeople().filter(function(p){return isNewJoiner(p.id)}).map(function(p){return p.id})");

        assertFalse(joiners.isEmpty(), "the demo team has a new joiner");

        for (Object id : joiners)
        {
            JsonObject actual = (JsonObject) await(calc.get(ActionCenterFormulas.SIGNALS, ref((String) id)));

            for (Object s : actual.getJsonArray("signals"))
            {
                String label = ((JsonObject) s).getString("label");

                assertFalse(label.equals("Sustained overload") || label.equals("Under-used"), id + " flagged for utilization: " + label);
            }
        }
    }

    @Test
    void skillsCoverageMatchesThePrototype() throws Exception
    {
        JsonObject result = (JsonObject) await(calc.get(ActionCenterFormulas.COVERAGE, team()));

        JsonArray expected = (JsonArray) oracle.eval("(function(ps){var cv=coverage(ps);return AREAS.map(function(a){var n=Object.keys(cv[a])"
                + ".length;return {area:a,n:n,mark:n<=1?'Single point of failure':n===2?'Thin':'Covered'}})})(visiblePeople())");

        JsonArray areas = result.getJsonArray("areas");

        assertEquals(expected.size(), areas.size());

        for (int i = 0; i < expected.size(); i++)
        {
            JsonObject e = expected.getJsonObject(i);

            JsonObject a = areas.getJsonObject(i);

            assertEquals(e.getString("area"), a.getString("area"));
            assertEquals(e.getInteger("n"), a.getInteger("people"), "people in " + e.getString("area"));
            assertEquals(e.getString("mark"), a.getString("coverage"), "coverage of " + e.getString("area"));
        }

        // spec "Single owner": one person touched quota, so it is a single point of failure with a pairing
        JsonObject quota = areas.stream().map(o -> (JsonObject) o).filter(a -> "quota".equals(a.getString("area"))).findFirst().orElseThrow();

        assertEquals("Single point of failure", quota.getString("coverage"));
        assertTrue(quota.getString("suggestion").matches("Pair \\S+ with Arjun on the next quota issue"), quota.getString("suggestion"));

        // the columns leave new joiners out
        long joiners = ((Number) oracle.eval("visiblePeople().filter(function(p){return isNewJoiner(p.id)}).length")).longValue();

        assertEquals(visible.size() - joiners, result.getJsonArray("people").size());
    }

    @Test
    void theTeamPulseTrendMatchesPulseTrend() throws Exception
    {
        JsonObject result = (JsonObject) await(calc.get(ActionCenterFormulas.TEAM_PULSE, team()));

        JsonArray expected = (JsonArray) oracle.eval("(function(ps){var wks={};ps.forEach(function(p){pulseOf(p.id).forEach(function(x){"
                + "(wks[x.week]=wks[x.week]||[]).push(x)})});return Object.keys(wks).sort().map(function(k){var a=wks[k];return {week:k,"
                + "mood:Math.round(a.reduce(function(s,x){return s+x.mood},0)/a.length*10)/10,"
                + "load:Math.round(a.reduce(function(s,x){return s+x.load},0)/a.length*10)/10}})})(visiblePeople())");

        JsonArray weeks = result.getJsonArray("weeks");

        assertEquals(expected.size(), weeks.size());
        assertEquals(ActionCenterFormulas.PULSE_WEEKS, weeks.size());

        for (int i = 0; i < expected.size(); i++)
        {
            JsonObject e = expected.getJsonObject(i);

            JsonObject w = weeks.getJsonObject(i);

            assertEquals(e.getString("week"), w.getString("week"));
            assertEquals(e.getDouble("mood"), w.getDouble("mood"), 0.001, "mood in " + e.getString("week"));
            assertEquals(e.getDouble("load"), w.getDouble("workload"), 0.001, "workload in " + e.getString("week"));
        }

        long low = ((Number) oracle.eval("visiblePeople().map(function(p){return lastPulse(p.id)}).filter(function(x){"
                + "return x&&(x.mood<=2||x.load>=5)}).length")).longValue();

        assertEquals(low, result.getJsonArray("low").size());
    }

    @Test
    void teamWinsHaveTheKudosVisitsTrainingAndProactiveFixesOfThePrototype() throws Exception
    {
        JsonArray wins = (JsonArray) await(calc.get(ActionCenterFormulas.WINS, team()));

        JsonArray expected = (JsonArray) oracle.eval("teamWins(visiblePeople()).map(function(w){return w.who+' | '+w.what})");

        List<String> actual = new ArrayList<>();

        wins.forEach(w -> actual.add(((JsonObject) w).getString("who") + " | " + ((JsonObject) w).getString("what")));

        List<String> wanted = new ArrayList<>();

        expected.forEach(w -> wanted.add((String) w));

        assertEquals(wanted.stream().sorted().toList(), actual.stream().sorted().toList());

        for (int i = 1; i < wins.size(); i++)
        {
            String before = String.valueOf(wins.getJsonObject(i - 1).getValue("at"));

            String after = String.valueOf(wins.getJsonObject(i).getValue("at"));

            assertTrue(before.compareTo(after) >= 0 || !before.matches("\\d{4}-.*") || !after.matches("\\d{4}-.*"), "newest first");
        }
    }

    @Test
    void signalsAreConversationPromptsWithAReasonAndANextStep()
    {
        JsonObject in = new JsonObject()
                .put("util", 150).put("closed", new JsonArray().add(110).add(90).add(105))
                .put("newJoiner", false).put("weekend", 2).put("share", 35).put("discipline", 2.6)
                .put("lastOneOnOne", "2026-09-08").put("oneOnOneDays", 14)
                .put("pulses", new JsonArray().add(new JsonObject().put("mood", 2).put("workload", 5).put("note", "")))
                .put("indexPrevious", 3.9).put("indexNow", 3.6)
                .put("kudos", 0).put("trainingBehind", new JsonArray().add(new JsonObject().put("plan", "SE onboarding").put("overdue", 2)))
                .put("nextSprint", "Sprint 44");

        JsonArray signals = ActionCenter.signals(in, new JsonObject().put("over", 120).put("under", 60).put("unplannedAlert", 30));

        List<String> labels = new ArrayList<>();

        signals.forEach(s -> labels.add(((JsonObject) s).getString("label")));

        assertEquals(List.of("Sustained overload", "Weekend work while overloaded", "High unplanned work", "Jira updates lagging",
                "1:1 overdue", "Pulse check-in low", "Performance index falling", "No recognition recently", "Training behind"), labels);

        signals.forEach(s ->
        {
            assertFalse(((JsonObject) s).getString("reason").isBlank());
            assertFalse(((JsonObject) s).getString("next").isBlank());
        });

        assertEquals("Over 100% in 3 of the last 4 sprints (now 150%)", signals.getJsonObject(0).getString("reason"));
        assertEquals("High", ActionCenter.risk(signals).getString("risk"));

        // a new joiner is not flagged for utilization or recognition
        JsonArray joiner = ActionCenter.signals(in.copy().put("newJoiner", true), new JsonObject());

        joiner.forEach(s -> assertFalse(List.of("Sustained overload", "No recognition recently").contains(((JsonObject) s).getString("label"))));

        // risk levels
        assertEquals("OK", ActionCenter.risk(new JsonArray()).getString("risk"));
        assertEquals("Low", ActionCenter.risk(new JsonArray().add(new JsonObject().put("tone", "accent"))).getString("risk"));
        assertEquals("Watch", ActionCenter.risk(new JsonArray().add(new JsonObject().put("tone", "accent"))
                .add(new JsonObject().put("tone", "info"))).getString("risk"));
    }

    @Test
    void anIssueThatDoesNotFitCarriesOverInDueDateOrder()
    {
        JsonObject live = new JsonObject().put("cap", 2).put("rem", 20.0).put("openIssues", new JsonArray()
                .add(new JsonObject().put("key", "A-2").put("rem", 6.0).put("due", "2026-10-02"))
                .add(new JsonObject().put("key", "A-1").put("rem", 8.0).put("due", "2026-09-30"))
                .add(new JsonObject().put("key", "A-3").put("rem", 6.0)));

        JsonObject forecast = ActionCenter.forecast(live, 6.0);

        assertEquals(12.0, forecast.getDouble("capacity"));
        assertEquals(60, forecast.getInteger("pct"));
        // A-1 (8 h) fits, A-2 (14 h in total) and A-3 (no due date, last) do not
        assertEquals(List.of("A-2", "A-3"), forecast.getJsonArray("carry").stream().map(c -> ((JsonObject) c).getString("key")).toList());
    }

    private static List<String> sorted(JsonArray keys)
    {
        return keys.stream().map(String::valueOf).sorted().toList();
    }

    private static JsonObject ref(String prototypeId)
    {
        return new JsonObject().put("person", prototype.people.get(prototypeId));
    }

    private static JsonObject team()
    {
        return new JsonObject().put("people", String.join(",", visible.stream().map(prototype.people::get).toList()));
    }
}
