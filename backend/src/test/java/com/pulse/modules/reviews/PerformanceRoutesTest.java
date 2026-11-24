package com.pulse.modules.reviews;

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
 * Performance review through the real server (task 20.1; spec culture/reviews-performance): the pillar
 * breakdown and trend, the calibration table for reviews.calibrate holders only, and submission for
 * calibration (audited, never yourself, once per quarter).
 */
class PerformanceRoutesTest
{
    private static final int PORT = 18_374;

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

    private static String proposed(double index)
    {
        return index >= 4.2 ? "Exceeds" : index >= 3.5 ? "Meets" : "Developing";
    }

    @Test
    void eachPillarShowsWeightScoreAndInputsAndTheIndexFollows() throws Exception
    {
        JsonObject review = ok(api.get("/api/v1/reviews/performance/people/" + personOf(RoleMatrix.EMPLOYEE), as(RoleMatrix.EMPLOYEE)));

        JsonArray pillars = review.getJsonArray("pillars");

        assertEquals(5, pillars.size());

        double sum = 0;

        double weights = 0;

        for (Object o : pillars)
        {
            JsonObject p = (JsonObject) o;

            assertNotNull(p.getValue("score"), p.encode());

            assertFalse(p.getString("inputs").isBlank());

            sum += p.getDouble("score") * p.getDouble("weight");

            weights += p.getDouble("weight");
        }

        assertEquals(100, weights, 1e-9);

        assertEquals(35, pillars.getJsonObject(0).getInteger("weight"));

        double index = review.getDouble("index");

        assertEquals(sum / weights, index, 0.01);

        assertEquals(proposed(index), review.getString("proposed"));

        JsonArray trend = review.getJsonArray("trend");

        assertEquals(4, trend.size());

        assertEquals("Q4 2025", trend.getJsonObject(0).getString("label"));

        assertEquals(3.4, trend.getJsonObject(0).getDouble("index"), 1e-9);

        assertEquals(index, trend.getJsonObject(3).getDouble("index"), 1e-9);

        assertFalse(review.getBoolean("canSubmit"));

        assertEquals("Priya Shah", review.getJsonObject("person").getString("name"));
    }

    @Test
    void employeesGetNoCalibrationTableAndCannotSeeOthers() throws Exception
    {
        assertEquals(403, api.get("/api/v1/reviews/performance/calibration", as(RoleMatrix.EMPLOYEE)).statusCode());

        int other = api.get("/api/v1/reviews/performance/people/" + personOf(RoleMatrix.PEER), as(RoleMatrix.EMPLOYEE)).statusCode();

        assertTrue(other == 403 || other == 404, "status " + other);

        assertEquals(403, api.post("/api/v1/reviews/performance/people/" + personOf(RoleMatrix.PEER) + "/submit", as(RoleMatrix.EMPLOYEE),
                new JsonObject()).statusCode());
    }

    @Test
    void theCalibrationTableProposesARatingFromTheIndex() throws Exception
    {
        JsonObject table = ok(api.get("/api/v1/reviews/performance/calibration", as(RoleMatrix.MANAGER)));

        JsonArray people = table.getJsonArray("people");

        assertTrue(people.size() > 1, people.encode());

        for (Object o : people)
        {
            JsonObject row = (JsonObject) o;

            if (row.getValue("index") != null)
            {
                assertEquals(proposed(row.getDouble("index")), row.getString("proposed"), row.encode());

                assertEquals(5, row.getJsonObject("pillars").size());
            }
        }
    }

    @Test
    void aManagerSubmitsOnceIsAuditedAndCannotCalibrateThemselves() throws Exception
    {
        String own = api.queryOne("INSERT INTO people (user_id, name, title, source, external_id) SELECT id, name, 'Engineering Manager', 'test', "
                + "'perf-own' FROM users WHERE email = '" + RoleMatrix.MANAGER + "' RETURNING id").getString("id");

        try
        {
            TestApi.Session rahul = api.signIn(RoleMatrix.MANAGER, TestApi.DEMO_PASSWORD);

            HttpResponse<Buffer> self = api.post("/api/v1/reviews/performance/people/" + own + "/submit", rahul, new JsonObject());

            assertEquals(422, self.statusCode(), self.bodyAsString());

            assertEquals("self_action_refused", TestApi.error(self).getString("code"));

            JsonObject mine = ok(api.get("/api/v1/reviews/performance/people/" + own, rahul));

            assertFalse(mine.getBoolean("canSubmit"));

            String priya = personOf(RoleMatrix.EMPLOYEE);

            JsonObject before = ok(api.get("/api/v1/reviews/performance/people/" + priya, rahul));

            assertTrue(before.getBoolean("canSubmit"));

            assertNull(before.getValue("submission"));

            long audited = api.count("SELECT count(*) FROM audit_log WHERE action = 'reviews.calibrate' AND outcome <> 'refused'");

            JsonObject after = ok(api.post("/api/v1/reviews/performance/people/" + priya + "/submit", rahul, new JsonObject()));

            JsonObject submission = after.getJsonObject("submission");

            assertNotNull(submission.getValue("submittedAt"));

            assertNotNull(submission.getString("submittedBy"));

            assertEquals(before.getDouble("index"), submission.getDouble("index"), 1e-9);

            assertFalse(after.getBoolean("canSubmit"));

            assertEquals(audited + 1, api.count("SELECT count(*) FROM audit_log WHERE action = 'reviews.calibrate' AND outcome <> 'refused'"));

            assertEquals(409, api.post("/api/v1/reviews/performance/people/" + priya + "/submit", rahul, new JsonObject()).statusCode());

            JsonObject team = ok(api.post("/api/v1/reviews/performance/submit", rahul, new JsonObject()));

            assertTrue(team.getInteger("submitted") >= 1, team.encode());

            for (Object o : ok(api.get("/api/v1/reviews/performance/calibration", rahul)).getJsonArray("people"))
            {
                JsonObject row = (JsonObject) o;

                assertFalse(own.equals(row.getString("id")), "the manager's own row is not in their calibration table");

                if (row.getValue("index") != null)
                {
                    assertNotNull(row.getValue("submission"), row.encode());
                }
            }

            assertEquals(0, api.count("SELECT count(*) FROM performance_reviews WHERE person_id = '" + own + "'"));
        }
        finally
        {
            api.count("WITH x AS (UPDATE people SET user_id = NULL WHERE id = '" + own + "' RETURNING 1) SELECT count(*) FROM x");
        }
    }
}
