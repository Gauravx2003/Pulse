package com.pulse.modules.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.platform.module.ModuleRegistry;
import com.pulse.platform.module.PulseModule;
import com.pulse.testsupport.Oracle;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 25.3 (change pulse-v2-live-connectors): a live Jira issue counts for a person only once its Jira
 * account is linked, and the team keeps the issues that count for no one.
 *
 * <p>The live sprint holds three live issues as the Jira connector writes them: JIRA-701, assigned to
 * Priya's Jira account {@code priya.live} (unmatched at first) with an update by that account on Friday;
 * JIRA-702, assigned to an account nobody links; JIRA-703, assigned to an account marked as a bot. Linking
 * {@code priya.live} moves JIRA-701 into Priya's Work items, Overview and Team utilization row from the next
 * read, with no re-sync, and her Friday update counts as activity, so it is not stale. Unlinking moves it
 * back. The team's open issues include the issues of no person; a bot's issue counts for nobody.
 *
 * <p>With these Jira rows present, the baseline 23.2 cross-screen check still holds: utilization, the stale
 * count and Discipline read the same on Overview, Work items, Team utilization and Culture values as
 * {@link CalcService} gives.
 */
class LinkThenReadTest
{

    private static final int PORT = 18_295;

    private static final String TODAY = "2026-09-28";

    private static final String ACCOUNTS = "/api/v1/integrations/accounts/";

    private static TestApi api;

    private static TestApi.Session admin;

    private static TestApi.Session manager;

    private static String priya;

    private static String priyaAccount;

    private static String ghostAccount;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", TODAY).put("http.userPerMinute", 100_000)
                .put("calc.closePeriods", false), productModules());

        admin = RoleMatrix.session(api, RoleMatrix.ADMIN);

        manager = RoleMatrix.session(api, RoleMatrix.MANAGER);

        priya = api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");

        String sprint43 = api.queryOne("SELECT id::text AS id FROM sprints WHERE number = 43").getString("id");

        priyaAccount = account("priya.live", false);

        ghostAccount = account("ghost.user", false);

        account("jira-bot", true);

        live("JIRA-701", "Live alarm router", "In Progress", sprint43, "priya.live", 3, 10, 8);
        live("JIRA-702", "Live topology import", "To Do", sprint43, "ghost.user", 2, 5, 5);
        live("JIRA-703", "Nightly re-index", "In Progress", sprint43, "jira-bot", 1, 4, 4);

        // Priya's own update on Friday 25 Sep, as the connector writes it: her Jira username, no person yet
        api.exec("INSERT INTO issue_events (issue_id, occurred_at, kind, field, from_value, to_value, actor_name, summary,"
                + " source, external_id, synced_at, actor_account_id) SELECT id, '2026-09-25 10:00+05:30', 'transition',"
                + " 'status', 'To Do', 'In Progress', 'Priya Shah', 'Status → In Progress', 'jira', 'JIRA-701:e1', now(),"
                + " 'priya.live' FROM issues WHERE source = 'jira' AND external_id = 'JIRA-701'");
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    @Test
    void linkingTheJiraAccountMovesTheIssueToThePersonFromTheNextRead() throws Exception
    {
        // unmatched: the team has it, Priya does not
        assertFalse(workItemKeys().contains("JIRA-701"));

        JsonObject team = team(manager);

        JsonObject before = row(team, priya);

        int teamOpen = team.getJsonObject("kpis").getInteger("open");

        assertEquals(2, team.getJsonObject("unassigned").getInteger("open"), "JIRA-701 and JIRA-702; the bot's issue counts for nobody");
        assertEquals(sumOpen(team) + 2, teamOpen, "the team's open issues include the unassigned ones");
        assertTrue(team.getJsonObject("unassigned").getJsonArray("items").isEmpty(), "a Manager sees only the totals");
        assertEquals(List.of("JIRA-701", "JIRA-702"), keys(team(admin).getJsonObject("unassigned").getJsonArray("items")));

        int tasks = overview().getInteger("tasks");

        assertFalse(counted(drawer("JIRA-701")), "no person yet, so the update is nobody's activity");

        // link: no sync, no cache call, the next read counts it for Priya
        ok(api.put(ACCOUNTS + priyaAccount + "/link", admin, new JsonObject().put("personId", priya)));

        JsonObject issue = workItem("JIRA-701");

        assertFalse(flagged(issue, "Stale") || flagged(issue, "Not started"), "her Friday update counts: " + issue.encode());
        assertEquals(tasks + 1, overview().getInteger("tasks"));

        JsonObject linked = team(manager);

        assertEquals(before.getInteger("open") + 1, row(linked, priya).getInteger("open"));
        assertEquals(1, linked.getJsonObject("unassigned").getInteger("open"));
        assertEquals(teamOpen, linked.getJsonObject("kpis").getInteger("open"), "the team total is unchanged");
        assertTrue(counted(drawer("JIRA-701")));

        // unlink: back to the team only
        ok(api.delete(ACCOUNTS + priyaAccount + "/link", admin));

        assertFalse(workItemKeys().contains("JIRA-701"));
        assertEquals(before.getInteger("open"), row(team(manager), priya).getInteger("open"));
        assertEquals(2, team(manager).getJsonObject("unassigned").getInteger("open"));

        // a bot's issues count for nobody, the team included
        ok(api.put(ACCOUNTS + ghostAccount + "/bot", admin, new JsonObject().put("isBot", true)));

        assertEquals(1, team(manager).getJsonObject("unassigned").getInteger("open"));

        ok(api.put(ACCOUNTS + ghostAccount + "/bot", admin, new JsonObject().put("isBot", false)));

        assertEquals(2, team(manager).getJsonObject("unassigned").getInteger("open"));
    }

    @Test
    void withJiraRowsPresentEveryScreenReadsOneNumber() throws Exception
    {
        ok(api.put(ACCOUNTS + priyaAccount + "/link", admin, new JsonObject().put("personId", priya)));

        try
        {
            CalcService calc = new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), TODAY, false), FormulaRegistry.discover());

            JsonObject team = team(manager);

            assertTrue(team.getBoolean("live"));

            JsonArray rows = team.getJsonArray("people");

            assertTrue(rows.size() >= 4, "team rows " + rows.size());

            for (Object o : rows)
            {
                JsonObject row = (JsonObject) o;

                String id = row.getString("id");

                String who = row.getString("name");

                JsonObject live = (JsonObject) get(calc, WorkloadFormulas.LIVE_SPRINT, new JsonObject().put("person", id));

                JsonObject discipline = (JsonObject) get(calc, "core.discipline", new JsonObject().put("person", id));

                JsonObject overview = ok(api.get("/api/v1/work/people/" + id + "/overview", manager)).getJsonObject("kpis");

                JsonObject items = ok(api.get("/api/v1/work/people/" + id + "/work-items", manager));

                JsonObject culture = ok(api.get("/api/v1/culture/people/" + id, manager)).getJsonObject("discipline");

                int stale = live.getJsonArray("stale").size();

                assertShown(live.getValue("util"), overview.getValue("utilization"), who + " utilization on Overview");
                assertShown(live.getValue("util"), row.getValue("utilization"), who + " utilization on Team utilization");
                assertEquals(stale, overview.getInteger("stale"), who + " stale on Overview");
                assertEquals(stale, row.getInteger("stale"), who + " stale on Team utilization");
                assertEquals(new TreeSet<>(live.getJsonArray("stale").getList()), staleKeys(items.getJsonArray("items")),
                        who + " stale issues on Work items");
                assertEquals(Oracle.display(discipline.getDouble("score"), 1), Oracle.display((Number) row.getValue("discipline"), 1),
                        who + " Discipline on Team utilization");
                assertEquals(Oracle.display(discipline.getDouble("score"), 1), Oracle.display((Number) culture.getValue("score"), 1),
                        who + " Discipline on Culture values");

                if (id.equals(priya))
                {
                    assertTrue(keys(items.getJsonArray("items")).contains("JIRA-701"), "the linked live issue is Priya's");
                }
            }
        }
        finally
        {
            ok(api.delete(ACCOUNTS + priyaAccount + "/link", admin));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String account(String login, boolean bot) throws Exception
    {
        return api.queryOne("INSERT INTO external_accounts (tool, tool_account_id, login, is_bot) VALUES ('jira', '" + login
                + "', '" + login + "', " + bot + ") RETURNING id::text AS id").getString("id");
    }

    private static void live(String key, String summary, String status, String sprint, String account, int points,
                             int estimate, int remaining) throws Exception
    {
        api.exec("INSERT INTO issues (key, summary, status, sprint_id, story_points, estimate_hours, remaining_hours,"
                + " started_on, source, external_id, synced_at, html_url, assignee_account_id) VALUES ('" + key + "', '"
                + summary + "', '" + status + "', '" + sprint + "', " + points + ", " + estimate + ", " + remaining + ", "
                + ("To Do".equals(status) ? "NULL" : "'2026-09-22'") + ", 'jira', '" + key + "', now(),"
                + " 'http://jira.test/browse/" + key + "', '" + account + "')");
    }

    private static JsonObject team(TestApi.Session who) throws Exception
    {
        return ok(api.get("/api/v1/utilization/team", who));
    }

    private static JsonObject row(JsonObject team, String id)
    {
        return team.getJsonArray("people").stream().map(JsonObject.class::cast).filter(r -> id.equals(r.getString("id")))
                .findFirst().orElseThrow();
    }

    private static int sumOpen(JsonObject team)
    {
        return team.getJsonArray("people").stream().mapToInt(r -> ((JsonObject) r).getInteger("open", 0)).sum();
    }

    private static JsonObject overview() throws Exception
    {
        return ok(api.get("/api/v1/work/people/" + priya + "/overview", manager)).getJsonObject("kpis");
    }

    private static List<String> workItemKeys() throws Exception
    {
        return keys(ok(api.get("/api/v1/work/people/" + priya + "/work-items", manager)).getJsonArray("items"));
    }

    private static JsonObject workItem(String key) throws Exception
    {
        return ok(api.get("/api/v1/work/people/" + priya + "/work-items", manager)).getJsonArray("items").stream()
                .map(JsonObject.class::cast).filter(i -> key.equals(i.getString("key"))).findFirst()
                .orElseThrow(() -> new AssertionError(key + " not in Priya's work items"));
    }

    private static JsonObject drawer(String key) throws Exception
    {
        return ok(api.get("/api/v1/work/issues/" + key, admin));
    }

    /** Whether the issue's Jira update is counted as the assignee's activity on its timeline. */
    private static boolean counted(JsonObject drawer)
    {
        return drawer.getJsonArray("timeline").stream().map(JsonObject.class::cast)
                .filter(e -> "Status → In Progress".equals(e.getString("summary"))).findFirst().orElseThrow()
                .getBoolean("counted");
    }

    private static boolean flagged(JsonObject issue, String label)
    {
        return issue.getJsonArray("flags", new JsonArray()).stream().anyMatch(f -> label.equals(((JsonArray) f).getString(1)));
    }

    private static List<String> keys(JsonArray items)
    {
        return items.stream().map(i -> ((JsonObject) i).getString("key")).toList();
    }

    /** The keys of the listed issues flagged Stale, Overdue or Not started (as FullOracleTest). */
    private static Set<Object> staleKeys(JsonArray items)
    {
        Set<Object> out = new TreeSet<>();

        for (Object o : items)
        {
            JsonObject issue = (JsonObject) o;

            if (flagged(issue, "Stale") || flagged(issue, "Overdue") || flagged(issue, "Not started"))
            {
                out.add(issue.getString("key"));
            }
        }

        return out;
    }

    private static Object get(CalcService calc, String formula, JsonObject params) throws Exception
    {
        return ResultGuard.unguard(TestApi.await(calc.get(formula, params)));
    }

    private static void assertShown(Object expected, Object actual, String what)
    {
        assertTrue(expected instanceof Number && actual instanceof Number, what + ": " + expected + " / " + actual);

        assertEquals(Oracle.display((Number) expected, 0), Oracle.display((Number) actual, 0), what);
    }

    private static JsonObject ok(HttpResponse<Buffer> res)
    {
        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static String[] productModules()
    {
        return ModuleRegistry.discover().modules().stream()
                .filter(m -> Files.isDirectory(Path.of("modules", m.id())))
                .map(PulseModule::id)
                .toArray(String[]::new);
    }
}
