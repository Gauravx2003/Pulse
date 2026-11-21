package com.pulse.modules.training;

import com.pulse.testsupport.Oracle;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 21.1 part C: the training rating of every seeded assignment and every trainee's overall rating,
 * read through the API (people.trainingRating / people.trainingOverall over the training tables and the
 * checkpoint reviews), against the oracle {@code trRating} and {@code trPerson} (app.js ~1652–1656).
 * The server's "today" is pinned to the prototype's 28 Sep 2026.
 */
class TrainingRatingsTest
{
    private static final int PORT = 18_352;

    private static final String A = "TASSIGN.filter(function(x){return x.id===id})[0]";

    private static final String RATING = "(function(id){var a=" + A + ",r=trRating(a);return {parts:r.parts.map(function(p){"
            + "return {part:p[0],value:p[1],weight:p[2],detail:p[3]}}),score:r.score,'final':r['final'],"
            + "state:r.score===null?'Not rated':r['final']?'Final':'Provisional',band:{tone:r.band[0],label:r.band[1]}}})";

    private static final String PERSON = "(function(pid){var t=trPerson(pid);return {score:t.score,'final':t['final'],n:t.n}})";

    private static TestApi api;

    private static Oracle oracle;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"));

        for (String script : new String[] {"modules/reviews/migrations/V340__review_360.sql", "modules/reviews/migrations/V345__review_cycle_kinds.sql",
                "modules/training/migrations/V350__training.sql", "modules/reviews/seed/S340__demo_review_360.sql",
                "modules/reviews/seed/S341__demo_review_360_unstarted.sql", "modules/training/seed/S350__demo_training.sql",
                "modules/training/seed/S355__demo_training_checkpoints.sql"})
        {
            try (InputStream in = TrainingRatingsTest.class.getClassLoader().getResourceAsStream(script))
            {
                api.exec(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        oracle = Oracle.start();
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (oracle != null)
        {
            oracle.close();
        }

        if (api != null)
        {
            api.close();
        }
    }

    @Test
    void everySeededAssignmentRatesAsThePrototype() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        JsonArray seeded = new JsonArray();

        for (Object row : api.sql().queryList("SELECT id, external_id FROM training_assignments WHERE source = 'seed'")
                .toCompletionStage().toCompletableFuture().get())
        {
            seeded.add(row);
        }

        assertEquals(7, seeded.size());

        for (Object item : seeded)
        {
            JsonObject row = (JsonObject) item;

            HttpResponse<Buffer> res = api.get("/api/v1/training/assignments/" + row.getString("id"), rahul);

            assertEquals(200, res.statusCode(), res.bodyAsString());

            JsonObject rating = res.bodyAsJsonObject().getJsonObject("rating");

            // CalcService's ResultGuard sends "not applicable" instead of null; the prototype has null.
            JsonArray parts = new JsonArray();

            for (Object part : rating.getJsonArray("parts"))
            {
                JsonObject p = ((JsonObject) part).copy();

                if (p.getValue("value") instanceof JsonObject na && na.getBoolean("notApplicable", false))
                {
                    p.putNull("value");
                }

                parts.add(p);
            }

            oracle.compare(RATING, Oracle.args(row.getString("external_id")), 4, args -> new JsonObject()
                    .put("parts", parts)
                    .put("score", rating.getValue("score"))
                    .put("final", rating.getBoolean("final"))
                    .put("state", rating.getString("state"))
                    .put("band", rating.getJsonObject("band")));
        }
    }

    @Test
    void everyTraineesOverallRatingMatchesThePrototype() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        JsonArray trainees = api.sql().queryList("""
                SELECT DISTINCT p.id, p.external_id FROM training_assignments a JOIN people p ON p.id = a.person_id
                WHERE a.source = 'seed'
                """).toCompletionStage().toCompletableFuture().get();

        assertTrue(trainees.size() >= 4);

        for (Object item : trainees)
        {
            JsonObject trainee = (JsonObject) item;

            HttpResponse<Buffer> res = api.get("/api/v1/training/rating?personId=" + trainee.getString("id"), rahul);

            assertEquals(200, res.statusCode(), res.bodyAsString());

            JsonObject overall = res.bodyAsJsonObject();

            oracle.compare(PERSON, Oracle.args(trainee.getString("external_id")), 4, args -> new JsonObject()
                    .put("score", overall.getValue("score"))
                    .put("final", overall.getBoolean("final"))
                    .put("n", overall.getInteger("n")));
        }
    }
}
