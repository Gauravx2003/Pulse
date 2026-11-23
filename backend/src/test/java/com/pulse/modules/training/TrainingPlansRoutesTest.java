package com.pulse.modules.training;

import com.pulse.platform.auth.SessionHandler;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec growth/training-mentoring "Plan library" (task 21.1 part A), with the demo seed. */
class TrainingPlansRoutesTest
{
    private static final int PORT = 18_350;

    private static TestApi api;

    private static WebClient client;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);

        for (String script : new String[] {"modules/training/migrations/V350__training.sql", "modules/training/seed/S350__demo_training.sql"})
        {
            try (InputStream in = TrainingPlansRoutesTest.class.getClassLoader().getResourceAsStream(script))
            {
                api.exec(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

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

    private static String seededPlan(String externalId) throws Exception
    {
        return api.queryOne("SELECT id FROM training_plans WHERE external_id = '" + externalId + "'").getString("id");
    }

    private static JsonObject newPlan(String name)
    {
        return new JsonObject()
                .put("name", name)
                .put("audience", "New SRE")
                .put("weeks", 4)
                .put("description", "Runbooks and on-call.")
                .put("values", JsonArray.of("Resilience", "Discipline"))
                .put("checkpoints", JsonArray.of("14", "28"))
                .put("cadence", "Weekly")
                .put("assessmentType", "Practical task")
                .put("passMark", 70)
                .put("modules", JsonArray.of(
                        new JsonObject().put("title", "Read the runbooks").put("type", "Reading").put("week", 1).put("hours", 4),
                        new JsonObject().put("title", "Shadow on-call").put("type", "Shadowing").put("week", 3).put("hours", 10)));
    }

    private static HttpResponse<Buffer> delete(TestApi.Session session, String path, int version) throws Exception
    {
        return await(client.delete(PORT, "localhost", path)
                .putHeader("X-Real-IP", TestApi.newIp())
                .putHeader("Cookie", session.cookie())
                .putHeader(SessionHandler.CSRF_HEADER, session.csrf())
                .putHeader("If-Match", String.valueOf(version))
                .send());
    }

    @Test
    void theLibraryIsForTrainingManagers() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/training/plans", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));

        JsonArray items = api.get("/api/v1/training/plans", RoleMatrix.session(api, RoleMatrix.MANAGER)).bodyAsJsonObject().getJsonArray("items");

        assertTrue(items.size() >= 5, items.encodePrettily());
    }

    @Test
    void createSavesVersionOneWithModuleIds() throws Exception
    {
        HttpResponse<Buffer> res = api.post("/api/v1/training/plans", RoleMatrix.session(api, RoleMatrix.MANAGER), newPlan("SRE onboarding"));

        assertEquals(201, res.statusCode(), res.bodyAsString());

        JsonObject plan = res.bodyAsJsonObject();

        assertEquals(1, plan.getInteger("currentVersion"));

        assertEquals(JsonArray.of(14, 28), plan.getJsonArray("checkpoints"));

        assertTrue(plan.getJsonArray("modules").getJsonObject(0).getString("id").startsWith("m"));

        assertEquals(70, plan.getInteger("passMark"));
    }

    @Test
    void editKeepsExistingAssignmentsOnTheirVersion() throws Exception
    {
        // Spec "Edit plan": existing assignments continue on their old version.
        String tp1 = seededPlan("tp1");

        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        JsonObject current = api.get("/api/v1/training/plans/" + tp1, rahul).bodyAsJsonObject();

        JsonObject edit = newPlan(current.getString("name")).put("weeks", 12).put("version", current.getInteger("version"));

        HttpResponse<Buffer> edited = api.put("/api/v1/training/plans/" + tp1, rahul, edit);

        assertEquals(200, edited.statusCode(), edited.bodyAsString());

        assertEquals(current.getInteger("currentVersion") + 1, edited.bodyAsJsonObject().getInteger("currentVersion"));

        assertEquals(current.getJsonArray("versions").size() + 1, edited.bodyAsJsonObject().getJsonArray("versions").size());

        assertEquals(0, api.count("SELECT count(*) FROM training_assignments WHERE plan_id = '" + tp1 + "' AND plan_version <> 1"));

        assertTrue(api.count("SELECT count(*) FROM training_assignments WHERE plan_id = '" + tp1 + "'") >= 2);

        HttpResponse<Buffer> stale = api.put("/api/v1/training/plans/" + tp1, rahul, edit);

        assertEquals(409, stale.statusCode());
    }

    @Test
    void duplicateAndDeleteLeaveAssignmentsRunning() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        String tp5 = seededPlan("tp5");

        HttpResponse<Buffer> copy = api.post("/api/v1/training/plans/" + tp5 + "/duplicate", rahul, new JsonObject());

        assertEquals(201, copy.statusCode(), copy.bodyAsString());

        assertTrue(copy.bodyAsJsonObject().getString("name").startsWith("Copy of "));

        long assignments = api.count("SELECT count(*) FROM training_assignments WHERE plan_id = '" + tp5 + "'");

        assertTrue(assignments >= 1);

        int version = api.get("/api/v1/training/plans/" + tp5, rahul).bodyAsJsonObject().getInteger("version");

        assertEquals(204, delete(rahul, "/api/v1/training/plans/" + tp5, version).statusCode());

        assertEquals(404, api.get("/api/v1/training/plans/" + tp5, rahul).statusCode());

        assertTrue(!api.get("/api/v1/training/plans", rahul).bodyAsString().contains(tp5));

        assertEquals(assignments, api.count("SELECT count(*) FROM training_assignments WHERE plan_id = '" + tp5 + "'"));
    }

    @Test
    void planContentIsChecked() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        JsonObject lateModule = newPlan("Late");

        lateModule.getJsonArray("modules").getJsonObject(1).put("week", 6);

        HttpResponse<Buffer> late = api.post("/api/v1/training/plans", rahul, lateModule);

        assertEquals(400, late.statusCode());

        assertEquals("modules", TestApi.error(late).getString("field"));

        // A 4-week plan has no day 45; the prototype's short plans use days such as 7 or 10, which are fine.
        assertEquals(400, api.post("/api/v1/training/plans", rahul, newPlan("Odd day").put("checkpoints", JsonArray.of("45"))).statusCode());

        assertEquals(201, api.post("/api/v1/training/plans", rahul, newPlan("Short").put("checkpoints", JsonArray.of("7", "10"))).statusCode());

        assertEquals(400, api.post("/api/v1/training/plans", rahul, newPlan("Odd value").put("values", JsonArray.of("Speed"))).statusCode());

        assertEquals(400, api.post("/api/v1/training/plans", rahul, newPlan("Too long").put("weeks", 60)).statusCode());
    }
}
