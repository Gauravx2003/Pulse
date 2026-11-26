package com.pulse.modules.work;

import com.pulse.calc.core.CalcService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 1.3 (change jira-sprint-names, design.md risk "Labels inside frozen snapshots"): a closed sprint keeps the
 * label text of its freeze in its snapshot's {@code inputs.period}, but every screen takes the period's label from
 * the calendar at read time ({@code core.period}, never frozen). So a sprint renamed after its period was frozen
 * shows the new name in the period list and on the closed period's screens, with no re-freeze and the same key.
 */
class SprintNamesTest
{

    private static final int PORT = 18_299;

    private static final String TODAY = "2026-09-28";

    /** Closed on {@link #TODAY}: frozen at start-up. */
    private static final String SPRINT = "s:S42";

    private static final String NAME = "Atlas 2.4.1";

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

        assertEquals("Sprint 42", sprintIn(periods(admin)).getString("short"));

        // the connector renames it, and the sync publishes the cache invalidation
        api.exec("UPDATE sprints SET name = '" + NAME + "' WHERE key = 'S42'");

        CalcService.publish(api.vertx(), CalcService.ALL);

        JsonObject renamed = awaitShort(admin, NAME);

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

    // ------------------------------------------------------------------ helpers

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
     * S42 in the period list once its short label is {@code expected}: the invalidation event arrives on its own.
     */
    private static JsonObject awaitShort(TestApi.Session session, String expected) throws Exception
    {
        JsonObject sprint = null;

        for (int i = 0; i < 50; i++)
        {
            sprint = sprintIn(periods(session));

            if (expected.equals(sprint.getString("short")))
            {
                return sprint;
            }

            Thread.sleep(100);
        }

        return fail("S42 is still labelled " + sprint);
    }

    private static JsonArray periods(TestApi.Session session) throws Exception
    {
        return ok(api.get("/api/v1/periods", session)).getJsonArray("sprints");
    }

    private static JsonObject sprintIn(JsonArray sprints)
    {
        for (Object o : sprints)
        {
            JsonObject sprint = (JsonObject) o;

            if (SPRINT.equals(sprint.getString("key")))
            {
                return sprint;
            }
        }

        return fail(SPRINT + " is not in the period list");
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
