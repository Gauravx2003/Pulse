package com.pulse.modules.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.work.Effort;
import com.pulse.calc.work.EffortFormulas;
import com.pulse.calc.work.WorkViewFormulas;
import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.platform.live.LoopbackFlag;
import com.pulse.platform.live.testkit.LiveMockServer;
import com.pulse.platform.secrets.SecretBox;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 25.4 (change pulse-v2-live-connectors): Track 3's Jira fixtures ({@code live-fixtures/jira/}),
 * ingested through the real Test connection, Save and Sync now routes of the Jira connector, give the same
 * {@link CalcService} input shape as the seed. A live issue and a seed issue read as the same keys of the
 * same types through {@code work.issue}, {@code work.periodIssues} and {@code work.sprintMetrics}, and a
 * live sprint reads like a seed sprint in the period calendar. Values may differ, and a missing value
 * (null or not applicable) matches any type; a key on one side only, or a number on one side and text on
 * the other, fails.
 *
 * <p>It also pins the Phase 1 semantics the review (docs/live-connectors.md section 5) checks: seed
 * statuses only, hours as numbers, the sprint's {@code ends_on} on a Sunday, the person resolved at read
 * time through the linked account.
 */
class JiraShapeTest
{

    private static final int PORT = 18_296;

    /** Inside Jira sprint 147 of the fixtures (23 Nov – 4 Dec 2026). */
    private static final String TODAY = "2026-11-26";

    private static final String SECRET = "jira-shape-pass-31c7";

    private static final Set<String> SEED_STATUSES = Set.of("To Do", "In Progress", "In Review", "Done");

    private static TestApi api;

    private static TestApi.Session admin;

    private static LiveMockServer mock;

    private static CalcService calc;

    private static String priya;

    /** A live issue of Jira sprint 147 assigned to dev.user1, whom the test links to Priya. */
    private static JsonObject live;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject()
                .put("today", TODAY)
                .put("calc.closePeriods", false)
                .put(SecretBox.CONFIG_KEY, SecretBox.generateKey())
                .put(LoopbackFlag.CONFIG_KEY, true), "work", "pull_requests", "bugs", "effort", "progress", "integrations",
                "connectors");

        admin = RoleMatrix.session(api, RoleMatrix.ADMIN);

        mock = TestApi.await(LiveMockServer.start(api.vertx(), "jira"));

        priya = api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");

        JsonObject values = new JsonObject()
                .put("config", new JsonObject()
                        .put("baseUrl", mock.baseUrl())
                        .put("authMode", "basic")
                        .put("username", "pulse.readonly")
                        .put("projectKeys", new JsonArray().add("PROJ"))
                        .put("storyPointsField", "customfield_10111")
                        .put("sprintField", "customfield_10108")
                        .put("ignoreBotEdits", true))
                .put("secret", SECRET);

        JsonObject tested = ok(api.post("/api/v1/integrations/jira/test", admin, values));

        ok(api.put("/api/v1/integrations/jira/connection", admin, values.copy().put("proof", tested.getString("proof"))));

        ok(api.post("/api/v1/integrations/jira/sync", admin, new JsonObject()));

        String account = api.queryOne("SELECT id::text AS id FROM external_accounts WHERE tool = 'jira' AND tool_account_id = 'dev.user1'")
                .getString("id");

        ok(api.put("/api/v1/integrations/accounts/" + account + "/link", admin, new JsonObject().put("personId", priya)));

        live = api.queryOne("""
                SELECT i.id::text AS id, i.key, s.key AS sprint FROM issues i JOIN sprints s ON s.id = i.sprint_id
                WHERE i.source = 'jira' AND i.assignee_account_id = 'dev.user1' AND s.source = 'jira'
                ORDER BY i.key LIMIT 1""");

        assertNotNull(live, "a live issue of dev.user1 in a Jira sprint");

        // built after the sync and the link, so nothing it caches predates them
        calc = new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), TODAY, false), FormulaRegistry.discover());
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (mock != null)
        {
            mock.close();
        }

        if (api != null)
        {
            api.close();
        }
    }

    @Test
    void aLiveIssueReadsLikeASeedIssue() throws Exception
    {
        String seedId = api.queryOne("SELECT id::text AS id FROM issues WHERE source = 'seed' AND key = 'PLAT-2217'").getString("id");

        Object liveIssue = get(WorkloadFormulas.ISSUE, new JsonObject().put("issue", live.getString("id")));

        Object seedIssue = get(WorkloadFormulas.ISSUE, new JsonObject().put("issue", seedId));

        assertSameShape(seedIssue, liveIssue, "work.issue");

        // the raw row as the live sprint's list reads it
        JsonObject row = item((JsonObject) get(WorkViewFormulas.LIVE_ISSUES, new JsonObject().put("person", priya)),
                live.getString("key"));

        assertEquals("jira", row.getString("source"));
        assertEquals(priya, row.getString("assigneeId"), "the linked account's person, resolved at read time");
        assertTrue(SEED_STATUSES.contains(row.getString("status")), row.getString("status"));
        assertTrue(row.getString("htmlUrl").endsWith("/browse/" + live.getString("key")), row.getString("htmlUrl"));
    }

    @Test
    void aLiveSprintReadsLikeASeedSprint() throws Exception
    {
        String key = "s:" + live.getString("sprint");

        JsonObject periods = (JsonObject) get("core.periods", new JsonObject());

        assertEquals(key, periods.getString("activeSprint"), "today is inside the Jira sprint");

        JsonObject liveSprint = sprint(periods, key);

        JsonObject seedSprint = sprint(periods, "s:S43");

        assertSameShape(seedSprint, liveSprint, "core.periods sprint");

        assertSameShape(get("core.period", new JsonObject().put("period", "s:S43")), get("core.period", new JsonObject().put("period", key)),
                "core.period");

        JsonObject row = api.queryOne("SELECT starts_on::text AS starts, work_ends_on::text AS ends, ends_on::text AS sunday"
                + " FROM sprints WHERE source = 'jira' AND key = '" + live.getString("sprint") + "'");

        assertEquals(DayOfWeek.SUNDAY, LocalDate.parse(row.getString("sunday")).getDayOfWeek(), "ends_on is a Sunday, as seeded");
        assertTrue(row.getString("ends").compareTo(row.getString("starts")) >= 0);
    }

    @Test
    void theSprintListsAndMetricsReadAlike() throws Exception
    {
        JsonObject liveParams = new JsonObject().put("person", priya).put("period", "s:" + live.getString("sprint"));

        JsonObject seedParams = new JsonObject().put("person", priya).put("period", "s:S42");

        JsonObject liveList = (JsonObject) get(WorkViewFormulas.PERIOD_ISSUES, liveParams);

        JsonObject seedList = (JsonObject) get(WorkViewFormulas.PERIOD_ISSUES, seedParams);

        assertTrue(keys(liveList).contains(live.getString("key")), "the linked live issue is Priya's: " + keys(liveList));
        assertTrue(!seedList.getJsonArray("items").isEmpty(), "Priya has seed issues in Sprint 42");

        assertSameShape(seedList.getJsonArray("items").getValue(0), item(liveList, live.getString("key")), "work.periodIssues item");

        assertSameShape(get(WorkloadFormulas.SPRINT_METRICS, seedParams), get(WorkloadFormulas.SPRINT_METRICS, liveParams),
                "work.sprintMetrics");

        for (Object o : liveList.getJsonArray("items"))
        {
            JsonObject issue = (JsonObject) o;

            if ("jira".equals(issue.getString("source")))
            {
                assertTrue(SEED_STATUSES.contains(issue.getString("status")), issue.encode());
                assertTrue(issue.getValue("spentHours") instanceof Number, "spent hours default to 0: " + issue.encode());
            }
        }
    }

    /**
     * The 25.4 review fixes: a live sprint change holds the Jira sprint key and a live assignee change the
     * Jira usernames (as JiraMapper writes them), so the Effort detection finds a mid-sprint addition and a
     * take-over for the person the account is linked to.
     */
    @Test
    void liveSprintAndAssigneeChangesFeedTheEffortDetection() throws Exception
    {
        String sprint = live.getString("sprint");

        assertTrue(sprint.startsWith("J"), "Jira sprints are keyed apart from the seed: " + sprint);

        String insert = "INSERT INTO issue_events (issue_id, occurred_at, kind, field, from_value, to_value, actor_name, summary,"
                + " source, external_id, synced_at, actor_account_id) SELECT id, '%s', '%s', '%s', %s, '%s', 'Dev User 2', '%s',"
                + " 'jira', '%s', now(), 'dev.user2' FROM issues WHERE source = 'jira' AND external_id = '" + live.getString("key") + "'";

        api.exec(insert.formatted("2026-11-24 12:00+05:30", "sprint_change", "Sprint", "NULL", sprint, "Sprint → 147",
                live.getString("key") + "|review.s"));
        api.exec(insert.formatted("2026-11-25 11:00+05:30", "assignee_change", "assignee", "'dev.user2'", "dev.user1",
                "Assigned to Dev User 1", live.getString("key") + "|review.a"));

        // a fresh CalcService: the events were written after the shared one cached its reads
        CalcService fresh = new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), TODAY, false), FormulaRegistry.discover());

        JsonArray found = (JsonArray) TestApi.await(fresh.get(EffortFormulas.DETECTED,
                new JsonObject().put("person", priya).put("period", "s:" + sprint)));

        Set<String> kinds = new TreeSet<>();

        for (Object o : found)
        {
            JsonObject item = (JsonObject) o;

            if (live.getString("key").equals(item.getString("ref")))
            {
                kinds.add(item.getString("kind"));
            }
        }

        assertEquals(Set.of(Effort.MIDSPRINT, Effort.TAKEOVER), kinds, found.encode());
    }

    // ------------------------------------------------------------------ shape

    /**
     * Fails with every path where {@code actual} differs in shape from {@code expected}: a key missing on
     * either side, or two present values of different JSON types. Missing values match anything.
     */
    static void assertSameShape(Object expected, Object actual, String what)
    {
        List<String> differences = new ArrayList<>();

        compare("", expected, actual, differences);

        assertEquals(List.of(), differences, what + ": seed " + expected + "\nlive " + actual);
    }

    private static void compare(String path, Object expected, Object actual, List<String> out)
    {
        String a = type(expected);

        String b = type(actual);

        if ("missing".equals(a) || "missing".equals(b))
        {
            return;
        }

        if (!a.equals(b))
        {
            out.add(path + ": " + a + " vs " + b);

            return;
        }

        if (expected instanceof JsonObject e && actual instanceof JsonObject l)
        {
            Set<String> names = new TreeSet<>(e.fieldNames());

            names.addAll(l.fieldNames());

            for (String name : names)
            {
                if (!e.containsKey(name) || !l.containsKey(name))
                {
                    out.add(path + "." + name + ": only in " + (e.containsKey(name) ? "seed" : "live"));

                    continue;
                }

                compare(path + "." + name, e.getValue(name), l.getValue(name), out);
            }
        }
        else if (expected instanceof JsonArray e && actual instanceof JsonArray l && !e.isEmpty() && !l.isEmpty())
        {
            compare(path + "[0]", e.getValue(0), l.getValue(0), out);
        }
    }

    private static String type(Object value)
    {
        if (value == null || ResultGuard.isNotApplicable(value))
        {
            return "missing";
        }

        return switch (value)
        {
            case JsonObject o -> "object";
            case JsonArray a -> "array";
            case Map<?, ?> m -> "object";
            case List<?> l -> "array";
            case Number n -> "number";
            case Boolean b -> "boolean";
            default -> "string";
        };
    }

    // ------------------------------------------------------------------ helpers

    private static Object get(String formula, JsonObject params) throws Exception
    {
        return TestApi.await(calc.get(formula, params));
    }

    private static JsonObject sprint(JsonObject periods, String key)
    {
        Map<String, JsonObject> byKey = new TreeMap<>();

        periods.getJsonArray("sprints").forEach(s -> byKey.put(((JsonObject) s).getString("key"), (JsonObject) s));

        return assertNotNullAndGet(byKey.get(key), key + " in " + byKey.keySet());
    }

    private static JsonObject assertNotNullAndGet(JsonObject value, String what)
    {
        assertNotNull(value, what);

        return value;
    }

    private static List<String> keys(JsonObject list)
    {
        return list.getJsonArray("items").stream().map(i -> ((JsonObject) i).getString("key")).toList();
    }

    private static JsonObject item(JsonObject list, String key)
    {
        return list.getJsonArray("items").stream().map(JsonObject.class::cast).filter(i -> key.equals(i.getString("key")))
                .findFirst().orElseThrow();
    }

    private static JsonObject ok(HttpResponse<Buffer> res)
    {
        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }
}
