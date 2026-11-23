package com.pulse.modules.appraisal;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Appraisal & increments through the real server (task 22.1; spec appraisal/appraisal): access and audit,
 * the cap, exceptions, budget, the frozen score at submission, send-back, approval and release.
 */
class AppraisalRoutesTest
{
    private static final int PORT = 18_360;

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "imports", "reviews", "training", "appraisal");
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    private static TestApi.Session as(String email) throws Exception
    {
        return RoleMatrix.session(api, email);
    }

    private static JsonObject ok(HttpResponse<Buffer> res)
    {
        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static JsonObject table(String email) throws Exception
    {
        return ok(api.get("/api/v1/appraisal/proposals", as(email)));
    }

    private static JsonObject row(JsonObject table, String personId)
    {
        for (Object o : table.getJsonArray("people"))
        {
            if (personId.equals(((JsonObject) o).getString("personId")))
            {
                return (JsonObject) o;
            }
        }

        return null;
    }

    private static String personOf(String email) throws Exception
    {
        return api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = '" + email + "'").getString("id");
    }

    @Test
    void employeesAreRefusedAndEveryReadIsAudited() throws Exception
    {
        long refused = api.count("SELECT count(*) FROM audit_log WHERE outcome = 'refused'");

        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/appraisal/proposals", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));

        assertTrue(api.count("SELECT count(*) FROM audit_log WHERE outcome = 'refused'") > refused);

        long reads = api.count("SELECT count(*) FROM audit_log WHERE action = 'salary.read'");

        table(RoleMatrix.MANAGER);

        assertEquals(reads + 1, api.count("SELECT count(*) FROM audit_log WHERE action = 'salary.read'"));
    }

    @Test
    void theCapAndExceptionsAreEnforcedServerSide() throws Exception
    {
        String priya = personOf(RoleMatrix.EMPLOYEE);

        HttpResponse<Buffer> cap = api.put("/api/v1/appraisal/proposals/" + priya, as(RoleMatrix.MANAGER), new JsonObject().put("pct", 22));

        assertEquals(422, cap.statusCode(), cap.bodyAsString());

        assertEquals("over_cap", TestApi.error(cap).getString("code"));

        JsonObject current = row(table(RoleMatrix.MANAGER), priya);

        JsonObject rec = current.getJsonObject("recommendation");

        // The exception rule applies while the proposal is still a draft (another test may have released it).
        if (rec.getBoolean("eligible") && "Draft".equals(current.getJsonObject("proposal").getString("status")))
        {
            double outside = rec.getJsonObject("band").getNumber("hi").doubleValue() + 1;

            HttpResponse<Buffer> noReason = api.put("/api/v1/appraisal/proposals/" + priya, as(RoleMatrix.MANAGER),
                    new JsonObject().put("pct", Math.min(outside, 20)));

            if (Math.min(outside, 20) > rec.getJsonObject("band").getNumber("hi").doubleValue())
            {
                assertEquals(422, noReason.statusCode(), noReason.bodyAsString());

                assertEquals("reason_required", TestApi.error(noReason).getString("code"));
            }
        }
    }

    @Test
    void submitFreezesApproveAndReleaseFollowAndSendBackRecomputes() throws Exception
    {
        TestApi.Session rahul = as(RoleMatrix.MANAGER);

        TestApi.Session hr = as(RoleMatrix.ADMIN);

        JsonObject before = table(RoleMatrix.MANAGER);

        assertFalse(before.getJsonArray("people").isEmpty());

        // Over budget: everyone at the 20% cap is refused with the amounts.
        for (Object o : before.getJsonArray("people"))
        {
            JsonObject r = (JsonObject) o;

            api.put("/api/v1/appraisal/proposals/" + r.getString("personId"), rahul,
                    new JsonObject().put("pct", 20).put("reason", "Testing the budget limit only"));
        }

        HttpResponse<Buffer> over = api.post("/api/v1/appraisal/proposals/submit", rahul, new JsonObject());

        assertEquals(409, over.statusCode(), over.bodyAsString());

        assertEquals("over_budget", TestApi.error(over).getString("code"));

        // Spec "Missing CTC": a person with no CTC shows "No CTC" and is not submitted.
        JsonObject first = before.getJsonArray("people").getJsonObject(0);

        api.exec("UPDATE people SET ctc = NULL WHERE id = '" + first.getString("personId") + "'");

        ok(api.post("/api/v1/appraisal/proposals/apply", rahul, new JsonObject()));

        JsonObject submitted = ok(api.post("/api/v1/appraisal/proposals/submit", rahul, new JsonObject()));

        assertTrue(submitted.getInteger("submitted") > 0, submitted.encode());

        assertTrue(submitted.getJsonArray("noCtc").contains(first.getString("name")), submitted.encode());

        JsonObject after = table(RoleMatrix.MANAGER);

        JsonObject frozen = null;

        for (Object o : after.getJsonArray("people"))
        {
            JsonObject r = (JsonObject) o;

            if ("Submitted".equals(r.getJsonObject("proposal").getString("status")) && r.getValue("score") != null)
            {
                frozen = r;
            }
        }

        assertTrue(frozen != null, after.encodePrettily());

        assertEquals("Draft", row(after, first.getString("personId")).getJsonObject("proposal").getString("status"));

        // Spec "Later rule edit": new weights after submission leave the submitted score alone.
        api.exec("INSERT INTO rule_versions (version, rule_set, reason) SELECT version + 1, jsonb_set(rule_set, '{appraisal,w}', "
                + "'{\"perf\": 100, \"r360\": 0, \"mgr\": 0, \"disc\": 0}'::jsonb), 'Test: all on performance' FROM rule_versions "
                + "ORDER BY version DESC LIMIT 1");

        assertEquals(frozen.getValue("score"), row(table(RoleMatrix.MANAGER), frozen.getString("personId")).getValue("score"));

        String person = frozen.getString("personId");

        // Spec "Send back": back to Draft with a reason; the manager resubmits and it is frozen again.
        HttpResponse<Buffer> shortReason = api.post("/api/v1/appraisal/proposals/" + person + "/send-back", hr,
                new JsonObject().put("reason", "No"));

        assertEquals(422, shortReason.statusCode());

        JsonObject back = ok(api.post("/api/v1/appraisal/proposals/" + person + "/send-back", hr,
                new JsonObject().put("reason", "Please recheck the 360° part with the team lead.")));

        assertEquals("Sent back", back.getJsonObject("proposal").getString("status"));

        assertEquals("Please recheck the 360° part with the team lead.", back.getJsonObject("proposal").getString("sendBackReason"));

        assertEquals(403, api.post("/api/v1/appraisal/proposals/" + person + "/approve", rahul, new JsonObject()).statusCode());

        ok(api.post("/api/v1/appraisal/proposals/apply", rahul, new JsonObject()));

        ok(api.post("/api/v1/appraisal/proposals/submit", rahul, new JsonObject()));

        assertEquals(2, api.count("SELECT count(*) FROM calc_snapshots s JOIN appraisal_proposals p ON s.subject_key = p.id::text "
                + "WHERE s.kind = 'appraisal' AND p.person_id = '" + person + "'"));

        // Approve one, approve all, release letters effective on the cycle date.
        assertEquals("Approved", ok(api.post("/api/v1/appraisal/proposals/" + person + "/approve", hr, new JsonObject()))
                .getJsonObject("proposal").getString("status"));

        JsonObject all = ok(api.post("/api/v1/appraisal/proposals/approve-all", hr, new JsonObject()));

        assertTrue(all.getInteger("approved") >= 0);

        JsonObject released = ok(api.post("/api/v1/appraisal/release", hr, new JsonObject()));

        assertTrue(released.getInteger("released") > 0, released.encode());

        assertEquals("2026-10-01", released.getString("effectiveOn"));

        assertEquals("Released", row(table(RoleMatrix.ADMIN), person).getJsonObject("proposal").getString("status"));

        // A released proposal can't be edited.
        assertEquals(409, api.put("/api/v1/appraisal/proposals/" + person, rahul, new JsonObject().put("pct", 5)).statusCode());

        assertTrue(api.count("SELECT count(*) FROM audit_log WHERE action IN ('appraisal.increment.approve', 'appraisal.letter.released', "
                + "'appraisal.increment.send_back', 'appraisal.submitted')") >= 4);

        // Spec "Export": released proposals as payroll CSV, formula-safe, audited, for appraisal roles only.
        api.exec("UPDATE people SET name = '=HYPERLINK(\"http://x\")' WHERE id = '" + person + "'");

        long exports = api.count("SELECT count(*) FROM audit_log WHERE action = 'salary.export'");

        HttpResponse<Buffer> csv = api.get("/api/v1/appraisal/export", hr);

        assertEquals(200, csv.statusCode(), csv.bodyAsString());

        assertTrue(csv.getHeader("Content-Type").startsWith("text/csv"));

        assertTrue(csv.bodyAsString().replace("\uFEFF", "").startsWith("name,level,current_ctc_lakh,increment_pct,new_ctc_lakh"),
                csv.bodyAsString());

        assertTrue(csv.bodyAsString().contains("'=HYPERLINK"), csv.bodyAsString());

        assertEquals(exports + 1, api.count("SELECT count(*) FROM audit_log WHERE action = 'salary.export'"));

        assertEquals(403, api.get("/api/v1/appraisal/export", as(RoleMatrix.EMPLOYEE)).statusCode());
    }

    @Test
    void theEvidencePanelShowsTheYearWithoutReviewerIdentity() throws Exception
    {
        String priya = personOf(RoleMatrix.EMPLOYEE);

        JsonObject panel = ok(api.get("/api/v1/appraisal/people/" + priya + "/evidence", as(RoleMatrix.MANAGER)));

        assertEquals(priya, panel.getJsonObject("row").getString("personId"));

        assertTrue(panel.getJsonArray("quarterlyIndex").size() >= 1, panel.encodePrettily());

        assertTrue(panel.getJsonObject("evidence").containsKey("training"));

        JsonObject comments = panel.getJsonObject("comments360");

        if (comments != null)
        {
            String text = comments.encode();

            assertFalse(comments.getJsonObject("groups").containsKey("self"), text);

            assertFalse(text.contains("reviewer") || text.contains("@pulse.demo"), text);
        }

        assertEquals(403, api.get("/api/v1/appraisal/people/" + priya + "/evidence", as(RoleMatrix.EMPLOYEE)).statusCode());

        assertEquals(404, api.get("/api/v1/appraisal/people/not-a-person/evidence", as(RoleMatrix.MANAGER)).statusCode());
    }

    @Test
    void ctcStaysOutOfOtherExports() throws Exception
    {
        // Spec "Salary source": CTC only in the appraisal export.
        HttpResponse<Buffer> users = api.get("/api/v1/imports/users/export", as(RoleMatrix.ADMIN));

        assertEquals(200, users.statusCode(), users.bodyAsString());

        // The ctc column stays in the template layout so a file checks back, but it is never filled.
        String[] lines = users.bodyAsString().replace("\uFEFF", "").split("\r?\n");

        assertTrue(lines[0].endsWith(",ctc"), lines[0]);

        for (int i = 1; i < lines.length; i++)
        {
            assertTrue(lines[i].isEmpty() || lines[i].endsWith(","), lines[i]);
        }
    }

    @Test
    void theCycleDefaultsToFirstOctoberAndHrMovesIt() throws Exception
    {
        JsonObject cycle = ok(api.get("/api/v1/appraisal/cycle", as(RoleMatrix.MANAGER)));

        assertEquals(2026, cycle.getInteger("year"));

        assertEquals(403, api.put("/api/v1/appraisal/cycle", as(RoleMatrix.MANAGER), new JsonObject().put("effectiveOn", "2026-11-01")
                .put("version", cycle.getInteger("version"))).statusCode());

        JsonObject moved = ok(api.put("/api/v1/appraisal/cycle", as(RoleMatrix.ADMIN), new JsonObject().put("effectiveOn", "2026-10-01")
                .put("version", cycle.getInteger("version"))));

        assertEquals("2026-10-01", moved.getString("effectiveOn"));
    }
}
