package com.pulse.modules.rules;

import com.pulse.calc.core.RuleCatalogue;
import com.pulse.calc.core.SnapshotStore;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec platform/rules-thresholds (task 5.2), through the real server with the demo seed. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RulesRoutesTest
{
    private static final int PORT = 18_150;

    private static TestApi api;

    private static TestApi.Session admin;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);

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

    private static int version() throws Exception
    {
        return api.get("/api/v1/rules/groups", admin).bodyAsJsonObject().getInteger("version");
    }

    private static HttpResponse<Buffer> save(String group, JsonObject values) throws Exception
    {
        return api.put("/api/v1/rules/groups/" + group, admin, new JsonObject().put("values", values).put("version", version()));
    }

    private static JsonObject rulesInForce() throws Exception
    {
        return await(SnapshotStore.currentRules(api.sql())).getJsonObject("rules");
    }

    // ------------------------------------------------------------------ access

    @Test
    @Order(1)
    void onlyRuleEditorsSeeRules() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/rules/groups", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 403,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));

        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/rules/groups/appraisal_standards", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 403,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));
    }

    // ------------------------------------------------------------------ defaults

    @Test
    @Order(2)
    void freshInstallEqualsDocumentedDefaults() throws Exception
    {
        JsonObject seeded = api.queryOne("SELECT rule_set FROM rule_versions WHERE version = 1").getJsonObject("rule_set");

        assertEquals(RuleCatalogue.defaults(), seeded, "seed version 1 must equal the catalogue defaults");

        JsonObject body = api.get("/api/v1/rules/groups", admin).bodyAsJsonObject();

        assertEquals(RuleCatalogue.groupKeys().size(), body.getJsonArray("groups").size(), "Admin sees every card");

        for (Object g : body.getJsonArray("groups"))
        {
            for (Object f : ((JsonObject) g).getJsonArray("fields"))
            {
                JsonObject field = (JsonObject) f;

                assertTrue(RulesService.same(field.getValue("default"), field.getValue("value")),
                        field.getString("path") + " equals its default");
            }
        }

        JsonObject att = RuleCatalogue.defaults().getJsonObject("att");

        assertEquals(3, att.getInteger("officeDays"));
        assertEquals(2, att.getInteger("notice"));
        assertEquals(7, att.getInteger("noticeLong"));
        assertEquals("10:30", att.getString("lateAfter"));
        assertEquals(3, att.getInteger("grace"));

        JsonObject appraisal = RuleCatalogue.defaults().getJsonObject("appraisal");

        assertEquals(new JsonObject().put("perf", 50).put("r360", 20).put("mgr", 20).put("disc", 10), appraisal.getJsonObject("w"));
        assertEquals(20, appraisal.getInteger("max"));
        assertEquals(12, appraisal.getInteger("budget"));
        assertEquals(6, appraisal.getInteger("minMonths"));
    }

    // ------------------------------------------------------------------ saving

    @Test
    @Order(3)
    void adminEditsAThresholdAndItIsInForceNextRequest() throws Exception
    {
        int before = version();

        HttpResponse<Buffer> res = save(RuleCatalogue.STALENESS, new JsonObject().put("staleDays", 3));

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertEquals(before + 1, res.bodyAsJsonObject().getInteger("version"));
        assertEquals(1, res.bodyAsJsonObject().getInteger("changed"));

        assertEquals(3, rulesInForce().getInteger("staleDays"), "the rule set CalcService reads uses 3");
        assertEquals(3, api.queryOne("SELECT (value #>> '{}')::int AS v FROM rules WHERE key = 'staleDays'").getInteger("v"));

        JsonObject audit = api.queryOne("SELECT summary FROM audit_log WHERE action = 'rules.changed' ORDER BY occurred_at DESC LIMIT 1")
                .getJsonObject("summary");

        assertEquals(new JsonObject().put("before", 2).put("after", 3), audit.getJsonObject("changes").getJsonObject("staleDays"));

        JsonObject lastChanged = res.bodyAsJsonObject().getJsonObject("lastChanged");

        assertEquals("Anita Desai", lastChanged.getJsonObject("actor").getString("name"), "each card shows who changed it");
    }

    @Test
    @Order(4)
    void indexWeightsMustTotal100() throws Exception
    {
        int before = version();

        HttpResponse<Buffer> res = save(RuleCatalogue.INDEX, new JsonObject()
                .put("weights.delivery", 35).put("weights.quality", 15).put("weights.culture", 25)
                .put("weights.okr", 15).put("weights.growth", 20));

        assertEquals(422, res.statusCode(), res.bodyAsString());
        assertEquals("weights_total", TestApi.error(res).getString("code"));
        assertEquals(110.0, TestApi.error(res).getDouble("total"));
        assertEquals(before, version(), "nothing was saved");

        HttpResponse<Buffer> ok = save(RuleCatalogue.INDEX, new JsonObject()
                .put("weights.delivery", 30).put("weights.growth", 15));

        assertEquals(200, ok.statusCode(), ok.bodyAsString());
        assertEquals(100.0, ok.bodyAsJsonObject().getDouble("total"));

        HttpResponse<Buffer> culture = save(RuleCatalogue.CULTURE, new JsonObject().put("vw.0", 30));

        assertEquals(422, culture.statusCode(), "culture value weights must total 100 too");
    }

    @Test
    @Order(5)
    void valuesAreCheckedAgainstTheirRangeAndType() throws Exception
    {
        HttpResponse<Buffer> range = save(RuleCatalogue.STALENESS, new JsonObject().put("staleDays", 9));

        assertEquals(400, range.statusCode());
        assertEquals("values.staleDays", TestApi.error(range).getString("field"));

        HttpResponse<Buffer> type = save(RuleCatalogue.STALENESS, new JsonObject().put("nudge", "yes"));

        assertEquals(400, type.statusCode());

        HttpResponse<Buffer> whole = save(RuleCatalogue.STALENESS, new JsonObject().put("staleDays", 2.5));

        assertEquals(400, whole.statusCode());

        HttpResponse<Buffer> other = save(RuleCatalogue.STALENESS, new JsonObject().put("under", 50));

        assertEquals(400, other.statusCode(), "a rule of another card is refused");
        assertEquals("values.under", TestApi.error(other).getString("field"));

        for (String bad : new String[] {"25:00", "10:60", "9:5", "half past ten", ""})
        {
            HttpResponse<Buffer> late = save(RuleCatalogue.ATTENDANCE, new JsonObject().put("att.lateAfter", bad));

            assertEquals(400, late.statusCode(), bad);
            assertEquals("values.att.lateAfter", TestApi.error(late).getString("field"));
        }

        HttpResponse<Buffer> late = save(RuleCatalogue.ATTENDANCE, new JsonObject().put("att.lateAfter", "9:45"));

        assertEquals(200, late.statusCode(), late.bodyAsString());
        assertEquals("09:45", rulesInForce().getJsonObject("att").getString("lateAfter"));
    }

    @Test
    @Order(6)
    void unsafeTitlePatternsAreRejectedWithAnExplanation() throws Exception
    {
        for (String unsafe : new String[] {"^(a+)+$", "(\\w+\\s?)*$", "^(x+x+)+y", "^(a|a)*$", "^(a|aa)+$"})
        {
            HttpResponse<Buffer> res = save(RuleCatalogue.PR_STANDARD, new JsonObject().put("prTitle", unsafe));

            assertEquals(422, res.statusCode(), unsafe + ": " + res.bodyAsString());
            assertEquals("unsafe_pattern", TestApi.error(res).getString("code"));
            assertFalse(TestApi.error(res).getString("message").isBlank(), "the refusal explains why");
        }

        HttpResponse<Buffer> invalid = save(RuleCatalogue.PR_STANDARD, new JsonObject().put("prTitle", "^(feat"));

        assertEquals(422, invalid.statusCode());

        HttpResponse<Buffer> tooLong = save(RuleCatalogue.PR_STANDARD, new JsonObject().put("prTitle", "a".repeat(201)));

        assertEquals(400, tooLong.statusCode(), "the pattern is length-limited");

        String safe = RuleCatalogue.PR_TITLE_DEFAULT.replace("{8,70}", "{8,60}");

        HttpResponse<Buffer> ok = save(RuleCatalogue.PR_STANDARD, new JsonObject().put("prTitle", safe));

        assertEquals(200, ok.statusCode(), ok.bodyAsString());
        assertNull(SafeRegex.problem(RuleCatalogue.PR_TITLE_DEFAULT), "the default standard is safe");
    }

    @Test
    @Order(7)
    void aStaleVersionIsRefused() throws Exception
    {
        int current = version();

        HttpResponse<Buffer> res = api.put("/api/v1/rules/groups/" + RuleCatalogue.UTILIZATION, admin,
                new JsonObject().put("values", new JsonObject().put("under", 55)).put("version", current - 1));

        assertEquals(409, res.statusCode());
        assertEquals(current, TestApi.error(res).getInteger("currentVersion"));
    }

    @Test
    @Order(8)
    void historyShowsActorTimeAndChangedValues() throws Exception
    {
        save(RuleCatalogue.UTILIZATION, new JsonObject().put("under", 55));
        save(RuleCatalogue.UTILIZATION, new JsonObject().put("under", 50).put("over", 130));

        JsonArray items = api.get("/api/v1/rules/groups/" + RuleCatalogue.UTILIZATION + "/history", admin)
                .bodyAsJsonObject().getJsonArray("items");

        assertEquals(2, items.size());

        JsonObject newest = items.getJsonObject(0);

        assertEquals("Anita Desai", newest.getJsonObject("actor").getString("name"));
        assertNotNull(newest.getString("at"));
        assertEquals(2, newest.getJsonArray("changes").size());
        assertEquals(new JsonObject().put("path", "under").put("label", "Under below").put("before", 55).put("after", 50),
                newest.getJsonArray("changes").getJsonObject(0));
    }

    @Test
    @Order(9)
    void aRuleEditDoesNotAlterAStoredSnapshot() throws Exception
    {
        int ruleVersion = version();
        JsonObject result = new JsonObject().put("stale", 4);

        await(SnapshotStore.freeze(api.sql(), SnapshotStore.PERIOD, "s:S99", ruleVersion, new JsonObject(), result, null));

        assertEquals(200, save(RuleCatalogue.STALENESS, new JsonObject().put("staleDays", 4)).statusCode());

        JsonObject snapshot = await(SnapshotStore.find(api.sql(), SnapshotStore.PERIOD, "s:S99"));

        assertEquals(ruleVersion, snapshot.getInteger("ruleVersion"));
        assertEquals(result, snapshot.getJsonObject("result"));
        assertTrue(version() > ruleVersion);
    }

    @Test
    @Order(10)
    void appraisalStandardsAreValidatedToo() throws Exception
    {
        HttpResponse<Buffer> total = save(RuleCatalogue.APPRAISAL, new JsonObject().put("appraisal.w.perf", 60));

        assertEquals(422, total.statusCode());
        assertEquals("weights_total", TestApi.error(total).getString("code"));

        HttpResponse<Buffer> bands = save(RuleCatalogue.APPRAISAL, new JsonObject().put("appraisal.bands.0.hi", 25));

        assertEquals(422, bands.statusCode(), "a band above the cap is refused");

        HttpResponse<Buffer> ok = save(RuleCatalogue.APPRAISAL, new JsonObject().put("appraisal.budget", 10.5));

        assertEquals(200, ok.statusCode(), ok.bodyAsString());
    }

    @Test
    @Order(11)
    void resetRestoresEveryDefaultAsANewAuditedVersion() throws Exception
    {
        JsonObject preview = api.get("/api/v1/rules/reset", admin).bodyAsJsonObject();

        int changes = preview.getInteger("changes");

        assertTrue(changes >= 5, "the confirmation names how many values change: " + preview.encode());

        int before = preview.getInteger("version");

        HttpResponse<Buffer> res = api.post("/api/v1/rules/reset", admin, new JsonObject().put("version", before));

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertEquals(before + 1, res.bodyAsJsonObject().getInteger("version"));
        assertEquals(changes, res.bodyAsJsonObject().getInteger("changes"));

        assertEquals(RuleCatalogue.defaults(), rulesInForce(), "every rule is back at its default");

        JsonObject audit = api.queryOne("SELECT summary FROM audit_log WHERE action = 'rules.reset' ORDER BY occurred_at DESC LIMIT 1")
                .getJsonObject("summary");

        assertEquals(4, audit.getJsonObject("changes").getJsonObject("staleDays").getInteger("before"), "the old value is kept");
        assertEquals(0, api.get("/api/v1/rules/reset", admin).bodyAsJsonObject().getInteger("changes"));
    }
}
