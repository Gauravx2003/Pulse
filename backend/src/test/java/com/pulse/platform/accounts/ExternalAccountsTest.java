package com.pulse.platform.accounts;

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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 24.1 (change pulse-v2-live-connectors): migration V190, person matching through
 * {@code external_accounts} and the Admin routes under {@code /api/v1/integrations/accounts}.
 */
class ExternalAccountsTest
{
    private static final int PORT = 18_241;

    private static final String BASE = "/api/v1/integrations/accounts";

    private static TestApi api;

    private static TestApi.Session admin;

    private static ExternalAccountService accounts;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject(), "integrations");

        admin = RoleMatrix.session(api, RoleMatrix.ADMIN);

        accounts = new SqlExternalAccountService(api.sql());
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    private static String personOf(String email) throws Exception
    {
        return api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = '"
                + email + "'").getString("id");
    }

    private static JsonObject resolve(String tool, JsonObject account) throws Exception
    {
        return TestApi.await(accounts.resolve(tool, account));
    }

    private static String unique(String prefix)
    {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static boolean listed(JsonArray items, String id)
    {
        return items.stream().anyMatch(o -> ((JsonObject) o).getString("id").equals(id));
    }

    // ------------------------------------------------------------------ migration

    @Test
    void v190AppliesAfterV300() throws Exception
    {
        ClassLoader loader = getClass().getClassLoader();

        ScriptCatalog full = ScriptCatalog.load(loader,
                ScriptCatalog.sourcesFor(ModuleRegistry.discover(loader).modules()));

        // a Phase 1 database: every Phase 2 migration (V190-V199, V290-V299, V390-V399) is pending with V190,
        // since Track 2's V290 and V292 read external_accounts
        List<SqlScript> withoutV190 = full.scripts(SqlScript.Kind.MIGRATION).stream()
                .filter(s -> s.version() % 100 < 90)
                .toList();

        assertTrue(withoutV190.stream().anyMatch(s -> s.version() >= 300), "the catalogue has V300+");

        try (TestDb db = TestDb.open())
        {
            TestApi.await(MigrationRunner.migrate(db.ownerPool(), ScriptCatalog.of(withoutV190)));

            List<SqlScript> late = TestApi.await(MigrationRunner.migrate(db.ownerPool(), full));

            List<Integer> applied = late.stream().map(SqlScript::version).toList();

            assertEquals(190, applied.get(0), "V190 first: " + applied);
            assertTrue(applied.stream().allMatch(v -> v % 100 >= 90), "only Phase 2 migrations: " + applied);

            SqlExecutor sql = new SqlExecutor(db.ownerPool());

            JsonObject tables = TestApi.await(sql.queryOne("SELECT "
                    + "to_regclass('external_accounts') IS NOT NULL AS a, "
                    + "to_regclass('sync_state') IS NOT NULL AS b, "
                    + "to_regclass('integration_connections') IS NOT NULL AS c"));

            assertEquals(new JsonObject().put("a", true).put("b", true).put("c", true), tables);
        }
    }

    // ------------------------------------------------------------------ resolve

    @Test
    void emailMatchesIgnoringCase() throws Exception
    {
        JsonObject row = resolve("jira", new JsonObject()
                .put("accountId", unique("priya"))
                .put("displayName", "Priya")
                .put("email", "  Priya@Pulse.Demo "));

        assertEquals(personOf(RoleMatrix.EMPLOYEE), row.getString("personId"));
        assertEquals(row.getString("accountId"), row.getString("login"), "login defaults to the account id");
        assertFalse(row.getBoolean("isBot"));
    }

    @Test
    void hiddenEmailStaysUnmatchedUnderItsUsername() throws Exception
    {
        String username = unique("svc");

        JsonObject row = resolve("jira", new JsonObject().put("accountId", username).put("displayName", "Hidden"));

        assertNull(row.getString("personId"));

        JsonArray unmatched = TestApi.await(accounts.listUnmatched("jira")).getJsonArray("items");

        assertTrue(listed(unmatched, row.getString("id")));

        assertFalse(listed(TestApi.await(accounts.listUnmatched("gh")).getJsonArray("items"), row.getString("id")),
                "the tool filter applies");
    }

    @Test
    void resolveNeverDuplicatesAndKeepsTheAdminsLink() throws Exception
    {
        String username = unique("dev");

        JsonObject first = resolve("jira", new JsonObject().put("accountId", username).put("displayName", "Dev One"));

        String person = personOf(RoleMatrix.PEER);

        TestApi.await(accounts.link(null, UUID.fromString(first.getString("id")), UUID.fromString(person)));

        // seen again with an email of somebody else: same row, the Admin's link is kept
        JsonObject again = resolve("jira", new JsonObject()
                .put("accountId", username)
                .put("displayName", "Dev One (renamed)")
                .put("email", RoleMatrix.EMPLOYEE));

        assertEquals(first.getString("id"), again.getString("id"));
        assertEquals(person, again.getString("personId"));
        assertEquals("Dev One (renamed)", again.getString("displayName"));
        assertEquals(1, api.count("SELECT count(*) FROM external_accounts WHERE tool = 'jira' "
                + "AND tool_account_id = '" + username + "'"));

        // the same id in another tool is another account
        JsonObject github = resolve("gh", new JsonObject().put("accountId", username));

        assertNotEquals(first.getString("id"), github.getString("id"));
    }

    @Test
    void resolveRefusesBadInput()
    {
        assertTrue(accounts.resolve("jira", new JsonObject().put("accountId", "  ")).failed());
        assertTrue(accounts.resolve("Not A Tool", new JsonObject().put("accountId", "x")).failed());
        assertTrue(accounts.resolve("jira", null).failed());
    }

    // ------------------------------------------------------------------ Admin routes

    @Test
    void linkThenSecondLinkIsAConflict() throws Exception
    {
        JsonObject row = resolve("jira", new JsonObject().put("accountId", unique("link")));

        String id = row.getString("id");

        String priya = personOf(RoleMatrix.EMPLOYEE);

        String arjun = personOf(RoleMatrix.PEER);

        HttpResponse<Buffer> linked = api.put(BASE + "/" + id + "/link", admin, new JsonObject().put("personId", priya));

        assertEquals(200, linked.statusCode(), linked.bodyAsString());
        assertEquals(priya, linked.bodyAsJsonObject().getString("personId"));
        assertTrue(linked.bodyAsJsonObject().getString("personName").length() > 0);

        assertFalse(listed(api.get(BASE + "?tool=jira", admin).bodyAsJsonObject().getJsonArray("items"), id),
                "a linked account leaves the unmatched list");

        for (String other : List.of(arjun, priya))
        {
            HttpResponse<Buffer> again = api.put(BASE + "/" + id + "/link", admin, new JsonObject().put("personId", other));

            assertEquals(409, again.statusCode(), again.bodyAsString());
            assertEquals("already_linked", TestApi.error(again).getString("code"));
        }

        assertEquals(priya, api.queryOne("SELECT person_id FROM external_accounts WHERE id = '" + id + "'")
                .getString("person_id"), "the refused link left the existing one unchanged");

        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'integration.account.linked' "
                + "AND target_id = '" + id + "'"));

        HttpResponse<Buffer> unlinked = api.delete(BASE + "/" + id + "/link", admin);

        assertEquals(200, unlinked.statusCode(), unlinked.bodyAsString());
        assertNull(unlinked.bodyAsJsonObject().getString("personId"));
        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'integration.account.unlinked' "
                + "AND target_id = '" + id + "'"));

        HttpResponse<Buffer> relinked = api.put(BASE + "/" + id + "/link", admin, new JsonObject().put("personId", arjun));

        assertEquals(200, relinked.statusCode(), relinked.bodyAsString());
        assertEquals(arjun, relinked.bodyAsJsonObject().getString("personId"));
    }

    @Test
    void botLeavesTheUnmatchedList() throws Exception
    {
        String id = resolve("jira", new JsonObject().put("accountId", unique("jira-bot"))).getString("id");

        assertTrue(listed(api.get(BASE, admin).bodyAsJsonObject().getJsonArray("items"), id));

        HttpResponse<Buffer> bot = api.put(BASE + "/" + id + "/bot", admin, new JsonObject().put("isBot", true));

        assertEquals(200, bot.statusCode(), bot.bodyAsString());
        assertTrue(bot.bodyAsJsonObject().getBoolean("isBot"));

        assertFalse(listed(api.get(BASE, admin).bodyAsJsonObject().getJsonArray("items"), id));
        assertTrue(listed(api.get(BASE + "?status=bot", admin).bodyAsJsonObject().getJsonArray("items"), id));
        assertTrue(listed(api.get(BASE + "?status=all", admin).bodyAsJsonObject().getJsonArray("items"), id));

        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'integration.account.bot_changed' "
                + "AND target_id = '" + id + "'"));

        assertEquals(400, api.get(BASE + "?status=everything", admin).statusCode());
        assertEquals(400, api.get(BASE + "?nope=1", admin).statusCode());
    }

    @Test
    void unknownAccountOrPersonIsNotFound() throws Exception
    {
        String id = resolve("jira", new JsonObject().put("accountId", unique("nf"))).getString("id");

        HttpResponse<Buffer> missingAccount = api.put(BASE + "/" + UUID.randomUUID() + "/link", admin,
                new JsonObject().put("personId", personOf(RoleMatrix.EMPLOYEE)));

        HttpResponse<Buffer> notAnId = api.put(BASE + "/not-a-uuid/link", admin,
                new JsonObject().put("personId", personOf(RoleMatrix.EMPLOYEE)));

        HttpResponse<Buffer> missingPerson = api.put(BASE + "/" + id + "/link", admin,
                new JsonObject().put("personId", UUID.randomUUID().toString()));

        assertEquals(404, missingAccount.statusCode());
        RoleMatrix.assertSameRefusal(missingAccount, notAnId);
        RoleMatrix.assertSameRefusal(missingAccount, missingPerson);

        assertEquals(404, api.delete(BASE + "/" + UUID.randomUUID() + "/link", admin).statusCode());
        assertEquals(404, api.put(BASE + "/" + UUID.randomUUID() + "/bot", admin, new JsonObject().put("isBot", true))
                .statusCode());
    }

    @Test
    void forbiddenAndNonexistentLookAlike() throws Exception
    {
        String id = resolve("jira", new JsonObject().put("accountId", unique("hidden"))).getString("id");

        TestApi.Session manager = RoleMatrix.session(api, RoleMatrix.MANAGER);

        JsonObject body = new JsonObject().put("personId", personOf(RoleMatrix.EMPLOYEE));

        RoleMatrix.assertSameRefusal(api.put(BASE + "/" + id + "/link", manager, body),
                api.put(BASE + "/" + UUID.randomUUID() + "/link", manager, body));

        RoleMatrix.assertSameRefusal(api.delete(BASE + "/" + id + "/link", manager),
                api.delete(BASE + "/" + UUID.randomUUID() + "/link", manager));

        assertNull(api.queryOne("SELECT person_id FROM external_accounts WHERE id = '" + id + "'").getString("person_id"));
    }

    @Test
    void onlyAdminsManageAccounts() throws Exception
    {
        String id = resolve("jira", new JsonObject().put("accountId", unique("matrix"))).getString("id");

        Map<String, Integer> listing = Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 403,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403,
                RoleMatrix.MENTOR, 403,
                RoleMatrix.OWNER, 403);

        Map<String, Integer> invalidWrite = Map.of(
                RoleMatrix.ADMIN, 400,
                RoleMatrix.MANAGER, 403,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403,
                RoleMatrix.MENTOR, 403,
                RoleMatrix.OWNER, 403);

        RoleMatrix.assertRoleMatrix(api, "GET " + BASE, listing);
        RoleMatrix.assertRoleMatrix(api, "PUT " + BASE + "/" + id + "/link", new JsonObject(), invalidWrite);
        RoleMatrix.assertRoleMatrix(api, "PUT " + BASE + "/" + id + "/bot", new JsonObject(), invalidWrite);
        RoleMatrix.assertRoleMatrix(api, "DELETE " + BASE + "/" + id + "/link", listing);
    }

    // ------------------------------------------------------------------ stub

    @Test
    void stubFollowsTheSameRules() throws Exception
    {
        UUID priya = UUID.randomUUID();

        InMemoryExternalAccountService stub = new InMemoryExternalAccountService().person(priya, "Priya", "priya@corp.com");

        JsonObject matched = TestApi.await(stub.resolve("jira", new JsonObject()
                .put("accountId", "priya").put("email", "Priya@Corp.com")));

        assertEquals(priya.toString(), matched.getString("personId"));

        JsonObject hidden = TestApi.await(stub.resolve("jira", new JsonObject().put("accountId", "svc")));

        assertEquals(1, TestApi.await(stub.listUnmatched("jira")).getJsonArray("items").size());

        UUID hiddenId = UUID.fromString(hidden.getString("id"));

        TestApi.await(stub.link(null, hiddenId, priya));

        assertTrue(stub.link(null, hiddenId, priya).failed(), "a second link is refused");
        assertEquals(2, stub.all().size());
    }
}
