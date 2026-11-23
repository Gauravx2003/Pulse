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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 9.1: {@code work.teamLive} and {@code work.teamPeriod} through CalcService on the prototype's work
 * data, with the stand-in Track 3 ports (leave, visits, onboarding), compared with the prototype's Team
 * utilization pages rendered as {@code rahul@}: {@code pTeam} for the live sprint and {@code pTeamPer}
 * for a closed sprint, releases and months.
 */
class TeamFormulasTest
{

    private static TestDb db;

    private static CalcService calc;

    private static Oracle oracle;

    private static Map<String, String> people;

    private static String team;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(TeamFormulasTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule())));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));

        await(MigrationRunner.seed(db.ownerPool(), catalog, true));

        SqlExecutor owner = new SqlExecutor(db.ownerPool());

        oracle = Oracle.start();

        oracle.eval("S.uid='u-rn'");

        PrototypeWork prototype = new PrototypeWork(oracle, owner);

        prototype.loadWork();

        prototype.loadOnboarding();

        people = prototype.people;

        team = String.join(",", people.values());

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
    void theLiveSprintMatchesPTeam() throws Exception
    {
        JsonObject live = get(TeamFormulas.TEAM_LIVE, new JsonObject().put("people", team));

        String page = (String) oracle.eval("pTeam()");

        List<String> stats = stats(page);

        JsonObject kpis = live.getJsonObject("kpis");

        assertEquals(stats.get(0), kpis.getInteger("utilization") + "%");
        assertEquals(stats.get(1), kpis.getJsonArray("overloaded").size() + "/ " + kpis.getInteger("people"));
        assertEquals(stats.get(2), kpis.getJsonArray("underUtilized").size() + "/ " + kpis.getInteger("countable"));
        assertEquals(stats.get(3), kpis.getInteger("stale") + "/ " + kpis.getInteger("open") + " open");

        // spec work/team-utilization "Suggestion text"
        assertEquals("Move PLAT-2198 (24 h) from Priya Shah (171%) to Dev Patel (52%). New levels ≈ 104% and 96%.",
                live.getJsonObject("suggestion").getString("text"));

        // the people table: the prototype's order and utilization
        JsonArray expected = (JsonArray) oracle.eval("visiblePeople().map(function(p){return {id:p.id,u:metrics(p).util}})"
                + ".sort(function(a,b){return b.u-a.u})");

        JsonArray table = live.getJsonArray("people");

        assertEquals(expected.size(), table.size());

        Map<String, Integer> utilization = new HashMap<>();

        for (Object item : expected)
        {
            JsonObject row = (JsonObject) item;

            utilization.put(people.get(row.getString("id")), row.getInteger("u"));
        }

        for (Object item : table)
        {
            JsonObject row = (JsonObject) item;

            assertEquals(utilization.get(row.getString("id")), (int) Math.round(row.getDouble("utilization")), row.getString("name"));
        }
    }

    @Test
    void heatmapLabelsMatchPTeam() throws Exception
    {
        JsonObject live = get(TeamFormulas.TEAM_LIVE, new JsonObject().put("people", team));

        // The cells of pTeam's heatmap without their values: before today the prototype reads p.logged, its 5-day
        // sample, where the port reads the worklogs (the deviation recorded for the daily load in WorkViewFormulasTest).
        JsonArray expected = (JsonArray) oracle.eval("visiblePeople().map(function(p){var u=metrics(p).util;"
                + "return {id:p.id,cells:workBetween(SPRINT.start,SPRINT.end).map(function(s){"
                + "if(JOINED[p.id]&&s<JOINED[p.id])return '—';if(p.leave.indexOf(s)>=0)return 'Leave';if(visitOn(p.id,s))return 'Visit';"
                + "if(onboarding(p.id))return 'Training';return s<TODAY?'logged':u+'%'})}})");

        Map<String, JsonArray> rows = new HashMap<>();

        for (Object item : live.getJsonObject("heatmap").getJsonArray("rows"))
        {
            rows.put(((JsonObject) item).getString("id"), ((JsonObject) item).getJsonArray("cells"));
        }

        for (Object item : expected)
        {
            JsonObject person = (JsonObject) item;

            JsonArray cells = rows.get(people.get(person.getString("id")));

            JsonArray labels = person.getJsonArray("cells");

            assertEquals(labels.size(), cells.size(), person.getString("id"));

            for (int i = 0; i < labels.size(); i++)
            {
                JsonObject cell = cells.getJsonObject(i);

                String actual = cell.getString("label") != null ? cell.getString("label")
                        : "logged".equals(cell.getString("kind")) ? "logged" : cell.getInteger("value") + "%";

                assertEquals(labels.getString(i), actual, person.getString("id") + " " + cell.getString("date"));
            }
        }
    }

    @Test
    void closedPeriodsMatchPTeamPer() throws Exception
    {
        for (String key : List.of("s:S42", "r:8.3", "r:8.2", "m:2026-08", "m:2026-09"))
        {
            JsonObject result = get(TeamFormulas.TEAM_PERIOD, new JsonObject().put("people", team).put("period", key));

            String page = (String) oracle.eval("pTeamPer(perOf('" + key + "'))");

            List<String> stats = stats(page);

            JsonObject kpis = result.getJsonObject("kpis");

            assertEquals(stats.get(0), kpis.getInteger("utilization") + "%", key);
            assertEquals(stats.get(1), Math.round(kpis.getDouble("storyPointsDone")) + "/ " + Math.round(kpis.getDouble("committed")), key);
            assertEquals(stats.get(2), String.valueOf(kpis.getInteger("carriedOver")), key);
            assertEquals(stats.get(3), kpis.getJsonArray("over").size() + "/ " + kpis.getInteger("people"), key);
            assertEquals(stats.get(4), String.valueOf(kpis.getJsonArray("under").size()), key);

            Map<String, String> chart = new HashMap<>();

            Matcher m = Pattern.compile("data-act=\"perpick\" data-k=\"([^\"]+)\"[\\s\\S]*?(\\d+)%</span></span></div>").matcher(page);

            while (m.find())
            {
                chart.put(m.group(1), m.group(2));
            }

            JsonArray sprints = result.getJsonArray("sprints");

            assertEquals(chart.size(), sprints.size(), key + " " + sprints.encode());

            for (Object item : sprints)
            {
                JsonObject sprint = (JsonObject) item;

                assertEquals(chart.get(sprint.getString("period")), String.valueOf(sprint.getInteger("utilization")),
                        key + " " + sprint.getString("period"));
            }

            if (key.startsWith("r:"))
            {
                assertEquals(kpis.getDouble("storyPointsDone"), result.getJsonObject("release").getDouble("teamDone"), 1e-9, key);
            }
        }
    }

    @Test
    void anEmptyTeamHasNoNumbers() throws Exception
    {
        JsonObject live = get(TeamFormulas.TEAM_LIVE, new JsonObject().put("people", ""));

        assertTrue(live.getJsonArray("people").isEmpty());
        assertEquals(0, live.getJsonObject("kpis").getInteger("people"));
        assertTrue(live.getJsonObject("kpis").getValue("utilization") instanceof JsonObject na && na.getBoolean("notApplicable"));
    }

    private static JsonObject get(String formula, JsonObject params) throws Exception
    {
        return (JsonObject) await(calc.get(formula, params));
    }

    private static List<String> stats(String html)
    {
        Matcher m = Pattern.compile("pl-stat-value\">([^<]*)(?:<small>([^<]*))?").matcher(html);

        List<String> out = new ArrayList<>();

        while (m.find())
        {
            out.add(m.group(1) + (m.group(2) == null ? "" : m.group(2)));
        }

        return out;
    }
}
