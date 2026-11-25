package com.pulse.calc.work;

import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 9.1: {@link TeamViews} against the prototype's Team utilization page ({@code pTeam} rendered as
 * {@code rahul@}), fed with the prototype's own per-person metrics, plus the rules the page does not
 * show directly: band edges, onboarding recipients, the skills match, heatmap cells and closed-period
 * KPIs.
 */
class TeamViewsTest
{

    private static Oracle oracle;

    private static JsonObject rules;

    private static List<JsonObject> rows;

    private static Map<String, List<JsonObject>> toDo;

    private static String page;

    @BeforeAll
    static void start() throws Exception
    {
        oracle = Oracle.start();

        rules = (JsonObject) oracle.eval("S.rules");

        JsonArray people = (JsonArray) oracle.eval("""
                PEOPLE.map(function(p){var m=metrics(p);return {id:p.id,name:p.name,level:p.level,util:m.util,work:m.work,
                cap:m.cap,open:m.open.length,stale:m.stale.length,onboarding:onboarding(p.id),
                toDo:p.tasks.filter(function(t){return t.status==='To Do'}).map(function(t){return {key:t.key,summary:t.summary,remainingHours:t.rem}})}})""");

        rows = new ArrayList<>();

        toDo = new HashMap<>();

        for (Object item : people)
        {
            JsonObject row = (JsonObject) item;

            List<JsonObject> waiting = new ArrayList<>();

            row.getJsonArray("toDo").forEach(t -> waiting.add((JsonObject) t));

            toDo.put(row.getString("id"), waiting);

            rows.add(row);
        }

        page = (String) oracle.eval("(function(){S.uid='u-rn';return pTeam()})()");
    }

    @AfterAll
    static void stop()
    {
        oracle.close();
    }

    // ------------------------------------------------------------------ against the prototype page

    @Test
    void liveKpisMatchThePrototypePage()
    {
        JsonObject kpis = TeamViews.liveKpis(rows, rules);

        List<String> stats = stats(page);

        assertEquals(stats.get(0), kpis.getInteger("utilization") + "%");
        assertEquals(stats.get(1), kpis.getJsonArray("overloaded").size() + "/ " + kpis.getInteger("people"));
        assertEquals(stats.get(2), kpis.getJsonArray("underUtilized").size() + "/ " + kpis.getInteger("countable"));
        assertEquals(stats.get(3), kpis.getInteger("stale") + "/ " + kpis.getInteger("open") + " open");

        assertEquals("Priya Shah", kpis.getJsonArray("overloaded").getJsonObject(0).getString("name"));
        assertEquals(2, kpis.getInteger("onboarding"));
    }

    @Test
    void thePlat2198SuggestionReadsAsInThePrototype()
    {
        JsonObject suggestion = TeamViews.rebalance(rows, toDo, TeamViews::skillsMatch, rules);

        String expected = "Move PLAT-2198 (24 h) from Priya Shah (171%) to Dev Patel (52%). New levels ≈ 104% and 96%.";

        assertEquals(expected, suggestion.getString("text"));
        assertEquals(expected, prototypeSuggestion(page));

        assertEquals("PLAT-2198", suggestion.getJsonObject("issue").getString("key"));
        assertEquals(104, suggestion.getJsonObject("from").getInteger("after"));
        assertEquals(96, suggestion.getJsonObject("to").getInteger("after"));
    }

    // ------------------------------------------------------------------ rules

    @Test
    void exactly100IsAtCapacityAnd100Point1IsOver()
    {
        assertEquals("At capacity", Workload.band(100, rules).getString(1));
        assertEquals("Over", Workload.band(100.1, rules).getString(1));

        List<JsonObject> team = List.of(row("a", "A", 100, 6, 6, false), row("b", "B", 100.1, 6.006, 6, false));

        JsonArray overloaded = TeamViews.liveKpis(team, rules).getJsonArray("overloaded");

        assertEquals(1, overloaded.size());
        assertEquals("B", overloaded.getJsonObject(0).getString("name"));
    }

    @Test
    void anOnboardingJoinerIsNeverUnderUsedNorARecipient()
    {
        List<JsonObject> team = List.of(
                row("a", "Asha", 150, 9, 6, false),
                row("b", "Bala", 10, 0.9, 9, true));

        Map<String, List<JsonObject>> work = Map.of("a", List.of(issue("X-1", 12)));

        assertTrue(TeamViews.liveKpis(team, rules).getJsonArray("underUtilized").isEmpty());
        assertNull(TeamViews.rebalance(team, work, TeamViews::skillsMatch, rules));
    }

    @Test
    void theSuggestionMovesTheBiggestToDoIssueToTheFirstMatchingPerson()
    {
        List<JsonObject> team = List.of(
                row("a", "Asha", 150, 9, 6, false).put("level", "L3"),
                row("b", "Bala", 50, 4.5, 9, false).put("level", "L2"),
                row("c", "Chen", 40, 3.6, 9, false).put("level", "L4"));

        Map<String, List<JsonObject>> work = Map.of("a", List.of(issue("X-1", 6), issue("X-2", 12.25)));

        JsonObject suggestion = TeamViews.rebalance(team, work, TeamViews::skillsMatch, rules);

        assertEquals("X-2", suggestion.getJsonObject("issue").getString("key"));
        assertEquals("Chen", suggestion.getJsonObject("to").getString("name"), "Bala is a level below Asha");
        assertTrue(suggestion.getString("text").startsWith("Move X-2 (12.3 h) from Asha (150%) to Chen (40%)."), suggestion.getString("text"));
        assertNull(suggestion.getJsonObject("issue").getValue("htmlUrl"), "a seed issue has no tool link");
    }

    @Test
    void aLiveIssueSuggestionKeepsItsJiraLink()
    {
        List<JsonObject> team = List.of(
                row("a", "Asha", 150, 9, 6, false).put("level", "L3"),
                row("c", "Chen", 40, 3.6, 9, false).put("level", "L4"));

        Map<String, List<JsonObject>> work = Map.of("a",
                List.of(issue("JIRA-7", 12).put("htmlUrl", "http://jira.test/browse/JIRA-7"), issue("X-1", 6)));

        JsonObject suggestion = TeamViews.rebalance(team, work, TeamViews::skillsMatch, rules);

        assertEquals("JIRA-7", suggestion.getJsonObject("issue").getString("key"));
        assertEquals("http://jira.test/browse/JIRA-7", suggestion.getJsonObject("issue").getString("htmlUrl"));
    }

    @Test
    void ratedSkillsMustShareOneHeldAtLevel3()
    {
        JsonObject from = new JsonObject().put("level", "L3")
                .put("skills", new JsonArray().add(skill("Go / backend", 4)).add(skill("Observability", 4)));

        JsonObject weak = new JsonObject().put("level", "L3").put("skills", new JsonArray().add(skill("go / Backend", 2)));

        JsonObject strong = new JsonObject().put("level", "L3").put("skills", new JsonArray().add(skill("Observability", 3)));

        JsonObject unrated = new JsonObject().put("level", "L3");

        assertFalse(TeamViews.skillsMatch(from, weak));
        assertTrue(TeamViews.skillsMatch(from, strong));
        assertTrue(TeamViews.skillsMatch(from, unrated));
        assertFalse(TeamViews.skillsMatch(from, new JsonObject().put("level", "L2")));
    }

    @Test
    void heatmapCellsLabelPreJoiningLeaveVisitAndTraining()
    {
        JsonArray load = new JsonArray()
                .add(day("2026-09-21", "logged", 6.0, null))
                .add(day("2026-09-22", "logged", null, "Leave"))
                .add(day("2026-09-23", "logged", null, "Customer visit"))
                .add(day("2026-09-28", "planned", 4.0, null));

        JsonArray cells = TeamViews.heatmapRow(load, "2026-09-22", false, 130, rules);

        assertEquals("prejoin", cells.getJsonObject(0).getString("kind"));
        assertEquals("Leave", cells.getJsonObject(1).getString("label"));
        assertNull(cells.getJsonObject(1).getValue("value"), "a leave cell has no utilization value");
        assertEquals("Visit", cells.getJsonObject(2).getString("label"));
        assertEquals(130, cells.getJsonObject(3).getInteger("value"), "from today the live utilization");
        assertEquals("Critical", cells.getJsonObject(3).getJsonArray("band").getString(1));

        JsonArray logged = TeamViews.heatmapRow(load, null, false, 130, rules);

        assertEquals(100, logged.getJsonObject(0).getInteger("value"), "6 h logged of 6 focus hours");

        JsonArray training = TeamViews.heatmapRow(load, null, true, 0, rules);

        assertEquals("Training", training.getJsonObject(3).getString("label"));
        assertEquals("Leave", training.getJsonObject(1).getString("label"), "leave wins over training");
    }

    @Test
    void closedKpisUseLoggedHoursOverWorkingDaysAndSkipNewJoiners()
    {
        List<JsonObject> team = List.of(
                new JsonObject().put("id", "a").put("name", "Asha").put("logged", 80.0).put("work", 10.0)
                        .put("com", 20.0).put("dsp", 15.0).put("carry", 2).put("util", 100.0),
                new JsonObject().put("id", "b").put("name", "Bala").put("logged", 16.0).put("work", 10.0)
                        .put("com", 10.0).put("dsp", 10.0).put("carry", 0).put("util", 20.0).put("newJoiner", true),
                new JsonObject().put("id", "c").put("name", "Chen").put("logged", 96.0).put("work", 10.0)
                        .put("com", 0.0).put("dsp", 0.0).put("carry", 1).put("util", 120.0));

        JsonObject kpis = TeamViews.closedKpis(team, rules);

        assertEquals(80, kpis.getInteger("utilization"), "192 h over 30 days × 8 h");
        assertEquals(25.0, kpis.getDouble("storyPointsDone"), 1e-9);
        assertEquals(83, kpis.getInteger("commitment"));
        assertEquals(3, kpis.getInteger("carriedOver"));
        assertEquals("Chen", kpis.getJsonArray("over").getJsonObject(0).getString("name"));
        assertTrue(kpis.getJsonArray("under").isEmpty(), "Bala is a new joiner");
    }

    @Test
    void sprintUtilizationIsAForecastForTheLiveSprint()
    {
        assertEquals(70, TeamViews.sprintUtilization(true, List.of(100.0, 40.0), 0, 0, rules));
        assertEquals(75, TeamViews.sprintUtilization(false, List.of(), 60, 10, rules));
        assertEquals(0, TeamViews.sprintUtilization(false, List.of(), 60, 0, rules));
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> stats(String html)
    {
        Matcher m = Pattern.compile("pl-stat-value\">([^<]*)<small>([^<]*)").matcher(html);

        List<String> out = new ArrayList<>();

        while (m.find())
        {
            out.add(m.group(1) + m.group(2));
        }

        return out;
    }

    private static String prototypeSuggestion(String html)
    {
        Matcher m = Pattern.compile("(Move <b>.*?)</span>").matcher(html);

        assertTrue(m.find(), "the prototype page shows a suggestion");

        return m.group(1).replaceAll("<[^>]+>", "");
    }

    private static JsonObject row(String id, String name, double util, double work, double cap, boolean onboarding)
    {
        return new JsonObject().put("id", id).put("name", name).put("util", util).put("work", work).put("cap", cap)
                .put("open", 0).put("stale", 0).put("onboarding", onboarding);
    }

    private static JsonObject issue(String key, double hours)
    {
        return new JsonObject().put("key", key).put("summary", key).put("remainingHours", hours);
    }

    private static JsonObject skill(String name, int current)
    {
        return new JsonObject().put("skill", name).put("current", current);
    }

    private static JsonObject day(String date, String kind, Double hours, String note)
    {
        return new JsonObject().put("date", date).put("kind", kind).put("hours", hours).put("note", note).put("today", false);
    }
}
