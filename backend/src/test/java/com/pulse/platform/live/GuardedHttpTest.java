package com.pulse.platform.live;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Task 24.3: the guarded outbound client, against two local servers. No request leaves the machine. */
class GuardedHttpTest
{
    private static Vertx vertx;

    private static HttpServer tool;

    private static HttpServer other;

    private static final AtomicInteger toolHits = new AtomicInteger();

    private static final AtomicInteger otherHits = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception
    {
        vertx = Vertx.vertx();

        tool = await(vertx.createHttpServer().requestHandler(req ->
        {
            toolHits.incrementAndGet();

            switch (req.path())
            {
                case "/ok" -> req.response().putHeader("Content-Type", "application/json")
                        .putHeader("X-OAuth-Scopes", "repo, read:org")
                        .end(new JsonObject().put("name", "demo").encode());

                case "/list" -> req.response().end(new JsonArray().add(1).add(2).encode());

                case "/unauthorized" -> req.response().setStatusCode(401).setStatusMessage("Unauthorized").end();

                case "/redirect" -> req.response().setStatusCode(302).putHeader("Location", "http://10.0.0.5/").end();

                case "/stall" ->
                {
                    // never answers
                }

                case "/big" -> req.response().end("x".repeat(4_096));

                default -> req.response().setStatusCode(404).end();
            }
        }).listen(0, "127.0.0.1"));

        other = await(vertx.createHttpServer().requestHandler(req ->
        {
            otherHits.incrementAndGet();

            req.response().end("{}");
        }).listen(0, "127.0.0.1"));
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (vertx != null)
        {
            await(vertx.close());
        }
    }

    @BeforeEach
    void reset()
    {
        toolHits.set(0);
        otherHits.set(0);
    }

    private static <T> T await(Future<T> future) throws Exception
    {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static LiveHttpException failure(Future<?> future) throws Exception
    {
        try
        {
            await(future);
        }
        catch (ExecutionException e)
        {
            return assertInstanceOf(LiveHttpException.class, e.getCause());
        }

        return fail("expected a refusal");
    }

    private static String base()
    {
        return "http://127.0.0.1:" + tool.actualPort();
    }

    private static JsonArray toolOrigin(String prefix)
    {
        return new JsonArray().add(new JsonObject().put("scheme", "http").put("host", "127.0.0.1")
                .put("port", tool.actualPort()).put("pathPrefix", prefix));
    }

    private static GuardedHttp client(JsonArray origins, JsonObject options, Map<String, String> dns)
    {
        return new GuardedHttp(vertx, "test", origins, options, host ->
        {
            String ip = dns.get(host);

            if (ip == null)
            {
                throw new UnknownHostException(host);
            }

            return new InetAddress[] {InetAddress.getByName(ip)};
        });
    }

    private static GuardedHttp client()
    {
        return client(toolOrigin(""), new JsonObject(), Map.of());
    }

    // ------------------------------------------------------------------ allowed

    @Test
    void getFromTheDeclaredOrigin() throws Exception
    {
        try (GuardedHttp http = client())
        {
            JsonObject res = await(http.get(base() + "/ok?jql=project%20in%20(A)", new JsonObject()
                    .put("Authorization", "Bearer t")));

            assertEquals(200, res.getInteger("status"));
            assertEquals("demo", res.getJsonObject("body").getString("name"));
            assertEquals("repo, read:org", res.getJsonObject("headers").getString("x-oauth-scopes"));

            assertInstanceOf(JsonArray.class, await(http.get(base() + "/list", null)).getValue("body"));
            assertEquals(2, toolHits.get());
        }
    }

    @Test
    void configuredLanOriginIsAllowed() throws Exception
    {
        JsonArray lan = new JsonArray().add(BaseUrls.origin("http://172.16.10.6:8080"));

        try (GuardedHttp http = client(lan, new JsonObject(), Map.of()))
        {
            // checks only: nothing is sent to the LAN host
            JsonObject target = await(http.admit("GET", "http://172.16.10.6:8080/rest/api/2/myself"));

            assertEquals("172.16.10.6", target.getString("address"));
            assertEquals(8080, target.getInteger("port"));
        }
    }

    // ------------------------------------------------------------------ refused before sending

    @Test
    void differentOriginIsRefusedBeforeSending() throws Exception
    {
        try (GuardedHttp http = client())
        {
            for (String url : new String[] {
                    "http://localhost:" + tool.actualPort() + "/ok",
                    "https://127.0.0.1:" + tool.actualPort() + "/ok",
                    "http://example.com/ok"})
            {
                LiveHttpException e = failure(http.get(url, null));

                assertEquals(LiveHttpException.ORIGIN, e.code(), url);
                assertFalse(e.sent());
            }

            assertEquals(0, toolHits.get());
        }
    }

    @Test
    void differentPortOnTheSameHostIsRefused() throws Exception
    {
        try (GuardedHttp http = client())
        {
            LiveHttpException e = failure(http.get("http://127.0.0.1:" + other.actualPort() + "/ok", null));

            assertEquals(LiveHttpException.ORIGIN, e.code());
            assertEquals(0, otherHits.get(), "nothing reached the other port");
        }
    }

    @Test
    void postIsRefused() throws Exception
    {
        try (GuardedHttp http = client())
        {
            for (String method : new String[] {"POST", "PUT", "DELETE", "PATCH", "get"})
            {
                assertEquals(LiveHttpException.METHOD, failure(http.send(method, base() + "/ok", null)).code());
            }

            assertEquals(0, toolHits.get());
        }
    }

    @Test
    void hostResolvingToMetadataIsRefused() throws Exception
    {
        JsonArray origins = new JsonArray()
                .add(new JsonObject().put("scheme", "http").put("host", "metadata.test").put("port", 80))
                .add(new JsonObject().put("scheme", "http").put("host", "rebind.test").put("port", 80))
                .add(new JsonObject().put("scheme", "http").put("host", "jira.lan").put("port", 80));

        Map<String, String> dns = Map.of("metadata.test", "169.254.169.254", "rebind.test", "127.0.0.1",
                "jira.lan", "10.1.2.3");

        try (GuardedHttp http = client(origins, new JsonObject(), dns))
        {
            LiveHttpException metadata = failure(http.get("http://metadata.test/latest/meta-data/", null));

            assertEquals(LiveHttpException.ADDRESS, metadata.code());
            assertFalse(metadata.sent());

            assertEquals(LiveHttpException.ADDRESS, failure(http.get("http://rebind.test/x", null)).code(),
                    "a name resolving to loopback is refused");

            assertEquals("10.1.2.3", await(http.admit("GET", "http://jira.lan/rest")).getString("address"));
        }

        JsonArray literal = new JsonArray().add(new JsonObject().put("scheme", "http").put("host", "169.254.169.254")
                .put("port", 80));

        try (GuardedHttp http = client(literal, new JsonObject(), Map.of()))
        {
            assertEquals(LiveHttpException.ADDRESS, failure(http.get("http://169.254.169.254/", null)).code(),
                    "metadata is refused even when declared literally");
        }
    }

    @Test
    void pathStaysUnderTheContextPath() throws Exception
    {
        try (GuardedHttp http = client(toolOrigin("/jira"), new JsonObject(), Map.of()))
        {
            await(http.admit("GET", base() + "/jira/rest/api/2/myself"));
            await(http.admit("GET", base() + "/jira"));

            for (String path : new String[] {"/other", "/jiraX/rest", "/jira/../admin", "/jira/./x",
                    "/jira/%2e%2e/admin", "/jira%2f..%2fadmin", "/jira//x"})
            {
                assertEquals(LiveHttpException.PATH, failure(http.get(base() + path, null)).code(), path);
            }

            assertEquals(LiveHttpException.PATH, failure(http.get("http://u:p@127.0.0.1:" + tool.actualPort()
                    + "/jira/x", null)).code(), "user info");

            assertEquals(0, toolHits.get());
        }
    }

    // ------------------------------------------------------------------ after sending

    @Test
    void redirectIsRefusedAndNotFollowed() throws Exception
    {
        try (GuardedHttp http = client())
        {
            LiveHttpException e = failure(http.get(base() + "/redirect", null));

            assertEquals(LiveHttpException.REDIRECT, e.code());
            assertEquals(302, e.status());
            assertEquals(1, toolHits.get());
        }
    }

    @Test
    void stallHitsTheReadTimeout() throws Exception
    {
        try (GuardedHttp http = client(toolOrigin(""), new JsonObject().put("readTimeoutMs", 300), Map.of()))
        {
            long started = System.nanoTime();

            assertEquals(LiveHttpException.TIMEOUT, failure(http.get(base() + "/stall", null)).code());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10));
        }
    }

    @Test
    void oversizedAnswerIsRefused() throws Exception
    {
        try (GuardedHttp http = client(toolOrigin(""), new JsonObject().put("maxBytes", 1_024), Map.of()))
        {
            assertEquals(LiveHttpException.TOO_LARGE, failure(http.get(base() + "/big", null)).code());
        }
    }

    @Test
    void limitsCannotBeRaised()
    {
        // options only lower the standard limits; a connector can't ask for more
        try (GuardedHttp http = client(toolOrigin(""), new JsonObject().put("maxBytes", Long.MAX_VALUE)
                .put("readTimeoutMs", 999_999), Map.of()))
        {
            assertEquals(1, http.origins().size());
        }
    }

    @Test
    void getOkFailsWithTheStatus() throws Exception
    {
        try (GuardedHttp http = client())
        {
            assertEquals(401, await(http.get(base() + "/unauthorized", null)).getInteger("status"));

            LiveHttpException e = failure(http.getOk(base() + "/unauthorized", null));

            assertEquals("401", e.code());
            assertEquals("401 Unauthorized", e.display());
            assertNull(await(http.get(base() + "/missing", null)).getValue("body"));
        }
    }
}
