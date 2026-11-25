package com.pulse.modules.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.SqlScript;
import com.pulse.platform.db.TestDb;
import com.pulse.platform.module.ModuleRegistry;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 25.1 (change pulse-v2-live-connectors): migration V290 adds {@code issues.html_url} and
 * {@code issues.assignee_account_id}, an index on (source, synced_at) and the read view
 * {@code issues_with_assignee}. Seed rows keep their stored assignee; a live row takes the person linked
 * to its tool account in {@code external_accounts}, and none while the account is unmatched or a bot.
 * The {@code calc/work} issue loaders read the view, so a linked live issue reaches the person's Work
 * items and an unmatched one does not.
 */
class LiveIssuesTest
{

    private static final int PORT = 18_293;

    private static final String API = "/api/v1/work";

    private static TestApi api;

    private static TestApi.Session admin;

    private static String priya;

    private static String sprint43;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort",
                "progress", "integrations");

        admin = RoleMatrix.session(api, RoleMatrix.ADMIN);

        priya = api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");

        sprint43 = api.queryOne("SELECT id::text AS id FROM sprints WHERE number = 43").getString("id");
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
    void migrationAddsTheColumnsTheIndexAndTheView() throws Exception
    {
        assertEquals(2, api.count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'issues'
                  AND column_name IN ('html_url', 'assignee_account_id') AND is_nullable = 'YES'"""));

        assertEquals(1, api.count("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()"
                + " AND indexname = 'issues_source_synced_idx' AND indexdef LIKE '%(source, synced_at)%'"));

        // the app role (api.sql()) reads the view
        assertTrue(api.queryOne("SELECT count(*) AS n FROM issues_with_assignee").getLong("n") > 0);
    }

    @Test
    void onAPhase1DatabaseV290AppliesAfterV190() throws Exception
    {
        ClassLoader loader = getClass().getClassLoader();

        ScriptCatalog full = ScriptCatalog.load(loader, ScriptCatalog.sourcesFor(ModuleRegistry.discover(loader).modules()));

        // every Phase 1 migration (V3xx included) already applied, V190 and V290 pulled together
        List<SqlScript> phase1 = full.scripts(SqlScript.Kind.MIGRATION).stream()
                .filter(s -> s.version() != 190 && s.version() != 290)
                .toList();

        try (TestDb db = TestDb.open())
        {
            TestApi.await(MigrationRunner.migrate(db.ownerPool(), ScriptCatalog.of(phase1)));

            List<SqlScript> late = TestApi.await(MigrationRunner.migrate(db.ownerPool(), full));

            assertEquals(List.of(190, 290), late.stream().map(SqlScript::version).toList());

            JsonObject view = TestApi.await(new SqlExecutor(db.ownerPool())
                    .queryOne("SELECT to_regclass('issues_with_assignee') IS NOT NULL AS present"));

            assertTrue(view.getBoolean("present"));
        }
    }

    @Test
    void seedRowsKeepTheirStoredAssignee() throws Exception
    {
        assertEquals(api.count("SELECT count(*) FROM issues"), api.count("SELECT count(*) FROM issues_with_assignee"));

        assertEquals(0, api.count("""
                SELECT count(*) FROM issues i JOIN issues_with_assignee v ON v.id = i.id
                WHERE i.assignee_account_id IS NULL AND v.assignee_id IS DISTINCT FROM i.assignee_id"""));

        assertTrue(api.count("SELECT count(*) FROM issues_with_assignee WHERE source = 'seed' AND assignee_id IS NOT NULL") > 0);
    }

    @Test
    void liveRowsTakeTheLinkedPersonAndNoneWhenUnmatchedOrABot() throws Exception
    {
        account("jira", "v-linked", priya, false);
        account("jira", "v-bot", priya, true);
        account("gh", "v-other-tool", priya, false);
        account("jira", "v-unmatched", null, false);

        live("VIEW-1", "v-linked");
        live("VIEW-2", "v-bot");
        live("VIEW-3", "v-other-tool");
        live("VIEW-4", "v-unmatched");
        live("VIEW-5", "v-never-seen");

        assertEquals(priya, assigneeOf("VIEW-1"));
        assertNull(assigneeOf("VIEW-2"), "a bot account counts for nobody");
        assertNull(assigneeOf("VIEW-3"), "an account of another tool does not match a jira row");
        assertNull(assigneeOf("VIEW-4"), "an unmatched account counts for nobody");
        assertNull(assigneeOf("VIEW-5"), "an account not in external_accounts counts for nobody");

        JsonObject row = api.queryOne("SELECT html_url AS url, assignee_account_id AS account FROM issues_with_assignee"
                + " WHERE key = 'VIEW-1'");

        assertEquals("http://jira.test/browse/VIEW-1", row.getString("url"));
        assertEquals("v-linked", row.getString("account"));
    }

    @Test
    void theWorkItemsLoaderCountsALiveIssueOnlyOnceItsAccountIsLinked() throws Exception
    {
        String accountId = account("jira", "priya.live", null, false);

        live("LIVE-101", "priya.live");

        assertFalse(listed("LIVE-101"), "unmatched: not in Priya's work items");

        HttpResponse<Buffer> linked = api.put("/api/v1/integrations/accounts/" + accountId + "/link", admin,
                new JsonObject().put("personId", priya));

        assertEquals(200, linked.statusCode(), linked.bodyAsString());

        // a sync or a link drops the cached issue reads; the link's own invalidation is task 25.3
        api.vertx().eventBus().publish(CalcService.INVALIDATE_ADDRESS,
                new JsonObject().put("topics", new JsonArray().add(WorkloadFormulas.ISSUES)));

        boolean shown = false;

        for (int i = 0; i < 50 && !shown; i++)
        {
            shown = listed("LIVE-101");

            if (!shown)
            {
                Thread.sleep(100);
            }
        }

        assertTrue(shown, "linked: in Priya's work items from the next read, with no re-sync");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Adds an {@code external_accounts} row and returns its id.
     */
    private static String account(String tool, String accountId, String personId, boolean bot) throws Exception
    {
        return api.queryOne("INSERT INTO external_accounts (tool, tool_account_id, login, person_id, is_bot) VALUES ('"
                + tool + "', '" + accountId + "', '" + accountId + "', " + (personId == null ? "NULL" : "'" + personId + "'")
                + ", " + bot + ") RETURNING id::text AS id").getString("id");
    }

    /**
     * Adds a live Jira issue in the active Sprint 43, assigned to a tool account and with no stored person,
     * as the connector writes it.
     */
    private static void live(String key, String accountId) throws Exception
    {
        api.exec("INSERT INTO issues (key, summary, status, sprint_id, story_points, estimate_hours, remaining_hours,"
                + " source, external_id, synced_at, html_url, assignee_account_id) VALUES ('" + key + "', 'Live issue "
                + key + "', 'In Progress', '" + sprint43 + "', 2, 8, 6, 'jira', '" + key + "', now(),"
                + " 'http://jira.test/browse/" + key + "', '" + accountId + "')");
    }

    private static String assigneeOf(String key) throws Exception
    {
        return api.queryOne("SELECT assignee_id::text AS id FROM issues_with_assignee WHERE key = '" + key + "'")
                .getString("id");
    }

    private static boolean listed(String key) throws Exception
    {
        HttpResponse<Buffer> res = api.get(API + "/people/" + priya + "/work-items?q=" + key, admin);

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject().getJsonArray("items").stream().anyMatch(i -> key.equals(((JsonObject) i).getString("key")));
    }
}
