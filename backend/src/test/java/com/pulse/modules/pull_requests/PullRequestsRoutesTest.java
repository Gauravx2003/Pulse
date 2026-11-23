package com.pulse.modules.pull_requests;

import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 10.2: the pull request endpoints on a real server with the Track 2 demo seed and "today" pinned
 * to 28 Sep 2026: the role matrix, the Person tab (All grouped by month), the Team tab, the PR detail
 * (pending checks, title rewrite, link out) and the standard any user may read. The standard maths is
 * covered in {@code PrStandardTest} and {@code PrFormulasTest}, the rewrites in {@code PrViewsTest}.
 */
class PullRequestsRoutesTest
{

    private static final int PORT = 18_280;

    private static final String API = "/api/v1/pull_requests";

    private static TestApi api;

    private static String priya;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress");

        priya = api.queryOne("SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    @Test
    void theTeamTabIsForManagersAndEveryoneReadsTheStandard() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/team", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));

        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/people/{person:priya@pulse.demo}", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 403));

        RoleMatrix.assertRoleMatrix(api, "GET " + API + "/standard", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 200));
    }

    @Test
    void theStandardComesFromRules() throws Exception
    {
        JsonObject standard = get(API + "/standard", RoleMatrix.EMPLOYEE);

        assertEquals(400, standard.getInteger("maxLines"));
        assertEquals(2, standard.getInteger("minApprovals"));
        assertEquals(85, standard.getInteger("target"));
        assertEquals(7, standard.getJsonArray("checks").size());
    }

    @Test
    void thePersonTabHasTheListWithADotPerCheckAndTheCommentMix() throws Exception
    {
        JsonObject body = get(API + "/people/" + priya + "?period=m:2026-09", RoleMatrix.EMPLOYEE);

        assertTrue(body.getBoolean("periodPicker"));

        JsonObject view = body.getJsonObject("view");

        assertFalse(view.getJsonArray("prs").isEmpty());
        assertTrue(view.getJsonArray("prs").stream().allMatch(pr -> !((JsonObject) pr).getJsonArray("checks").isEmpty()));
        assertFalse(view.getJsonArray("checkShare").isEmpty());
        assertNotNull(body.getJsonObject("stats").getValue("comp"));
    }

    @Test
    void allGroupsThePrsByMonthAndHidesTheDropdown() throws Exception
    {
        JsonObject body = get(API + "/people/" + priya + "?period=all", RoleMatrix.EMPLOYEE);

        assertFalse(body.getBoolean("periodPicker"));

        JsonArray groups = body.getJsonArray("groups");

        assertTrue(groups.size() > 1, groups.encode());

        int listed = groups.stream().mapToInt(g -> ((JsonObject) g).getJsonArray("prs").size()).sum();

        assertEquals(body.getJsonObject("view").getJsonArray("prs").size(), listed);
    }

    @Test
    void anOpenPrWithNoBuildIsPendingAndAFailingTitleHasARewrite() throws Exception
    {
        JsonObject open = api.queryOne("SELECT id, build_passed FROM pull_requests WHERE status = 'Active' ORDER BY number LIMIT 1");

        JsonObject detail = get(API + "/prs/" + open.getString("id"), RoleMatrix.ADMIN);

        // an open PR cannot be judged on approvals yet, nor on its build unless the build already failed (prChecks)
        assertEquals("pending", state(detail, "appr"));
        assertEquals(Boolean.FALSE.equals(open.getValue("build_passed")) ? "fail" : "pending", state(detail, "build"));
        assertNotNull(detail.getJsonObject("pr").getString("tool"));

        for (Object row : api.sql().queryList("SELECT id FROM pull_requests").toCompletionStage().toCompletableFuture().get())
        {
            JsonObject pr = get(API + "/prs/" + ((JsonObject) row).getString("id"), RoleMatrix.ADMIN);

            boolean titleFails = pr.getJsonArray("checks").stream().map(c -> (JsonObject) c)
                    .anyMatch(c -> "title".equals(c.getString("k")) && "fail".equals(c.getString("state")));

            // a missing value reads as {"notApplicable": true}, as every calculated result does
            assertEquals(titleFails, pr.getValue("suggestedTitle") instanceof String, pr.getJsonObject("pr").getString("number"));
        }
    }

    @Test
    void aPeerWhoNeitherWroteNorReviewedAPrGets404() throws Exception
    {
        JsonObject other = api.queryOne("""
                SELECT p.id FROM pull_requests p JOIN people a ON a.id = p.author_id
                WHERE a.external_id = 'ps' AND NOT EXISTS (
                    SELECT 1 FROM pr_reviews r JOIN people v ON v.id = r.reviewer_id
                    WHERE r.pull_request_id = p.id AND v.external_id = 'am')
                LIMIT 1""");

        HttpResponse<Buffer> res = api.get(API + "/prs/" + other.getString("id"), RoleMatrix.session(api, RoleMatrix.PEER));

        assertEquals(404, res.statusCode(), res.bodyAsString());
        assertEquals(200, api.get(API + "/prs/" + other.getString("id"), RoleMatrix.session(api, RoleMatrix.EMPLOYEE)).statusCode());
    }

    @Test
    void theTeamTabHasPeopleAndThePrsMergedBelowTheStandard() throws Exception
    {
        JsonObject body = get(API + "/team?period=m:2026-09", RoleMatrix.MANAGER);

        assertEquals("Platform", body.getString("team"));
        assertFalse(body.getJsonArray("people").isEmpty());
        assertNotNull(body.getJsonObject("stats").getValue("comp"));
        assertTrue(body.getJsonArray("belowStandard").stream().allMatch(pr -> ((JsonObject) pr).getString("title") != null));

        HttpResponse<Buffer> unknown = api.get(API + "/team?team=Sales", RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertEquals(400, unknown.statusCode());
        assertEquals("team", TestApi.error(unknown).getString("field"));
    }

    private static String state(JsonObject detail, String check)
    {
        return detail.getJsonArray("checks").stream().map(c -> (JsonObject) c)
                .filter(c -> check.equals(c.getString("k"))).findFirst().orElseThrow().getString("state");
    }

    private static JsonObject get(String path, String email) throws Exception
    {
        HttpResponse<Buffer> res = api.get(path, RoleMatrix.session(api, email));

        assertEquals(200, res.statusCode(), path + ": " + res.bodyAsString());

        return res.bodyAsJsonObject();
    }
}
