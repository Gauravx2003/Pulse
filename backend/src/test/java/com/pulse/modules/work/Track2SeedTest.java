package com.pulse.modules.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.work.WorkloadFormulas;
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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 7.2: the Track 2 demo seed, loaded at start-up by the {@code SeedConnector} through
 * {@code IngestService} (ingest tables) and by the S230 and S240 SQL seeds (Pulse-owned tables). Row
 * counts equal the prototype datasets, Priya's live-sprint issues equal the prototype's, and Sync now
 * re-ingests without duplicates.
 */
class Track2SeedTest
{

    private static final int PORT = 18_220;

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null, "work", "pull_requests", "bugs", "effort", "progress", "integrations");
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    @Test
    void rowCountsEqualThePrototypeDatasets() throws Exception
    {
        assertEquals(counts(), actualCounts());
    }

    @Test
    void ingestTablesAreSeededOnlyThroughTheConnector() throws Exception
    {
        for (String table : List.of("issues", "issue_events", "worklogs", "commit_days", "pull_requests", "pr_reviews",
                "pr_comments", "bugs", "support_tickets"))
        {
            assertEquals(0L, api.count("SELECT count(*) FROM " + table + " WHERE source <> 'seed' OR synced_at IS NULL"), table);
        }
    }

    @Test
    void priyasLiveSprintIssuesEqualThePrototypes() throws Exception
    {
        List<String> expected = new ArrayList<>();

        for (Object item : json("issues"))
        {
            JsonObject t = (JsonObject) item;

            if ("ps".equals(t.getString("person")))
            {
                expected.add(String.join("|", t.getString("key"), t.getString("summary"), t.getString("status"), t.getString("type"),
                        num(t.getValue("sp")), num(t.getValue("orig")), num(t.getValue("spent")), num(t.getValue("rem")),
                        String.valueOf(t.getString("due")), String.valueOf(t.getString("started"))));
            }
        }

        List<String> actual = new ArrayList<>();

        for (Object item : await(api.sql().queryList("""
                SELECT i.key, i.summary, i.status, i.issue_type, i.story_points, i.estimate_hours, i.spent_hours,
                       i.remaining_hours, i.due_on, i.started_on
                FROM issues i JOIN sprints s ON s.id = i.sprint_id JOIN people p ON p.id = i.assignee_id
                WHERE s.key = 'S43' AND p.external_id = 'ps' ORDER BY i.key""")))
        {
            JsonObject r = (JsonObject) item;

            actual.add(String.join("|", r.getString("key"), r.getString("summary"), r.getString("status"), r.getString("issue_type"),
                    num(r.getValue("story_points")), num(r.getValue("estimate_hours")), num(r.getValue("spent_hours")),
                    num(r.getValue("remaining_hours")), String.valueOf(r.getString("due_on")), String.valueOf(r.getString("started_on"))));
        }

        expected.sort(null);

        assertEquals(8, expected.size());

        assertEquals(expected, actual);
    }

    @Test
    void priyasLiveSprintMatchesTheOracleOnTheSeededData() throws Exception
    {
        CalcService calc = new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), "2026-09-28", false), FormulaRegistry.discover());

        String priya = api.queryOne("SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");

        try (Oracle oracle = Oracle.start())
        {
            for (Object item : json("issues"))
            {
                JsonObject t = (JsonObject) item;

                if (!"ps".equals(t.getString("person")))
                {
                    continue;
                }

                String id = api.queryOne("SELECT id FROM issues WHERE key = '" + t.getString("key") + "'").getString("id");

                JsonObject issue = (JsonObject) await(calc.get(WorkloadFormulas.ISSUE, new JsonObject().put("issue", id)));

                assertEquals(t.getString("last"), issue.getString("lastActivity"), t.getString("key") + " last activity");

                int index = ((Number) oracle.eval("person('ps').tasks.map(function(x){return x.key}).indexOf('" + t.getString("key") + "')")).intValue();

                assertEquals(oracle.eval("flags(person('ps').tasks[" + index + "],person('ps'))"), issue.getJsonArray("flags"), t.getString("key"));
            }

            JsonObject live = (JsonObject) await(calc.get(WorkloadFormulas.LIVE_SPRINT, new JsonObject().put("person", priya)));

            JsonObject prototype = (JsonObject) oracle.eval("(function(m){var k=function(t){return t.key};"
                    + "return {open:m.open.map(k).sort(),stale:m.stale.map(k).sort(),done:m.done.map(k).sort(),rem:m.rem,sp:m.sp,"
                    + "oldest:m.oldest}})(metrics(person('ps')))");

            assertEquals(prototype.getJsonArray("open"), sorted(live.getJsonArray("open")));
            assertEquals(prototype.getJsonArray("stale"), sorted(live.getJsonArray("stale")));
            assertEquals(prototype.getJsonArray("done"), sorted(live.getJsonArray("done")));
            assertEquals(prototype.getDouble("rem"), live.getDouble("rem"), 1e-9);
            assertEquals(prototype.getDouble("sp"), live.getDouble("sp"), 1e-9);
            assertEquals(prototype.getInteger("oldest"), live.getInteger("oldest"));
        }
    }

    @Test
    void syncNowReingestsWithoutDuplicates() throws Exception
    {
        Map<String, Long> before = actualCounts();

        TestApi.Session admin = RoleMatrix.session(api, RoleMatrix.ADMIN);

        for (String tool : List.of("jira", "gh", "tfs"))
        {
            HttpResponse<Buffer> res = api.post("/api/v1/integrations/tools/" + tool + "/sync", admin, new JsonObject());

            assertEquals(200, res.statusCode(), tool + ": " + res.bodyAsString());
        }

        assertEquals(before, actualCounts());

        assertTrue(api.count("SELECT count(*) FROM issues WHERE synced_at > created_at") > 0, "synced_at moved");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Expected rows per table, from the extracted prototype datasets.
     */
    private static Map<String, Long> counts() throws Exception
    {
        JsonArray issues = json("issues");

        long started = issues.stream().map(o -> (JsonObject) o)
                .filter(t -> t.getString("started") != null && t.getString("started").compareTo(t.getString("last").substring(0, 10)) < 0)
                .count();

        long reviews = 0;

        long comments = 0;

        for (Object item : json("pull_requests"))
        {
            for (Object r : ((JsonObject) item).getJsonArray("reviewers"))
            {
                reviews++;

                comments += ((JsonObject) r).getJsonArray("comments").size();
            }
        }

        Map<String, Long> out = new TreeMap<>();

        out.put("issues", (long) issues.size() + json("issue_history").size());
        out.put("issue_events", 3L * issues.size() + started);
        out.put("worklogs", (long) json("worklogs").size());
        out.put("commit_days", (long) json("commit_days").size());
        out.put("pull_requests", (long) json("pull_requests").size());
        out.put("pr_reviews", reviews);
        out.put("pr_comments", comments);
        out.put("bugs", (long) json("bugs").size());
        out.put("support_tickets", (long) json("support_tickets").size());
        out.put("weekly_pulse", (long) json("weekly_pulse").size());
        out.put("extra_items", (long) json("extra_mile").size());

        return out;
    }

    private static Map<String, Long> actualCounts() throws Exception
    {
        Map<String, Long> out = new TreeMap<>();

        for (String table : counts().keySet())
        {
            out.put(table, api.count("SELECT count(*) FROM " + table));
        }

        return out;
    }

    private static JsonArray json(String name) throws Exception
    {
        return new JsonArray(Files.readString(Path.of("..", "seed", "extracted", name + ".json")));
    }

    private static JsonArray sorted(JsonArray values)
    {
        List<String> out = new ArrayList<>();

        values.forEach(v -> out.add((String) v));

        out.sort(null);

        return new JsonArray(new ArrayList<Object>(out));
    }

    private static String num(Object value)
    {
        return value == null ? "null" : new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    }
}
