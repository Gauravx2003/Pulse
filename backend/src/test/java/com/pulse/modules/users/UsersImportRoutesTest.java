package com.pulse.modules.users;

import com.pulse.platform.auth.SessionHandler;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Users import through the real server (task 16.3; spec admin/users-roles "User CSV import and
 * export"): roles, managers' limits, a manager in the same file, ctc for users.all only, the trainee
 * warning, invites, and undo of created and updated accounts.
 */
class UsersImportRoutesTest
{
    private static final int PORT = 18_317;

    private static TestApi api;

    private static WebClient client;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null, "imports");

        client = WebClient.create(api.vertx());
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    private static JsonObject check(String email, String mode, String csv) throws Exception
    {
        TestApi.Session session = RoleMatrix.session(api, email);

        HttpResponse<Buffer> res = await(client.post(PORT, "localhost", "/api/v1/imports/users/check?mode=" + mode + "&fileName=users.csv")
                .putHeader("X-Real-IP", TestApi.newIp())
                .putHeader("Cookie", session.cookie())
                .putHeader(SessionHandler.CSRF_HEADER, session.csrf())
                .putHeader("Content-Type", "text/csv")
                .sendBuffer(Buffer.buffer(csv.getBytes(StandardCharsets.UTF_8))));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static String commit(String email, JsonObject checked) throws Exception
    {
        HttpResponse<Buffer> res = api.post("/api/v1/imports/users/commit", RoleMatrix.session(api, email),
                new JsonObject().put("checkId", checked.getString("checkId")));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject().getString("importId");
    }

    private static HttpResponse<Buffer> undo(String importId) throws Exception
    {
        return api.post("/api/v1/imports/history/" + importId + "/undo", RoleMatrix.session(api, RoleMatrix.ADMIN), new JsonObject());
    }

    private static long count(String sql) throws Exception
    {
        return api.count(sql);
    }

    @Test
    void hrAddsAManagerAndTheirReportInOneFileAndUndoRemovesThem() throws Exception
    {
        JsonObject checked = check(RoleMatrix.ADMIN, UsersImportType.UPSERT, """
                name,email,role,manager_email,title,department,level,ctc
                Tara Iyer,tara.iyer@pulse.demo,Employee,maya.k@pulse.demo,Engineering Trainee,Platform,L1,6.5
                Maya Kapoor,maya.k@pulse.demo,Manager,,Engineering Manager,Platform,,
                """);

        assertEquals(2, checked.getInteger("new"), checked.encodePrettily());

        assertTrue(checked.encode().contains("Trainees are Employees"), checked.encodePrettily());

        String importId = commit(RoleMatrix.ADMIN, checked);

        assertEquals("Invited", api.queryOne("SELECT status FROM users WHERE email = 'tara.iyer@pulse.demo'").getString("status"));

        assertEquals(1, count("SELECT count(*) FROM reporting_lines rl JOIN users u ON u.id = rl.user_id JOIN users m ON m.id = rl.manager_id "
                + "WHERE u.email = 'tara.iyer@pulse.demo' AND m.email = 'maya.k@pulse.demo' AND rl.valid_to IS NULL"));

        assertEquals("6.50", api.queryOne("SELECT p.ctc::text AS ctc FROM people p JOIN users u ON u.id = p.user_id "
                + "WHERE u.email = 'tara.iyer@pulse.demo'").getString("ctc"));

        // Only Employees get a performance profile; both get a set-password invite.
        assertEquals(0, count("SELECT count(*) FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = 'maya.k@pulse.demo'"));

        assertEquals(2, count("SELECT count(*) FROM password_tokens t JOIN users u ON u.id = t.user_id "
                + "WHERE u.email IN ('tara.iyer@pulse.demo', 'maya.k@pulse.demo')"));

        assertEquals(200, undo(importId).statusCode());

        assertEquals(0, count("SELECT count(*) FROM users WHERE email IN ('tara.iyer@pulse.demo', 'maya.k@pulse.demo')"));
    }

    @Test
    void anInternRoleIsRefusedWithAHint() throws Exception
    {
        JsonObject checked = check(RoleMatrix.ADMIN, UsersImportType.UPSERT, "name,email,role\nIra Shah,ira@pulse.demo,intern\n");

        assertEquals(1, checked.getInteger("errors"));

        assertTrue(checked.encode().contains("a trainee is an Employee"), checked.encodePrettily());
    }

    @Test
    void managersImportOnlyEmployeesOfTheirOwnTeam() throws Exception
    {
        JsonObject checked = check(RoleMatrix.MANAGER, UsersImportType.UPSERT, """
                name,email,role,manager_email,ctc
                Lead Person,lead@pulse.demo,Manager,,
                New Report,new.report@pulse.demo,Employee,hr@pulse.demo,9
                HR Person,hr@pulse.demo,,,
                Rahul Nair,rahul@pulse.demo,,,
                """);

        String body = checked.encode();

        assertTrue(body.contains(UsersService.MSG_ROLE_NOT_ALLOWED), body);

        assertTrue(body.contains("Set to you"), body);

        assertTrue(body.contains("ctc ignored"), body);

        assertTrue(body.contains("You can’t edit"), body);

        assertTrue(body.contains("your own account"), body);

        assertEquals(1, checked.getInteger("ready"), body);

        commit(RoleMatrix.MANAGER, checked);

        assertEquals(1, count("SELECT count(*) FROM reporting_lines rl JOIN users u ON u.id = rl.user_id JOIN users m ON m.id = rl.manager_id "
                + "WHERE u.email = 'new.report@pulse.demo' AND m.email = 'rahul@pulse.demo' AND rl.valid_to IS NULL"));

        assertEquals(0, count("SELECT count(*) FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = 'new.report@pulse.demo' "
                + "AND p.ctc IS NOT NULL"));
    }

    @Test
    void anUpdateIsUndoneAndNewOnlySkipsExistingUsers() throws Exception
    {
        String before = api.queryOne("SELECT title FROM users WHERE email = 'neha@pulse.demo'").getString("title");

        JsonObject skipped = check(RoleMatrix.ADMIN, UsersImportType.NEW_ONLY, "name,email,title\nNeha Joshi,neha@pulse.demo,Staff Engineer\n");

        assertEquals(0, skipped.getInteger("ready"));

        assertTrue(skipped.encode().contains("already exists"));

        JsonObject checked = check(RoleMatrix.ADMIN, UsersImportType.UPSERT, "name,email,title,level\nNeha Joshi,neha@pulse.demo,Staff Engineer,L4\n");

        assertEquals(1, checked.getInteger("update"), checked.encodePrettily());

        String importId = commit(RoleMatrix.ADMIN, checked);

        assertEquals("Staff Engineer", api.queryOne("SELECT title FROM users WHERE email = 'neha@pulse.demo'").getString("title"));

        // An empty role keeps the account's role.
        assertEquals("Employee", api.queryOne("SELECT r.name FROM users u JOIN roles r ON r.id = u.role_id WHERE u.email = 'neha@pulse.demo'")
                .getString("name"));

        assertEquals(200, undo(importId).statusCode());

        assertEquals(before, api.queryOne("SELECT title FROM users WHERE email = 'neha@pulse.demo'").getString("title"));

        assertEquals(1, count("SELECT count(*) FROM reporting_lines rl JOIN users u ON u.id = rl.user_id WHERE u.email = 'neha@pulse.demo' "
                + "AND rl.valid_to IS NULL"));
    }

    @Test
    void managersSeeOnlyOkrsUsersAndVisitsCards() throws Exception
    {
        // Spec "Manager's cards": attendance and holidays need attendance.upload or rules.manage.
        String types = api.get("/api/v1/imports/types", RoleMatrix.session(api, RoleMatrix.MANAGER)).bodyAsString();

        assertTrue(types.contains("\"users\"") && types.contains("\"visits\""), types);

        assertFalse(types.contains("\"attendance\"") || types.contains("\"holidays\""), types);
    }

    @Test
    void theTemplateSampleChecksAndCommits() throws Exception
    {
        HttpResponse<Buffer> template = api.get("/api/v1/imports/users/template", RoleMatrix.session(api, RoleMatrix.ADMIN));

        assertEquals(200, template.statusCode(), template.bodyAsString());

        JsonObject checked = check(RoleMatrix.ADMIN, UsersImportType.UPSERT, template.bodyAsString());

        assertEquals(0, checked.getInteger("errors"), checked.encodePrettily());

        assertEquals(1, checked.getInteger("ready"), checked.encodePrettily());

        commit(RoleMatrix.ADMIN, checked);
    }
}
