package com.pulse.modules.users;

import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec admin/users-roles, rbac-visibility "Managers' user-management limits" and "Permission changes
 * take effect immediately", privacy-retention "Deactivation versus deletion" and "User deletion and
 * anonymization" (task 5.1), through the real server with the demo seed. Tests that change the demo
 * organisation work on accounts they create, so they do not depend on each other's order.
 */
class UsersRoutesTest
{
    private static final int PORT = 18_151;

    private static final String TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();

    private static TestApi api;

    private static TestApi.Session admin;

    private static TestApi.Session manager;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);

        admin = RoleMatrix.session(api, RoleMatrix.ADMIN);
        manager = RoleMatrix.session(api, RoleMatrix.MANAGER);
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String userId(String email) throws Exception
    {
        return api.queryOne("SELECT id FROM users WHERE lower(email) = lower('" + email + "')").getString("id");
    }

    private static String roleId(String name) throws Exception
    {
        return api.queryOne("SELECT id FROM roles WHERE name = '" + name + "'").getString("id");
    }

    private static Set<String> emails(HttpResponse<Buffer> directory)
    {
        Set<String> out = new HashSet<>();

        directory.bodyAsJsonObject().getJsonArray("items").forEach(u -> out.add(((JsonObject) u).getString("email")));

        return out;
    }

    private static JsonObject add(TestApi.Session session, JsonObject body) throws Exception
    {
        HttpResponse<Buffer> res = api.post("/api/v1/users/items", session, body);

        assertEquals(201, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static JsonObject newUser(String name, String email, String role, String managerId) throws Exception
    {
        JsonObject body = new JsonObject().put("name", name).put("email", email).put("roleId", roleId(role))
                .put("title", "Engineer").put("department", "Platform").put("level", "L2");

        if (managerId != null)
        {
            body.put("managerId", managerId);
        }

        return add(admin, body);
    }

    private static TestApi.Session activate(String email) throws Exception
    {
        api.exec("UPDATE users SET status = 'Active', password_hash = public.crypt('demo123', public.gen_salt('bf', 4)) "
                + "WHERE lower(email) = lower('" + email + "')");

        return api.signIn(email, TestApi.DEMO_PASSWORD);
    }

    private static HttpResponse<Buffer> edit(TestApi.Session session, String id, JsonObject changes) throws Exception
    {
        int version = api.queryOne("SELECT version FROM users WHERE id = '" + id + "'").getInteger("version");

        return api.put("/api/v1/users/items/" + id, session, changes.copy().put("version", version));
    }

    private static JsonObject lastAudit(String action) throws Exception
    {
        return api.queryOne("SELECT actor_id, target_id, outcome, summary FROM audit_log WHERE action = '" + action
                + "' ORDER BY occurred_at DESC LIMIT 1");
    }

    // ------------------------------------------------------------------ directory

    @Test
    void directoryIsForUserManagers() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/users/items", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));

        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/users/items/{user:priya@pulse.demo}", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));

        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/users/items/{user:meera@pulse.demo}", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 403,      // not Rahul's report
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));
    }

    @Test
    void managersDirectoryShowsOnlyTheirReports() throws Exception
    {
        HttpResponse<Buffer> res = api.get("/api/v1/users/items?pageSize=200", manager);

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertEquals("team", res.bodyAsJsonObject().getString("scope"));

        Set<String> shown = emails(res);

        assertTrue(shown.containsAll(List.of("priya@pulse.demo", "arjun@pulse.demo", "rohit@pulse.demo")), shown.toString());
        assertFalse(shown.contains("meera@pulse.demo"), "not a report");
        assertFalse(shown.contains("rahul@pulse.demo"), "only reports appear");
        assertFalse(shown.contains("hr@pulse.demo"));

        JsonObject priya = res.bodyAsJsonObject().getJsonArray("items").stream().map(JsonObject.class::cast)
                .filter(u -> u.getString("email").equals("priya@pulse.demo")).findFirst().orElseThrow();

        assertEquals("Rahul Nair", priya.getJsonObject("manager").getString("name"));
        assertTrue(priya.getBoolean("canEdit"));
        assertEquals("L3", priya.getString("level"));

        JsonObject all = api.get("/api/v1/users/items?pageSize=200", admin).bodyAsJsonObject();

        assertEquals("all", all.getString("scope"));
        assertTrue(emails(api.get("/api/v1/users/items?pageSize=200", admin)).contains("meera@pulse.demo"));
        assertTrue(all.getJsonObject("counts").getLong("admins") >= 1);
        assertTrue(all.getJsonObject("counts").getLong("employees") >= 13);

        JsonObject self = all.getJsonArray("items").stream().map(JsonObject.class::cast)
                .filter(u -> u.getString("email").equals(RoleMatrix.ADMIN)).findFirst().orElseThrow();

        assertTrue(self.getBoolean("isSelf"));
        assertFalse(self.getBoolean("canDelete"), "nobody deletes themself");

        assertEquals(Set.of("arjun@pulse.demo"), emails(api.get("/api/v1/users/items?q=arjun", manager)), "search by name");
        assertTrue(emails(api.get("/api/v1/users/items?q=Manager&pageSize=200", admin)).contains("rahul@pulse.demo"),
                "search by role");
    }

    @Test
    void deactivatedReportsAreHiddenFromManagersAndSessionsEnd() throws Exception
    {
        JsonObject temp = newUser("Tara Shetty", "tara@pulse.demo", "Employee", userId(RoleMatrix.MANAGER));
        TestApi.Session tara = activate("tara@pulse.demo");

        assertTrue(emails(api.get("/api/v1/users/items?pageSize=200", manager)).contains("tara@pulse.demo"));

        HttpResponse<Buffer> res = edit(admin, temp.getString("id"), new JsonObject().put("status", "Deactivated"));

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertEquals("Deactivated", res.bodyAsJsonObject().getString("status"));

        assertFalse(emails(api.get("/api/v1/users/items?pageSize=200", manager)).contains("tara@pulse.demo"),
                "a Manager without users.all does not see deactivated reports");
        assertTrue(emails(api.get("/api/v1/users/items?pageSize=200", admin)).contains("tara@pulse.demo"));

        assertEquals(401, api.get("/api/v1/me", tara).statusCode(), "deactivation ends the user's sessions");
        assertEquals("user.deactivated", api.queryOne("SELECT action FROM audit_log WHERE target_id = '"
                + temp.getString("id") + "' AND action = 'user.deactivated'").getString("action"));
    }

    // ------------------------------------------------------------------ add

    @Test
    void adminAddsAnInvitedUserWithAnInviteAndNoPassword() throws Exception
    {
        HttpResponse<Buffer> withPassword = api.post("/api/v1/users/items", admin, new JsonObject()
                .put("name", "Pia Rao").put("email", "pia@pulse.demo").put("password", "secret12"));

        assertEquals(400, withPassword.statusCode(), "there is no password field");
        assertEquals("password", TestApi.error(withPassword).getString("field"));

        JsonObject created = newUser("Pia Rao", "pia@pulse.demo", "Employee", userId(RoleMatrix.MANAGER));

        assertEquals("Invited", created.getString("status"));
        assertEquals("Employee", created.getString("role"));
        assertEquals("Rahul Nair", created.getJsonObject("manager").getString("name"));

        String id = created.getString("id");

        assertNull(api.queryOne("SELECT password_hash FROM users WHERE id = '" + id + "'").getString("password_hash"));
        assertEquals(1, api.count("SELECT count(*) FROM people WHERE user_id = '" + id + "'"), "Employees get a profile");
        assertEquals(1, api.count("SELECT count(*) FROM outbox WHERE recipient = 'pia@pulse.demo' AND body LIKE '%set-password#token=%'"),
                "a set-password link is emailed");
        assertEquals(1, api.count("SELECT count(*) FROM reporting_lines WHERE user_id = '" + id + "' AND valid_to IS NULL "
                + "AND valid_from = '" + TODAY + "'"));

        JsonObject audit = lastAudit("user.created");

        assertEquals(id, audit.getString("target_id"));
        assertFalse(audit.getJsonObject("summary").encode().contains("pia@"), "no email in the audit summary");

        HttpResponse<Buffer> duplicate = api.post("/api/v1/users/items", admin,
                new JsonObject().put("name", "Pia Again").put("email", "PIA@pulse.demo"));

        assertEquals(409, duplicate.statusCode());
        assertEquals("A user with this email already exists.", TestApi.error(duplicate).getString("message"));

        HttpResponse<Buffer> badEmail = api.post("/api/v1/users/items", admin,
                new JsonObject().put("name", "No Email").put("email", "not-an-email"));

        assertEquals(400, badEmail.statusCode());
        assertEquals("Enter a valid work email.", TestApi.error(badEmail).getString("message"));

        JsonObject lead = newUser("Omar Lead", "omar@pulse.demo", "Manager", null);

        assertEquals(0, api.count("SELECT count(*) FROM people WHERE user_id = '" + lead.getString("id") + "'"),
                "only Employees get a performance profile");
    }

    @Test
    void managersAddOnlyEmployeesWhoReportToThem() throws Exception
    {
        JsonObject created = add(manager, new JsonObject().put("name", "Ravi Kumar").put("email", "ravi@pulse.demo")
                .put("level", "L1"));

        assertEquals("Employee", created.getString("role"));
        assertEquals(userId(RoleMatrix.MANAGER), created.getJsonObject("manager").getString("id"), "their new users report to them");

        HttpResponse<Buffer> admin = api.post("/api/v1/users/items", manager, new JsonObject()
                .put("name", "Eve Admin").put("email", "eve@pulse.demo").put("roleId", roleId("Admin")));

        assertEquals(403, admin.statusCode());
        assertEquals("refused", lastAudit("permission.refused").getString("outcome"));
        assertEquals(0, api.count("SELECT count(*) FROM users WHERE email = 'eve@pulse.demo'"));

        HttpResponse<Buffer> otherManager = api.post("/api/v1/users/items", manager, new JsonObject()
                .put("name", "Eve Elsewhere").put("email", "eve@pulse.demo").put("managerId", userId(RoleMatrix.ADMIN)));

        assertEquals(403, otherManager.statusCode());

        JsonObject options = api.get("/api/v1/users/options", manager).bodyAsJsonObject();

        assertEquals(1, options.getJsonArray("roles").size());
        assertEquals("Employee", options.getJsonArray("roles").getJsonObject(0).getString("name"));
        assertTrue(options.getBoolean("managerLocked"));
    }

    // ------------------------------------------------------------------ edit

    @Test
    void emailIsReadOnlyAndManagersStayWithinTheirLimits() throws Exception
    {
        String priya = userId("priya@pulse.demo");

        HttpResponse<Buffer> email = edit(admin, priya, new JsonObject().put("email", "new@pulse.demo"));

        assertEquals(400, email.statusCode());
        assertEquals("email", TestApi.error(email).getString("field"));

        HttpResponse<Buffer> peer = edit(manager, userId("meera@pulse.demo"), new JsonObject().put("title", "Lead"));

        assertEquals(403, peer.statusCode(), "a Manager editing someone who does not report to them gets 403");

        HttpResponse<Buffer> self = edit(manager, userId(RoleMatrix.MANAGER), new JsonObject().put("title", "Director"));

        assertEquals(422, self.statusCode());
        assertEquals("self_action_refused", TestApi.error(self).getString("code"));

        HttpResponse<Buffer> grant = edit(manager, priya, new JsonObject().put("roleId", roleId("Admin")));

        assertEquals(403, grant.statusCode(), "a Manager cannot grant Admin");
        assertEquals("refused", lastAudit("permission.refused").getString("outcome"));
        assertEquals("Employee", api.queryOne("SELECT r.name FROM users u JOIN roles r ON r.id = u.role_id WHERE u.id = '"
                + priya + "'").getString("name"));

        HttpResponse<Buffer> detach = edit(manager, priya, new JsonObject().putNull("managerId"));

        assertEquals(403, detach.statusCode(), "a Manager cannot move or detach their report");

        HttpResponse<Buffer> ok = edit(manager, priya, new JsonObject().put("title", "Software Engineer III"));

        assertEquals(200, ok.statusCode(), ok.bodyAsString());
        assertEquals("Software Engineer III", api.queryOne("SELECT title FROM people WHERE user_id = '" + priya + "'")
                .getString("title"), "the profile follows the account");

        HttpResponse<Buffer> stale = api.put("/api/v1/users/items/" + priya, admin,
                new JsonObject().put("title", "Old").put("version", 1));

        assertEquals(409, stale.statusCode());
    }

    @Test
    void managerChangesAreEffectiveDatedAndAudited() throws Exception
    {
        JsonObject temp = newUser("Lena Fox", "lena@pulse.demo", "Employee", userId(RoleMatrix.MANAGER));

        api.exec("UPDATE reporting_lines SET valid_from = '2025-01-01' WHERE user_id = '" + temp.getString("id") + "'");

        String hr = userId(RoleMatrix.ADMIN);

        HttpResponse<Buffer> res = edit(admin, temp.getString("id"), new JsonObject().put("managerId", hr));

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertEquals(hr, res.bodyAsJsonObject().getJsonObject("manager").getString("id"));

        assertEquals(1, api.count("SELECT count(*) FROM reporting_lines WHERE user_id = '" + temp.getString("id")
                + "' AND valid_to = '" + TODAY + "' AND manager_id = '" + userId(RoleMatrix.MANAGER) + "'"), "old line ends today");
        assertEquals(1, api.count("SELECT count(*) FROM reporting_lines WHERE user_id = '" + temp.getString("id")
                + "' AND valid_to IS NULL AND valid_from = '" + TODAY + "' AND manager_id = '" + hr + "'"), "new line starts today");

        JsonObject summary = lastAudit("user.updated").getJsonObject("summary");

        assertEquals(hr, summary.getJsonObject("managerId").getString("after"));

        HttpResponse<Buffer> role = edit(admin, temp.getString("id"), new JsonObject().put("roleId", roleId("Manager")));

        assertEquals(200, role.statusCode());
        assertEquals("Manager", lastAudit("user.updated").getJsonObject("summary").getJsonObject("role").getString("after"));
    }

    // ------------------------------------------------------------------ delete

    @Test
    void nobodyDeletesThemself() throws Exception
    {
        HttpResponse<Buffer> res = api.delete("/api/v1/users/items/" + userId(RoleMatrix.ADMIN), admin);

        assertEquals(422, res.statusCode());
        assertEquals("self_action_refused", TestApi.error(res).getString("code"));
        assertEquals("refused", lastAudit("users.delete").getString("outcome"));
    }

    @Test
    void deletingAManagerReassignsTheirReportsAndErasesPersonalData() throws Exception
    {
        JsonObject mona = newUser("Mona Pillai", "mona@pulse.demo", "Manager", userId(RoleMatrix.ADMIN));
        String monaId = mona.getString("id");

        for (int i = 1; i <= 4; i++)
        {
            newUser("Report " + i, "report" + i + "@pulse.demo", "Employee", monaId);
        }

        TestApi.Session monaSession = activate("mona@pulse.demo");

        JsonObject preview = api.get("/api/v1/users/items/" + monaId + "/deletion", admin).bodyAsJsonObject();

        assertEquals(4, preview.getInteger("reports"));
        assertTrue(preview.getJsonArray("removed").encode().contains("OKRs"));
        assertTrue(preview.getJsonArray("removed").encode().contains("Extra-work records"));
        assertFalse(preview.getJsonArray("kept").isEmpty());
        assertTrue(preview.getString("suggestion").contains("Deactivate"));

        HttpResponse<Buffer> res = api.delete("/api/v1/users/items/" + monaId, admin);

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertEquals(4, res.bodyAsJsonObject().getInteger("reportsReassigned"));

        String hr = userId(RoleMatrix.ADMIN);

        assertEquals(4, api.count("SELECT count(*) FROM reporting_lines rl JOIN users u ON u.id = rl.user_id "
                + "WHERE u.email LIKE 'report_@pulse.demo' AND rl.valid_to IS NULL AND rl.manager_id = '" + hr + "'"),
                "the 4 reports now report to the deleting Admin");

        assertEquals(0, api.count("SELECT count(*) FROM users WHERE name = 'Mona Pillai' OR lower(email) = 'mona@pulse.demo'"),
                "no table resolves the id to the name or email");
        assertEquals(0, api.count("SELECT count(*) FROM outbox WHERE body LIKE '%Mona%' OR recipient = 'mona@pulse.demo'"));
        assertEquals(1, api.count("SELECT count(*) FROM user_deletions WHERE user_id = '" + monaId + "' AND deleted_on = '"
                + TODAY + "'"), "a deletion record keeps the pseudonymous id and date");
        assertEquals(401, api.get("/api/v1/me", monaSession).statusCode(), "sessions end");
        assertEquals(404, api.get("/api/v1/users/items/" + monaId, admin).statusCode());
        assertFalse(emails(api.get("/api/v1/users/items?pageSize=200", admin)).contains(monaId + "@deleted.invalid"));
        assertEquals(4, lastAudit("user.deleted").getJsonObject("summary").getInteger("reportsReassigned"));
    }

    @Test
    void deletingAnEmployeeErasesTheProfileButKeepsItsId() throws Exception
    {
        JsonObject temp = newUser("Gita Bose", "gita@pulse.demo", "Employee", userId(RoleMatrix.MANAGER));
        String personId = api.queryOne("SELECT id FROM people WHERE user_id = '" + temp.getString("id") + "'").getString("id");

        HttpResponse<Buffer> peer = api.delete("/api/v1/users/items/" + userId("meera@pulse.demo"), manager);

        assertEquals(403, peer.statusCode(), "a Manager deletes only their own Employees");

        HttpResponse<Buffer> res = api.delete("/api/v1/users/items/" + temp.getString("id"), manager);

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject profile = api.queryOne("SELECT name, title, ctc FROM people WHERE id = '" + personId + "'");

        assertEquals("Deleted user", profile.getString("name"));
        assertNull(profile.getString("title"));
        assertEquals(0, api.count("SELECT count(*) FROM people WHERE name = 'Gita Bose'"));
    }

    // ------------------------------------------------------------------ roles

    @Test
    void permissionMatrixIsForRoleManagers() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/users/roles", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 403,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));

        JsonObject matrix = api.get("/api/v1/users/roles", admin).bodyAsJsonObject();

        JsonObject adminRole = matrix.getJsonArray("roles").getJsonObject(0);

        assertEquals("Admin", adminRole.getString("name"));
        assertTrue(adminRole.getBoolean("locked"));
        assertEquals(matrix.getJsonArray("permissions").size(), adminRole.getJsonArray("permissions").size(),
                "Admin holds every permission");
        assertTrue(matrix.getJsonArray("areas").contains("360 reviews"));

        JsonObject employee = matrix.getJsonArray("roles").getJsonObject(2);

        assertEquals("Employee", employee.getString("name"));
        assertFalse(employee.getJsonArray("menu").encode().contains("\"Users\""), "what each role sees");

        HttpResponse<Buffer> locked = api.put("/api/v1/users/roles/" + adminRole.getString("id") + "/permissions/rules.manage",
                admin, new JsonObject().put("granted", false));

        assertEquals(422, locked.statusCode());
        assertEquals("admin_locked", TestApi.error(locked).getString("code"));
    }

    @Test
    void aToggleAppliesOnTheNextRequestWithoutSigningInAgain() throws Exception
    {
        String managerRole = roleId("Manager");
        TestApi.Session rahul = api.signIn(RoleMatrix.MANAGER, TestApi.DEMO_PASSWORD);

        assertEquals(200, api.get("/api/v1/users/items", rahul).statusCode());

        HttpResponse<Buffer> off = api.put("/api/v1/users/roles/" + managerRole + "/permissions/users.team", admin,
                new JsonObject().put("granted", false));

        assertEquals(200, off.statusCode(), off.bodyAsString());
        assertFalse(off.bodyAsJsonObject().getJsonArray("permissions").contains("users.team"));

        try
        {
            assertEquals(403, api.get("/api/v1/users/items", rahul).statusCode(), "the next call is refused");
            assertEquals(200, api.get("/api/v1/me", rahul).statusCode(), "without needing to sign in again");

            JsonObject summary = lastAudit("role.permissions_changed").getJsonObject("summary");

            assertTrue(summary.getJsonArray("before").contains("users.team"));
            assertFalse(summary.getJsonArray("after").contains("users.team"));
        }
        finally
        {
            api.put("/api/v1/users/roles/" + managerRole + "/permissions/users.team", admin, new JsonObject().put("granted", true));
        }

        assertEquals(200, api.get("/api/v1/users/items", rahul).statusCode());
    }

    @Test
    void customRolesStartFromARoleAndAreDeletedOnlyWhenUnused() throws Exception
    {
        HttpResponse<Buffer> res = api.post("/api/v1/users/roles", admin, new JsonObject()
                .put("name", "HR Partner").put("fromRoleId", roleId("Manager")).put("description", "People partner"));

        assertEquals(201, res.statusCode(), res.bodyAsString());

        JsonObject role = res.bodyAsJsonObject();
        JsonArray managerPermissions = api.get("/api/v1/users/roles", admin).bodyAsJsonObject().getJsonArray("roles")
                .getJsonObject(1).getJsonArray("permissions");

        assertEquals(managerPermissions, role.getJsonArray("permissions"), "starts with Manager's permissions");
        assertEquals(0L, role.getLong("userCount"));
        assertFalse(role.getBoolean("builtIn"));

        HttpResponse<Buffer> duplicate = api.post("/api/v1/users/roles", admin,
                new JsonObject().put("name", "hr partner").put("fromRoleId", roleId("Employee")));

        assertEquals(409, duplicate.statusCode());

        JsonObject holder = newUser("Holly Role", "holly@pulse.demo", "HR Partner", null);

        HttpResponse<Buffer> inUse = api.delete("/api/v1/users/roles/" + role.getString("id"), admin);

        assertEquals(422, inUse.statusCode());
        assertEquals("role_in_use", TestApi.error(inUse).getString("code"));

        edit(admin, holder.getString("id"), new JsonObject().put("roleId", roleId("Employee")));

        assertEquals(204, api.delete("/api/v1/users/roles/" + role.getString("id"), admin).statusCode());
        assertEquals("role.deleted", api.queryOne("SELECT action FROM audit_log WHERE action = 'role.deleted' LIMIT 1")
                .getString("action"));

        HttpResponse<Buffer> builtIn = api.delete("/api/v1/users/roles/" + roleId("Employee"), admin);

        assertEquals(422, builtIn.statusCode());
        assertEquals("role_built_in", TestApi.error(builtIn).getString("code"));
    }
}
