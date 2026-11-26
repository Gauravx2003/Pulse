package com.pulse.modules.users;

import com.pulse.platform.module.ModuleRegistry;
import com.pulse.platform.module.PulseModule;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 24.8 (change pulse-v2-live-connectors, spec privacy-retention "External accounts on deletion"):
 * deleting a person unlinks and pseudonymizes their live tool accounts, keeps their live rows with no
 * person, and leaves team totals unchanged.
 *
 * <p>Ravi gets a Jira account {@code ravi.k} linked to him, a live open issue in the active sprint and two
 * events: one he wrote, one where another account assigned the issue to him, and one where he took it. After the delete, no
 * table holds his Jira username, display name or email; the issue moves from his row to the team's
 * unassigned issues; the erased account is neither listed as unmatched nor linkable.
 */
class LiveAccountDeletionTest
{
    private static final int PORT = 18_248;

    private static final String TODAY = "2026-09-28";

    private static final String LOGIN = "ravi.k";

    private static final String NAME = "Ravi Kulkarni";

    private static final String EMAIL = "ravi.k@jira.example";

    private static TestApi api;

    private static TestApi.Session admin;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", TODAY).put("http.userPerMinute", 100_000)
                .put("calc.closePeriods", false), productModules());

        admin = RoleMatrix.session(api, RoleMatrix.ADMIN);
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
    void deletingAPersonErasesTheirToolAccountsAndKeepsTeamTotals() throws Exception
    {
        String managerUser = api.queryOne("SELECT id FROM users WHERE lower(email) = lower('" + RoleMatrix.MANAGER + "')")
                .getString("id");

        JsonObject body = new JsonObject().put("name", NAME).put("email", "ravi@pulse.demo")
                .put("roleId", api.queryOne("SELECT id FROM roles WHERE name = 'Employee'").getString("id"))
                .put("title", "Engineer").put("department", "Platform").put("level", "L2").put("managerId", managerUser);

        HttpResponse<Buffer> added = api.post("/api/v1/users/items", admin, body);

        assertEquals(201, added.statusCode(), added.bodyAsString());

        String userId = added.bodyAsJsonObject().getString("id");

        String person = api.queryOne("SELECT id::text AS id FROM people WHERE user_id = '" + userId + "'").getString("id");

        String sprint43 = api.queryOne("SELECT id::text AS id FROM sprints WHERE number = 43").getString("id");

        String account = api.queryOne("INSERT INTO external_accounts (tool, tool_account_id, login, display_name, email,"
                + " person_id) VALUES ('jira', '" + LOGIN + "', '" + LOGIN + "', '" + NAME + "', '" + EMAIL + "', '" + person
                + "') RETURNING id::text AS id").getString("id");

        api.exec("INSERT INTO issues (key, summary, status, sprint_id, story_points, estimate_hours, remaining_hours,"
                + " started_on, source, external_id, synced_at, html_url, assignee_account_id) VALUES ('JIRA-801',"
                + " 'Live poller retry', 'In Progress', '" + sprint43 + "', 3, 10, 8, '2026-09-22', 'jira', 'JIRA-801',"
                + " now(), 'http://jira.test/browse/JIRA-801', '" + LOGIN + "')");

        api.exec("INSERT INTO issue_events (issue_id, occurred_at, kind, field, from_value, to_value, actor_name, summary,"
                + " source, external_id, synced_at, actor_account_id) SELECT id, '2026-09-25 10:00+05:30', 'transition',"
                + " 'status', 'To Do', 'In Progress', '" + NAME + "', 'Status → In Progress', 'jira', 'JIRA-801|h1.0', now(),"
                + " '" + LOGIN + "' FROM issues WHERE external_id = 'JIRA-801'");

        api.exec("INSERT INTO issue_events (issue_id, occurred_at, kind, field, from_value, to_value, actor_name, summary,"
                + " source, external_id, synced_at, actor_account_id) SELECT id, '2026-09-22 09:00+05:30', 'assignee_change',"
                + " 'assignee', NULL, '" + NAME + "', 'Lead Dev', 'Assigned to " + NAME + "', 'jira', 'JIRA-801|h0.0', now(),"
                + " 'lead.dev' FROM issues WHERE external_id = 'JIRA-801'");

        // he took the issue himself: his own event names him as the new assignee
        api.exec("INSERT INTO issue_events (issue_id, occurred_at, kind, field, from_value, to_value, actor_name, summary,"
                + " source, external_id, synced_at, actor_account_id) SELECT id, '2026-09-22 09:05+05:30', 'assignee_change',"
                + " 'assignee', 'Lead Dev', '" + NAME + "', '" + NAME + "', 'Assigned to " + NAME + "', 'jira', 'JIRA-801|h0.1',"
                + " now(), '" + LOGIN + "' FROM issues WHERE external_id = 'JIRA-801'");

        JsonObject before = team();

        int unassignedBefore = before.getJsonObject("unassigned").getInteger("open");

        HttpResponse<Buffer> preview = api.get("/api/v1/users/items/" + userId + "/deletion", admin);

        assertTrue(preview.bodyAsJsonObject().getJsonArray("removed").encode().contains("Jira and GitHub accounts"),
                "the dialog lists the unlinking");
        assertTrue(preview.bodyAsJsonObject().getJsonArray("kept").encode().contains("Jira and GitHub activity"),
                "the dialog lists the kept live rows");

        HttpResponse<Buffer> deleted = api.delete("/api/v1/users/items/" + userId, admin);

        assertEquals(200, deleted.statusCode(), deleted.bodyAsString());

        // no username, display name or email remains for the deleted person
        String anyMatch = "('" + LOGIN + "', '" + NAME + "', '" + EMAIL + "')";

        assertEquals(0, api.count("SELECT count(*) FROM external_accounts WHERE person_id = '" + person + "'"
                + " OR tool_account_id IN " + anyMatch + " OR login IN " + anyMatch + " OR display_name IN " + anyMatch
                + " OR email IN " + anyMatch));
        assertEquals(0, api.count("SELECT count(*) FROM issues WHERE assignee_account_id IN " + anyMatch));
        assertEquals(0, api.count("SELECT count(*) FROM issue_events WHERE actor_account_id IN " + anyMatch
                + " OR actor_name IN " + anyMatch + " OR from_value IN " + anyMatch + " OR to_value IN " + anyMatch
                + " OR summary LIKE '%" + NAME + "%'"));

        JsonObject erased = api.queryOne("SELECT tool_account_id, login, display_name, email, person_id FROM external_accounts"
                + " WHERE id = '" + account + "'");

        assertEquals("erased:" + account, erased.getString("tool_account_id"));
        assertEquals("Deleted user", erased.getString("login"));

        // the live rows stay, with no person, and the other account's event keeps its author
        assertEquals(1, api.count("SELECT count(*) FROM issues_with_assignee WHERE external_id = 'JIRA-801'"
                + " AND assignee_id IS NULL AND assignee_account_id = 'erased:" + account + "'"));
        assertEquals(3, api.count("SELECT count(*) FROM issue_events e JOIN issues i ON i.id = e.issue_id"
                + " WHERE i.external_id = 'JIRA-801'"));
        assertEquals(1, api.count("SELECT count(*) FROM issue_events WHERE actor_account_id = 'lead.dev'"
                + " AND actor_name = 'Lead Dev' AND to_value = 'Deleted user' AND summary = 'Assigned to Deleted user'"));

        // team totals unchanged: the issue moves to the team's unassigned issues
        JsonObject after = team();

        assertEquals(before.getJsonObject("kpis").getInteger("open"), after.getJsonObject("kpis").getInteger("open"),
                "team open issues unchanged");
        assertEquals(unassignedBefore + 1, after.getJsonObject("unassigned").getInteger("open"));

        // the erased account is nobody to link
        String unmatched = api.get("/api/v1/integrations/accounts?status=unmatched&pageSize=200", admin).bodyAsString();

        assertFalse(unmatched.contains(account), "not offered as unmatched");

        String other = api.queryOne("SELECT p.id::text AS id FROM people p WHERE p.source = 'seed' LIMIT 1").getString("id");

        HttpResponse<Buffer> link = api.put("/api/v1/integrations/accounts/" + account + "/link", admin,
                new JsonObject().put("personId", other));

        assertEquals(422, link.statusCode(), link.bodyAsString());
        assertEquals(0, api.count("SELECT count(*) FROM external_accounts WHERE id = '" + account + "' AND person_id IS NOT NULL"));
    }

    private static JsonObject team() throws Exception
    {
        HttpResponse<Buffer> res = api.get("/api/v1/utilization/team", admin);

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
