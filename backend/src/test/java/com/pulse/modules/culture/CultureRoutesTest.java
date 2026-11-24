package com.pulse.modules.culture;

import com.pulse.calc.core.CultureParameters;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Culture values through the real server (task 19.1; spec culture/culture-values): the person page and
 * its reproduction, Moral &amp; Ethics, the period selector and a closed quarter, the detail view with
 * definitions and source labels, Discipline under four weeks, the override (own score refused, audited,
 * revert) and kudos in the evidence log.
 */
class CultureRoutesTest
{
    private static final int PORT = 18_373;

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress",
                "visits", "reviews", "training", "attendance", "culture");
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

    private static String personOf(String email) throws Exception
    {
        return api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = '" + email + "'").getString("id");
    }

    private static JsonObject value(JsonObject page, String name)
    {
        for (Object o : page.getJsonArray("values"))
        {
            if (name.equals(((JsonObject) o).getString("value")))
            {
                return (JsonObject) o;
            }
        }

        return null;
    }

    @Test
    void thePeriodSelectorDefaultsToTheLiveQuarter() throws Exception
    {
        JsonObject periods = ok(api.get("/api/v1/culture/periods", as(RoleMatrix.EMPLOYEE)));

        assertEquals("q:2026-Q3", periods.getString("default"));

        assertEquals("Q2 2026", periods.getJsonArray("items").getJsonObject(1).getString("label"));
    }

    @Test
    void theRulesTableListsAllValuesWithMoralAndEthicsAs360Only() throws Exception
    {
        JsonArray values = ok(api.get("/api/v1/culture/rules", as(RoleMatrix.EMPLOYEE))).getJsonArray("values");

        assertEquals(11, values.size());

        JsonObject moral = values.getJsonObject(CultureParameters.VALUES.indexOf(CultureParameters.MORAL_AND_ETHICS));

        assertEquals("360° only", moral.getString("note"));

        assertEquals(0, moral.getInteger("dataShare"));

        assertTrue(moral.getJsonArray("parameters").isEmpty());

        JsonObject decision = values.getJsonObject(CultureParameters.VALUES.indexOf("Decision Making"));

        assertEquals(5, decision.getJsonObject("definition").getJsonArray("anchors").size());

        assertFalse(decision.getJsonArray("parameters").isEmpty());

        double weights = 0;

        for (Object o : values)
        {
            weights += ((JsonObject) o).getDouble("weight");
        }

        assertEquals(100, weights, 1e-9);
    }

    @Test
    void thePersonPageReproducesEveryScoreAndThePillar() throws Exception
    {
        String priya = personOf(RoleMatrix.EMPLOYEE);

        JsonObject page = ok(api.get("/api/v1/culture/people/" + priya, as(RoleMatrix.EMPLOYEE)));

        assertTrue(page.getBoolean("scored"));

        assertFalse(page.getBoolean("canOverride"));

        JsonArray weights = ok(api.get("/api/v1/culture/rules", as(RoleMatrix.EMPLOYEE))).getJsonArray("values");

        double sum = 0;

        double wsum = 0;

        for (int i = 0; i < 11; i++)
        {
            JsonObject v = page.getJsonArray("values").getJsonObject(i);

            JsonObject p = v.getJsonObject("parts");

            double reproduced = CultureParameters.DISCIPLINE.equals(v.getString("value")) ? p.getDouble("discipline")
                    : Math.min(5, p.getDouble("rating360") * (1 - p.getDouble("dataShare") / 100)
                            + (p.getValue("dataScore") == null ? 0 : p.getDouble("dataScore")) * p.getDouble("dataShare") / 100 + p.getDouble("bonus"));

            assertEquals(v.getDouble("score"), Math.round(reproduced * 100) / 100.0, 1e-9, v.encode());

            double w = weights.getJsonObject(i).getDouble("weight");

            sum += v.getDouble("score") * w;

            wsum += w;
        }

        assertEquals(sum / wsum, page.getDouble("pillar"), 0.01);

        JsonObject moral = value(page, CultureParameters.MORAL_AND_ETHICS).getJsonObject("parts");

        assertEquals(0, moral.getInteger("dataShare"));

        assertNull(moral.getValue("dataScore"));

        assertEquals(11, page.getJsonArray("teamAverage").size());
    }

    @Test
    void aPeerCannotOpenSomeoneElsesCulture() throws Exception
    {
        HttpResponse<Buffer> res = api.get("/api/v1/culture/people/" + personOf(RoleMatrix.EMPLOYEE), as(RoleMatrix.PEER));

        assertTrue(res.statusCode() == 403 || res.statusCode() == 404, res.bodyAsString());
    }

    @Test
    void aClosedQuarterIsReadFromItsSnapshot() throws Exception
    {
        String priya = personOf(RoleMatrix.EMPLOYEE);

        JsonObject q2 = ok(api.get("/api/v1/culture/people/" + priya + "?period=q:2026-Q2", as(RoleMatrix.MANAGER)));

        assertEquals("q:2026-Q2", q2.getString("period"));

        // The seeded Q2 snapshot holds the index only, so Q2 shows no culture scores rather than live Q3 ones.
        assertFalse(q2.getBoolean("scored"));

        HttpResponse<Buffer> bad = api.get("/api/v1/culture/people/" + priya + "?period=s:S1", as(RoleMatrix.MANAGER));

        assertEquals(400, bad.statusCode(), bad.bodyAsString());
    }

    @Test
    void theDetailShowsTheFormulaPartsAnchorsAndSourceLabels() throws Exception
    {
        String priya = personOf(RoleMatrix.EMPLOYEE);

        JsonObject res = ok(api.get("/api/v1/culture/people/" + priya + "/values/Decision%20Making", as(RoleMatrix.EMPLOYEE)));

        JsonObject detail = res.getJsonObject("detail");

        assertEquals(5, res.getJsonObject("definition").getJsonArray("anchors").size());

        assertEquals((int) Math.round(detail.getDouble("score")), res.getInteger("currentBand"));

        assertFalse(detail.getJsonArray("raterGroups").isEmpty());

        JsonObject documented = null;

        for (Object o : detail.getJsonArray("parameters"))
        {
            if ("decisions_documented".equals(((JsonObject) o).getString("key")))
            {
                documented = (JsonObject) o;
            }
        }

        assertNotNull(documented);

        assertEquals("Confluence · Q3", documented.getString("sourceLabel"));

        assertEquals(404, api.get("/api/v1/culture/people/" + priya + "/values/Punctuality", as(RoleMatrix.EMPLOYEE)).statusCode());
    }

    @Test
    void disciplineStaysAtThreeUnderFourWeeksOfData() throws Exception
    {
        String id = api.queryOne("INSERT INTO people (name, title, joined_on, source, external_id) "
                + "VALUES ('Two Weeks', 'Engineer', '2026-09-14', 'test', 'culture-two-weeks') RETURNING id").getString("id");

        api.queryOne("INSERT INTO discipline_inputs (person_id, log_same_pct, dod_pct, review_pct, standup_pct) "
                + "VALUES ('" + id + "', 100, 100, 100, 100) RETURNING person_id");

        api.queryOne("INSERT INTO daily_updates (person_id, day, updated) SELECT '" + id + "', d::date, true "
                + "FROM generate_series('2026-09-14'::date, '2026-09-25'::date, '1 day') d WHERE extract(isodow FROM d) < 6 RETURNING 1");

        JsonObject d = ok(api.get("/api/v1/culture/people/" + id + "/discipline", as(RoleMatrix.ADMIN)));

        assertEquals(3.0, d.getDouble("score"), 1e-9, d.encode());

        assertEquals(2, d.getJsonObject("metrics").getInteger("weeks"));

        assertFalse(d.getJsonObject("metrics").getBoolean("enoughData"));
    }

    @Test
    void theOverrideIsAuditedRefusedOnYourOwnAndReverts() throws Exception
    {
        String priya = personOf(RoleMatrix.EMPLOYEE);

        // The seeded Manager has no profile of their own; give them one so there is an own score to refuse.
        String own = api.queryOne("INSERT INTO people (user_id, name, title, source, external_id) SELECT id, name, 'Engineering Manager', 'test', "
                + "'culture-own' FROM users WHERE email = '" + RoleMatrix.MANAGER + "' RETURNING id").getString("id");

        TestApi.Session rahul = api.signIn(RoleMatrix.MANAGER, TestApi.DEMO_PASSWORD);

        assertEquals(403, api.put("/api/v1/culture/people/" + priya + "/discipline-override", as(RoleMatrix.EMPLOYEE),
                new JsonObject().put("score", 4).put("reason", "Covering the on-call rota")).statusCode());

        HttpResponse<Buffer> self = api.put("/api/v1/culture/people/" + own + "/discipline-override", rahul,
                new JsonObject().put("score", 5).put("reason", "Covering the on-call rota"));

        assertEquals(422, self.statusCode(), self.bodyAsString());

        assertEquals("self_action_refused", TestApi.error(self).getString("code"));

        assertFalse(ok(api.get("/api/v1/culture/people/" + own + "/discipline", rahul)).getBoolean("canOverride"));

        HttpResponse<Buffer> shortReason = api.put("/api/v1/culture/people/" + priya + "/discipline-override", rahul,
                new JsonObject().put("score", 4).put("reason", "ok"));

        assertEquals(422, shortReason.statusCode(), shortReason.bodyAsString());

        long audited = api.count("SELECT count(*) FROM audit_log WHERE action = 'discipline.override' AND outcome <> 'refused'");

        JsonObject set = ok(api.put("/api/v1/culture/people/" + priya + "/discipline-override", rahul,
                new JsonObject().put("score", 4.25).put("reason", "Ran the release rota for the whole sprint")));

        assertEquals("override", set.getString("source"));

        assertEquals(4.25, set.getDouble("score"), 1e-9);

        assertEquals("Ran the release rota for the whole sprint", set.getJsonObject("override").getString("reason"));

        assertEquals(audited + 1, api.count("SELECT count(*) FROM audit_log WHERE action = 'discipline.override' AND outcome <> 'refused'"));

        JsonObject page = ok(api.get("/api/v1/culture/people/" + priya, as(RoleMatrix.EMPLOYEE)));

        assertEquals(4.25, value(page, CultureParameters.DISCIPLINE).getDouble("score"), 1e-9);

        assertEquals("Ran the release rota for the whole sprint", page.getJsonObject("discipline").getJsonObject("override").getString("reason"));

        JsonObject back = ok(api.delete("/api/v1/culture/people/" + priya + "/discipline-override", rahul));

        assertEquals("auto", back.getString("source"));

        assertNull(back.getValue("override"));

        assertEquals(1, api.count("SELECT count(*) FROM audit_log WHERE action = 'discipline.override.cleared' AND target_id = '" + priya + "'"));

        assertEquals(409, api.delete("/api/v1/culture/people/" + priya + "/discipline-override", rahul).statusCode());

        api.count("WITH x AS (UPDATE people SET user_id = NULL WHERE id = '" + own + "' RETURNING 1) SELECT count(*) FROM x");
    }

    @Test
    void kudosAppearInTheEvidenceLog() throws Exception
    {
        String priya = personOf(RoleMatrix.EMPLOYEE);

        HttpResponse<Buffer> give = api.post("/api/v1/reviews/kudos", as(RoleMatrix.PEER),
                new JsonObject().put("personId", priya).put("value", "Collaboration").put("message", "Paired on the sync bug all afternoon"));

        assertTrue(give.statusCode() == 200 || give.statusCode() == 201, give.bodyAsString());

        // Kudos carry the database time; place this one inside the pinned quarter.
        api.count("WITH x AS (UPDATE kudos SET given_at = '2026-09-27T10:00:00+05:30' "
                + "WHERE message = 'Paired on the sync bug all afternoon' RETURNING 1) SELECT count(*) FROM x");

        JsonArray evidence = ok(api.get("/api/v1/culture/people/" + priya + "/values/Collaboration", as(RoleMatrix.EMPLOYEE)))
                .getJsonArray("evidence");

        assertTrue(evidence.stream().anyMatch(o -> "kudos".equals(((JsonObject) o).getString("kind"))
                && "Paired on the sync bug all afternoon".equals(((JsonObject) o).getString("text"))), evidence.encode());

        JsonArray other = ok(api.get("/api/v1/culture/people/" + priya + "/values/Resilience", as(RoleMatrix.EMPLOYEE))).getJsonArray("evidence");

        assertTrue(other.stream().noneMatch(o -> "Paired on the sync bug all afternoon".equals(((JsonObject) o).getString("text"))));
    }
}
