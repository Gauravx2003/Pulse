package com.pulse.platform.live.testkit;

import com.pulse.platform.config.RuntimeProfile;
import com.pulse.platform.live.LoopbackFlag;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 24.4: the mock server's fixture routing and faults, and the test-only loopback flag. */
class LiveMockServerTest
{
    private static Vertx vertx;

    private static LiveMockServer mock;

    private static WebClient client;

    @BeforeAll
    static void start() throws Exception
    {
        vertx = Vertx.vertx();

        mock = LiveMockServer.start(vertx, "stub").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    @AfterAll
    static void stop() throws Exception
    {
        client.close();
        mock.close();

        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @BeforeEach
    void reset()
    {
        mock.reset();
    }

    private HttpResponse<Buffer> get(String uri) throws Exception
    {
        return client.getAbs(mock.baseUrl() + uri).send().toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    void servesFixturesByPathAndQuery() throws Exception
    {
        HttpResponse<Buffer> page2 = get("/items?page=2&since=x");

        assertEquals(200, page2.statusCode());
        assertEquals("ST-3", page2.bodyAsJsonObject().getJsonArray("items").getJsonObject(0).getString("id"));

        assertEquals(404, get("/items").statusCode(), "a missing required query parameter matches no route");
        assertEquals(404, get("/nowhere").statusCode());
    }

    @Test
    void replacesTheBaseUrlPlaceholder() throws Exception
    {
        assertEquals(mock.baseUrl() + "/myself", get("/myself").bodyAsJsonObject().getString("self"));
    }

    @Test
    void recordsRequestsAndRefusesNonGet() throws Exception
    {
        int status = client.postAbs(mock.baseUrl() + "/items").putHeader("Authorization", "Bearer t")
                .send().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).statusCode();

        assertEquals(405, status);

        List<JsonObject> requests = mock.requests();

        assertEquals(1, requests.size());
        assertEquals("POST", requests.get(0).getString("method"));
        assertEquals("Bearer t", requests.get(0).getJsonObject("headers").getString("authorization"));
    }

    @Test
    void faultsReplaceTheAnswer() throws Exception
    {
        mock.faultAt(2, LiveMockServer.status(401)).faultOn("/myself", LiveMockServer.redirect("http://10.0.0.5/"));

        assertEquals(200, get("/items?page=1").statusCode());

        HttpResponse<Buffer> unauthorized = get("/items?page=1");

        assertEquals(401, unauthorized.statusCode());
        assertEquals("Bearer realm=\"stub\"", unauthorized.getHeader("WWW-Authenticate"), "recorded error answer");

        mock.reset().faultAt(1, LiveMockServer.status(500)).faultOn("/myself", LiveMockServer.redirect("http://10.0.0.5/"));

        assertEquals(500, get("/items?page=1").statusCode(), "an unrecorded status gets a generic body");

        HttpResponse<Buffer> redirect = get("/myself");

        assertEquals(302, redirect.statusCode());
        assertEquals("http://10.0.0.5/", redirect.getHeader("Location"));
    }

    @Test
    void missingFixturesFailToLoad()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LiveMockServer.load("none"));

        assertTrue(e.getMessage().contains("live-fixtures/none/routes.json"));
    }

    @Test
    void loopbackFlagIsRefusedInProduction()
    {
        JsonObject on = new JsonObject().put(LoopbackFlag.CONFIG_KEY, "true");

        RuntimeProfile development = new RuntimeProfile(RuntimeProfile.DEVELOPMENT, false);

        RuntimeProfile production = new RuntimeProfile(RuntimeProfile.PRODUCTION, false);

        assertTrue(LoopbackFlag.allowed(on, development));
        assertFalse(LoopbackFlag.allowed(on, production));
        assertFalse(LoopbackFlag.allowed(new JsonObject().put(LoopbackFlag.CONFIG_KEY, false), development));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> LoopbackFlag.guard(on, production));

        assertTrue(refused.getMessage().contains(LoopbackFlag.ENV));

        LoopbackFlag.guard(on, development);
        LoopbackFlag.guard(new JsonObject().put(LoopbackFlag.CONFIG_KEY, "off"), production);

        assertThrows(IllegalStateException.class,
                () -> LoopbackFlag.requested(new JsonObject().put(LoopbackFlag.CONFIG_KEY, "maybe")));
    }
}
