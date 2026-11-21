package com.pulse.platform.policy;

import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.pulse.testsupport.RoleMatrix.ADMIN;
import static com.pulse.testsupport.RoleMatrix.EMPLOYEE;
import static com.pulse.testsupport.RoleMatrix.MANAGER;
import static com.pulse.testsupport.RoleMatrix.PEER;
import static com.pulse.testsupport.RoleMatrix.assertRoleMatrix;
import static com.pulse.testsupport.RoleMatrix.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3.5: the policy service end to end, through the test {@code probe} module
 * ({@code com.pulse.modules.probe.ProbeModule}): route permissions, visibility (self, direct report,
 * all.view, mentor exception, check-in for the direct manager only), effective-dated reporting lines,
 * uniform refusals and separation of duties.
 */
class PolicyServiceTest
{
    private static final int PORT = 18_195;

    private static final String BASE = "/api/v1/probe";

    private static final String PRIYA = "{person:" + EMPLOYEE + "}";

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);

        api.exec("CREATE TABLE probe_mentors (mentor_id uuid NOT NULL, mentee_id uuid NOT NULL)");

        // Arjun (Priya's peer, not her manager) mentors Priya.
        api.exec("INSERT INTO probe_mentors SELECT a.id, p.id FROM people a, people p "
                + "WHERE a.user_id = (SELECT id FROM users WHERE email = 'arjun@pulse.demo') "
                + "AND p.user_id = (SELECT id FROM users WHERE email = 'priya@pulse.demo')");

        // HR gets a performance profile so the self-approval rule has a subject.
        api.exec("INSERT INTO people (user_id, name, source, external_id) "
                + "SELECT id, name, 'test', 'hr' FROM users WHERE email = 'hr@pulse.demo'");
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
    void overviewFollowsSelfReportAndAllView() throws Exception
    {
        assertRoleMatrix(api, "GET " + BASE + "/people/" + PRIYA + "/overview", Map.of(
                ADMIN, 200,
                MANAGER, 200,
                EMPLOYEE, 200,
                PEER, 403));
    }

    @Test
    void peerAccessIsRefusedWithoutData() throws Exception
    {
        HttpResponse<Buffer> res = api.get(BASE + "/people/" + personId(EMPLOYEE) + "/overview", session(api, PEER));

        assertEquals(403, res.statusCode());
        assertEquals("no_access", TestApi.error(res).getString("code"));
        assertEquals("You don't have access", TestApi.error(res).getString("message"));
        assertEquals(Set.of("error"), res.bodyAsJsonObject().fieldNames());
    }

    @Test
    void mentorSeesTrainingButNotOkrsOrAttendance() throws Exception
    {
        assertRoleMatrix(api, "GET " + BASE + "/people/" + PRIYA + "/training", Map.of(
                ADMIN, 200,
                MANAGER, 200,
                EMPLOYEE, 200,
                PEER, 200));    // Arjun is Priya's mentor

        assertRoleMatrix(api, "GET " + BASE + "/people/" + PRIYA + "/okrs", Map.of(
                ADMIN, 200,
                MANAGER, 200,
                EMPLOYEE, 200,
                PEER, 403));

        // the mentor relation does not reach Neha, whom Arjun does not mentor
        assertEquals(403, api.get(BASE + "/people/" + personId("neha@pulse.demo") + "/training",
                session(api, PEER)).statusCode());
    }

    @Test
    void checkInTimesAreForThePersonAndDirectManagerOnly() throws Exception
    {
        assertRoleMatrix(api, "GET " + BASE + "/people/" + PRIYA + "/checkin", Map.of(
                ADMIN, 403,     // all.view does not reach check-in times
                MANAGER, 200,
                EMPLOYEE, 200,
                PEER, 403));
    }

    @Test
    void routePermissionIsDeclaredAndEnforced() throws Exception
    {
        assertRoleMatrix(api, "GET " + BASE + "/team", Map.of(
                ADMIN, 200,
                MANAGER, 200,
                EMPLOYEE, 403,
                PEER, 403));
    }

    @Test
    void listIsFilteredByScope() throws Exception
    {
        assertEquals(Set.of(EMPLOYEE), names(EMPLOYEE));

        Set<String> team = names(MANAGER);

        assertTrue(team.containsAll(Set.of("priya@pulse.demo", "arjun@pulse.demo", "neha@pulse.demo")), team.toString());
        assertTrue(!team.contains("meera@pulse.demo") && !team.contains("hr@pulse.demo"), "only direct reports: " + team);

        assertEquals(api.count("SELECT count(*) FROM people"),
                api.get(BASE + "/people?pageSize=200", session(api, ADMIN)).bodyAsJsonObject().getLong("total"));
    }

    @Test
    void existenceIsNotDisclosed() throws Exception
    {
        TestApi.Session manager = session(api, MANAGER);

        HttpResponse<Buffer> notReport = api.get(BASE + "/people/" + personId("hr@pulse.demo") + "/overview", manager);
        HttpResponse<Buffer> missing = api.get(BASE + "/people/" + UUID.randomUUID() + "/overview", manager);
        HttpResponse<Buffer> garbage = api.get(BASE + "/people/not-a-uuid/overview", manager);

        assertEquals(403, notReport.statusCode());
        RoleMatrix.assertSameRefusal(notReport, missing);
        RoleMatrix.assertSameRefusal(notReport, garbage);

        // only a caller who sees everyone learns that a record is missing
        assertEquals(404, api.get(BASE + "/people/" + UUID.randomUUID() + "/overview", session(api, ADMIN)).statusCode());
    }

    @Test
    void visibilityFollowsTheCurrentReportingLine() throws Exception
    {
        String today = "(now() AT TIME ZONE 'Asia/Kolkata')::date";

        // Kiran moves from Rahul to HR today: Rahul loses access at once.
        api.exec("UPDATE reporting_lines SET valid_to = " + today + " WHERE valid_to IS NULL "
                + "AND user_id = (SELECT id FROM users WHERE email = 'kiran@pulse.demo')");
        api.exec("INSERT INTO reporting_lines (user_id, manager_id, valid_from) SELECT k.id, h.id, " + today
                + " FROM users k, users h WHERE k.email = 'kiran@pulse.demo' AND h.email = 'hr@pulse.demo'");

        // Dev moves to HR from tomorrow: Rahul still sees Dev today.
        api.exec("UPDATE reporting_lines SET valid_to = " + today + " + 1 WHERE valid_to IS NULL "
                + "AND user_id = (SELECT id FROM users WHERE email = 'dev@pulse.demo')");
        api.exec("INSERT INTO reporting_lines (user_id, manager_id, valid_from) SELECT k.id, h.id, " + today + " + 1"
                + " FROM users k, users h WHERE k.email = 'dev@pulse.demo' AND h.email = 'hr@pulse.demo'");

        TestApi.Session manager = session(api, MANAGER);

        assertEquals(403, api.get(BASE + "/people/" + personId("kiran@pulse.demo") + "/overview", manager).statusCode());
        assertEquals(200, api.get(BASE + "/people/" + personId("dev@pulse.demo") + "/overview", manager).statusCode());
    }

    @Test
    void selfApprovalIsRefusedAndAudited() throws Exception
    {
        assertRoleMatrix(api, "POST " + BASE + "/increments/" + PRIYA + "/approve", Map.of(
                ADMIN, 200,
                MANAGER, 403,
                EMPLOYEE, 403,
                PEER, 403));

        HttpResponse<Buffer> own = api.post(BASE + "/increments/" + personId(ADMIN) + "/approve", session(api, ADMIN),
                new JsonObject());

        assertEquals(422, own.statusCode(), own.bodyAsString());
        assertEquals("self_action_refused", TestApi.error(own).getString("code"));
        assertEquals("You can't approve your own increment.", TestApi.error(own).getString("message"));

        JsonObject audit = api.queryOne("SELECT actor_id::text AS actor, target_id, outcome, request_id FROM audit_log "
                + "WHERE action = 'appraisal.increment.approve' ORDER BY occurred_at DESC LIMIT 1");

        assertEquals("refused", audit.getString("outcome"));
        assertEquals(personId(ADMIN), audit.getString("target_id"));
        assertEquals(api.queryOne("SELECT id::text AS id FROM users WHERE email = 'hr@pulse.demo'").getString("id"),
                audit.getString("actor"));
        assertEquals(TestApi.error(own).getString("requestId"), audit.getString("request_id"));
    }

    @Test
    void permissionRevocationAppliesOnTheNextRequest() throws Exception
    {
        TestApi.Session manager = api.signIn(MANAGER, TestApi.DEMO_PASSWORD);

        assertEquals(200, api.get(BASE + "/team", manager).statusCode());

        try
        {
            api.exec("DELETE FROM role_permissions WHERE permission = 'team.view' "
                    + "AND role_id = (SELECT id FROM roles WHERE name = 'Manager')");

            assertEquals(403, api.get(BASE + "/team", manager).statusCode());
        }
        finally
        {
            api.exec("INSERT INTO role_permissions (role_id, permission) SELECT id, 'team.view' FROM roles WHERE name = 'Manager'");
        }
    }

    private static Set<String> names(String email) throws Exception
    {
        JsonObject page = api.get(BASE + "/people?pageSize=200", session(api, email)).bodyAsJsonObject();

        Set<String> emails = new HashSet<>();

        page.getJsonArray("items").forEach(item ->
        {
            try
            {
                JsonObject row = api.queryOne("SELECT u.email FROM people p JOIN users u ON u.id = p.user_id WHERE p.id = '"
                        + ((JsonObject) item).getString("id") + "'");

                emails.add(row.getString("email"));
            }
            catch (Exception e)
            {
                throw new IllegalStateException(e);
            }
        });

        return emails;
    }

    private static String personId(String email) throws Exception
    {
        return api.queryOne("SELECT p.id::text AS id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = '"
                + email + "'").getString("id");
    }
}
