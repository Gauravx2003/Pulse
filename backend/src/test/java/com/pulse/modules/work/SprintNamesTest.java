package com.pulse.modules.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.work.EffortFormulas;
import com.pulse.platform.module.ModuleRegistry;
import com.pulse.platform.module.PulseModule;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 1.3 (change jira-sprint-names, design.md risk "Labels inside frozen snapshots"): a closed sprint keeps the
 * label text of its freeze in its snapshot's {@code inputs.period}, but every screen takes the period's label from
 * the calendar at read time ({@code core.period}, never frozen). So a sprint renamed after its period was frozen
 * shows the new name in the period list and on the closed period's screens, with no re-freeze and the same key.
 *
 * <p>Task 2.1: every other place that names a sprint takes the calendar's label instead of formatting its own.
 * With the active sprint S43 named, its name is in the Effort mid-sprint item, the workload action text, Work
 * items' {@code sprintName}, the release cards' {@code sprintNames} (Overview, Team utilization) and the Action
 * Center forecast. The two tests rename different sprints (S42, S43), so their order does not matter.
 */
class SprintNamesTest
{

    private static final int PORT = 18_299;

    private static final String TODAY = "2026-09-28";

    /** Closed on {@link #TODAY}: frozen at start-up. */
    private static final String SPRINT = "s:S42";

    private static final String NAME = "Atlas 2.4.1";

    /** The name given to the active sprint S43 in the task 2.1 test. */
    private static final String ACTIVE = "Atlas 2.5";

    private static TestApi api;

    private static String priya;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", TODAY), productModules());

        priya = api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");
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
    void aSprintRenamedAfterItsPeriodIsFrozenShowsItsNewName() throws Exception
    {
        TestApi.Session admin = RoleMatrix.session(api, RoleMatrix.ADMIN);

        JsonObject frozen = frozenInputs();

        assertEquals("Sprint 42", frozen.getJsonObject("period").getString("short"), "frozen with the label of its day");

        assertEquals("Sprint 42", sprintIn(periods(admin), SPRINT).getString("short"));

        // the connector renames it, and the sync publishes the cache invalidation
        api.exec("UPDATE sprints SET name = '" + NAME + "' WHERE key = 'S42'");

        CalcService.publish(api.vertx(), CalcService.ALL);

        JsonObject renamed = awaitShort(admin, SPRINT, NAME);

        assertEquals(SPRINT, renamed.getString("key"), "the key does not change");
        assertEquals(NAME + " · 7 Sep – 18 Sep", renamed.getString("label"));

        assertEquals(frozen, frozenInputs(), "the snapshot is not re-frozen");

        // the closed period's screens take the label from the calendar, not the snapshot
        JsonObject effort = ok(api.get("/api/v1/effort/people/" + priya + "?period=" + SPRINT, admin));

        assertEquals(NAME, effort.getJsonObject("period").getString("short"));
        assertEquals(renamed.getString("label"), effort.getJsonObject("period").getString("label"));

        JsonObject overview = ok(api.get("/api/v1/work/people/" + priya + "/overview?period=" + SPRINT, admin));

        assertEquals(NAME, overview.getJsonObject("period").getString("short"));
        assertEquals(Boolean.FALSE, overview.getBoolean("live"), "a closed period, read from its snapshot");
    }

    @Test
    void theActiveSprintsNameIsInEveryPayloadThatNamesIt() throws Exception
    {
        TestApi.Session admin = RoleMatrix.session(api, RoleMatrix.ADMIN);

        TestApi.Session manager = RoleMatrix.session(api, RoleMatrix.MANAGER);

        api.exec("UPDATE sprints SET name = '" + ACTIVE + "' WHERE key = 'S43'");

        // a mid-sprint addition for Priya: S43 started on 21 Sep, the issue joined it on 23 Sep
        String issue = api.queryOne("INSERT INTO issues (key, summary, issue_type, status, assignee_id, sprint_id, release_id,"
                + " story_points, source, external_id) SELECT 'SN-1', 'Mid-sprint addition', 'Story', 'In Progress', '" + priya
                + "', s.id, (SELECT r.id FROM releases r WHERE r.key = '8.3' LIMIT 1), 2, 'test', 'SN-1' FROM sprints s"
                + " WHERE s.key = 'S43' RETURNING id::text AS id").getString("id");

        api.exec("INSERT INTO issue_events (issue_id, occurred_at, kind, field, from_value, to_value, actor_name, summary) VALUES ('"
                + issue + "', '2026-09-23T11:04:00+05:30', 'sprint_change', 'sprint_change', NULL, 'S43', 'Rahul Nair', 'sprint_change')");

        CalcService.publish(api.vertx(), CalcService.ALL);

        awaitShort(admin, "s:S43", ACTIVE);

        // Effort: the mid-sprint item names the sprint the issue joined
        JsonArray detected = (JsonArray) fresh(EffortFormulas.DETECTED, new JsonObject().put("person", priya).put("period", "s:S43"));

        JsonObject midSprint = detected.stream().map(JsonObject.class::cast).filter(i -> "SN-1".equals(i.getString("ref")))
                .findFirst().orElseThrow(() -> new AssertionError("SN-1 not detected: " + detected.encode()));

        assertTrue(midSprint.getString("detectionNote").contains("Sprint field set to " + ACTIVE + " on 23 Sep"), midSprint.encode());

        // the workload action text (Priya is over capacity in S43 on the seed)
        JsonArray actions = ok(api.get("/api/v1/action_center/items", admin)).getJsonArray("items");

        JsonObject overload = actions.stream().map(JsonObject.class::cast)
                .filter(i -> "work.overload".equals(i.getString("key")) && priya.equals(i.getString("personId")))
                .findFirst().orElseThrow(() -> new AssertionError("no overload item for Priya: " + actions.encode()));

        assertTrue(overload.getString("text").contains("of capacity in " + ACTIVE + " — "), overload.getString("text"));

        // Work items of release 8.3: each S43 issue carries the sprint's name beside its key
        JsonObject items = ok(api.get("/api/v1/work/people/" + priya + "/work-items?period=r:8.3", admin));

        List<JsonObject> s43 = items.getJsonArray("items").stream().map(JsonObject.class::cast)
                .filter(i -> "S43".equals(i.getString("sprintKey"))).toList();

        assertTrue(s43.stream().anyMatch(i -> "SN-1".equals(i.getString("key"))), items.encode());
        assertTrue(s43.stream().allMatch(i -> ACTIVE.equals(i.getString("sprintName"))), items.encode());

        // the release cards: Overview and Team utilization
        JsonObject card = ok(api.get("/api/v1/work/people/" + priya + "/overview?period=r:8.3", admin)).getJsonObject("release");

        assertTrue(card.getJsonArray("sprints").contains("s:S43"), "sprints keep their keys");
        assertTrue(card.getJsonArray("sprintNames").contains(new JsonObject().put("key", "s:S43").put("name", ACTIVE)), card.encode());

        JsonObject team = ok(api.get("/api/v1/utilization/team?period=r:8.3", manager)).getJsonObject("release");

        assertTrue(team.getJsonArray("sprintNames").contains(new JsonObject().put("key", "s:S43").put("name", ACTIVE)), team.encode());

        // the Action Center forecast
        JsonObject forecast = ok(api.get("/api/v1/action_center/forecast", manager));

        assertEquals("s:S43", forecast.getString("sprint"));
        assertEquals(ACTIVE, forecast.getString("sprintName"));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A formula's value from a fresh {@link CalcService}, so nothing is cached.
     */
    private static Object fresh(String formula, JsonObject params) throws Exception
    {
        CalcService calc = new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), TODAY, false), FormulaRegistry.discover());

        return ResultGuard.unguard(TestApi.await(calc.get(formula, params)));
    }

    /**
     * The inputs of S42's snapshot, once the start-up close has frozen it.
     */
    private static JsonObject frozenInputs() throws Exception
    {
        String query = "SELECT inputs FROM calc_snapshots WHERE subject_key = '" + SPRINT + "' AND superseded_at IS NULL";

        for (int i = 0; i < 100; i++)
        {
            if (api.count("SELECT count(*) FROM calc_snapshots WHERE subject_key = '" + SPRINT + "' AND superseded_at IS NULL") > 0)
            {
                Object inputs = api.queryOne(query).getValue("inputs");

                return inputs instanceof JsonObject o ? o : new JsonObject(String.valueOf(inputs));
            }

            Thread.sleep(200);
        }

        return fail("S42 was not frozen at start-up");
    }

    /**
     * A sprint in the period list once its short label is {@code expected}: the invalidation event arrives on its
     * own.
     */
    private static JsonObject awaitShort(TestApi.Session session, String key, String expected) throws Exception
    {
        JsonObject sprint = null;

        for (int i = 0; i < 50; i++)
        {
            sprint = sprintIn(periods(session), key);

            if (expected.equals(sprint.getString("short")))
            {
                return sprint;
            }

            Thread.sleep(100);
        }

        return fail(key + " is still labelled " + sprint);
    }

    private static JsonArray periods(TestApi.Session session) throws Exception
    {
        return ok(api.get("/api/v1/periods", session)).getJsonArray("sprints");
    }

    private static JsonObject sprintIn(JsonArray sprints, String key)
    {
        for (Object o : sprints)
        {
            JsonObject sprint = (JsonObject) o;

            if (key.equals(sprint.getString("key")))
            {
                return sprint;
            }
        }

        return fail(key + " is not in the period list");
    }

    private static JsonObject ok(io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer> res)
    {
        assertTrue(res.statusCode() == 200, res.statusCode() + " " + res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static String[] productModules()
    {
        List<String> ids = ModuleRegistry.discover().modules().stream()
                .filter(m -> Files.isDirectory(Path.of("modules", m.id())))
                .map(PulseModule::id)
                .toList();

        return ids.toArray(String[]::new);
    }
}
