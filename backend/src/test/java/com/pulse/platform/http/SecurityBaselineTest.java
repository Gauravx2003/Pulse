package com.pulse.platform.http;

import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3.6: the security baseline on the real server: hardening headers, CORS and the Origin check,
 * schema validation with unknown-field rejection, body limits enforced while streaming, per-user and
 * per-IP rate limits, and the generic 500 with a request id.
 */
class SecurityBaselineTest
{
    private static final int PORT = 18_196;

    private static final String APP = "https://pulse.test";

    private static final int BODY_LIMIT = 2048;

    private static final int USER_LIMIT = 40;

    private static final int IP_LIMIT = 15;

    private static TestApi api;

    private static WebClient client;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject()
                .put("app.url", APP + "/")
                .put("http.bodyLimitBytes", BODY_LIMIT)
                .put("http.userPerMinute", USER_LIMIT)
                .put("http.ipPerMinute", IP_LIMIT));

        client = WebClient.create(api.vertx());
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (client != null)
        {
            client.close();
        }

        if (api != null)
        {
            api.close();
        }
    }

    // ------------------------------------------------------------------ headers

    @Test
    void hardeningHeadersOnEveryResponse() throws Exception
    {
        for (HttpResponse<Buffer> res : new HttpResponse[] {
                api.get("/health", null),
                api.get("/api/v1/me", null),
                api.get("/api/v1/me", RoleMatrix.session(api, RoleMatrix.EMPLOYEE))})
        {
            MultiMap h = res.headers();
            String csp = h.get("Content-Security-Policy");

            assertNotNull(csp);
            assertTrue(csp.contains("frame-ancestors 'none'"), csp);
            assertTrue(csp.contains("script-src 'self'"), csp);
            assertFalse(csp.contains("unsafe-inline") || csp.contains("unsafe-eval"), csp);
            assertEquals("nosniff", h.get("X-Content-Type-Options"));
            assertEquals("no-referrer", h.get("Referrer-Policy"));
            assertEquals("DENY", h.get("X-Frame-Options"));
            assertNotNull(h.get("Permissions-Policy"));
            assertTrue(h.get("Strict-Transport-Security").startsWith("max-age="));
            assertNotNull(h.get(RequestId.HEADER));
        }

        assertEquals("no-store", api.get("/api/v1/me", null).headers().get("Cache-Control"));
    }

    // ------------------------------------------------------------------ CORS and origin

    @Test
    void corsAllowsOnlyTheAppOrigin() throws Exception
    {
        HttpResponse<Buffer> ok = preflight(APP);

        assertEquals(APP, ok.headers().get("Access-Control-Allow-Origin"));
        assertEquals("true", ok.headers().get("Access-Control-Allow-Credentials"));
        assertTrue(ok.headers().get("Access-Control-Allow-Headers").contains("X-CSRF-Token"));

        HttpResponse<Buffer> evil = preflight("https://evil.example");

        assertNull(evil.headers().get("Access-Control-Allow-Origin"));
        assertEquals(403, evil.statusCode());
    }

    @Test
    void crossSiteWriteIsRejected() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        HttpResponse<Buffer> res = send(client.post(PORT, "localhost", "/api/v1/auth/logout")
                .putHeader("Origin", "https://evil.example")
                .putHeader("Cookie", priya.cookie())
                .putHeader("X-CSRF-Token", priya.csrf()), null);

        assertEquals(403, res.statusCode());

        HttpResponse<Buffer> referer = send(client.post(PORT, "localhost", "/api/v1/auth/login")
                .putHeader("Referer", "https://evil.example/page"), new JsonObject());

        assertEquals(403, referer.statusCode());
        assertEquals("origin_rejected", TestApi.error(referer).getString("code"));

        HttpResponse<Buffer> same = send(client.post(PORT, "localhost", "/api/v1/auth/login")
                .putHeader("Origin", APP), new JsonObject().put("email", "x@pulse.demo").put("password", "nope-nope"));

        assertEquals(401, same.statusCode(), same.bodyAsString());
    }

    // ------------------------------------------------------------------ validation

    @Test
    void unknownOrOversizedFieldIsRejectedByName() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        HttpResponse<Buffer> unknown = api.post("/api/v1/probe/echo", priya,
                new JsonObject().put("title", "ok").put("isAdmin", true));

        assertEquals(400, unknown.statusCode());
        assertEquals("invalid_field", TestApi.error(unknown).getString("code"));
        assertEquals("isAdmin", TestApi.error(unknown).getString("field"));

        HttpResponse<Buffer> tooLong = api.post("/api/v1/probe/echo", priya, new JsonObject().put("title", "x".repeat(11)));

        assertEquals(400, tooLong.statusCode());
        assertEquals("title", TestApi.error(tooLong).getString("field"));

        HttpResponse<Buffer> tooMany = api.post("/api/v1/probe/echo", priya,
                new JsonObject().put("title", "ok").put("tags", new JsonArray().add("a").add("b").add("c").add("d")));

        assertEquals("tags", TestApi.error(tooMany).getString("field"));

        HttpResponse<Buffer> missing = api.post("/api/v1/probe/echo", priya, new JsonObject().put("count", 3));

        assertEquals("title", TestApi.error(missing).getString("field"));

        HttpResponse<Buffer> wrongType = api.post("/api/v1/probe/echo", priya,
                new JsonObject().put("title", "ok").put("count", "3"));

        assertEquals("count", TestApi.error(wrongType).getString("field"));

        HttpResponse<Buffer> ok = api.post("/api/v1/probe/echo", priya, new JsonObject().put("title", "ok").put("count", 3));

        assertEquals(200, ok.statusCode(), ok.bodyAsString());

        HttpResponse<Buffer> signIn = api.post("/api/v1/auth/login", null,
                new JsonObject().put("email", "priya@pulse.demo").put("password", "demo123").put("role", "Admin"));

        assertEquals(400, signIn.statusCode());
        assertEquals("role", TestApi.error(signIn).getString("field"));
    }

    @Test
    void bodyLimitIsEnforcedWhileStreaming() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        HttpResponse<Buffer> declared = api.post("/api/v1/probe/echo", priya,
                new JsonObject().put("title", "x".repeat(BODY_LIMIT * 2)));

        assertEquals(413, declared.statusCode());
        assertEquals("payload_too_large", TestApi.error(declared).getString("code"));

        // chunked, no Content-Length: rejected once the received bytes pass the limit
        HttpClient http = api.vertx().createHttpClient();

        try
        {
            HttpClientRequest req = await(http.request(new RequestOptions().setMethod(HttpMethod.POST)
                    .setHost("localhost").setPort(PORT).setURI("/api/v1/probe/echo")));

            req.setChunked(true)
                    .putHeader("Content-Type", "application/json")
                    .putHeader("Cookie", priya.cookie())
                    .putHeader("X-CSRF-Token", priya.csrf())
                    .putHeader("X-Real-IP", TestApi.newIp());

            for (int i = 0; i < 8; i++)
            {
                req.write(Buffer.buffer("x".repeat(1024)));
            }

            req.end();

            HttpClientResponse res = req.response().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);

            assertEquals(413, res.statusCode());
        }
        finally
        {
            http.close();
        }
    }

    // ------------------------------------------------------------------ rate limits

    @Test
    void perUserLimitAnswers429WithRetryAfter() throws Exception
    {
        TestApi.Session dev = api.signIn("dev@pulse.demo", TestApi.DEMO_PASSWORD);

        HttpResponse<Buffer> limited = firstLimited(USER_LIMIT, () -> api.get("/api/v1/me", dev));

        assertEquals("rate_limited", TestApi.error(limited).getString("code"));
        assertTrue(Integer.parseInt(limited.headers().get("Retry-After")) >= 1);
    }

    @Test
    void perIpLimitAnswers429() throws Exception
    {
        String ip = TestApi.newIp();

        HttpResponse<Buffer> limited = firstLimited(IP_LIMIT,
                () -> send(client.get(PORT, "localhost", "/health").putHeader("X-Real-IP", ip), null));

        assertNotNull(limited.headers().get("Retry-After"));

        // another IP is unaffected
        assertEquals(200, send(client.get(PORT, "localhost", "/health").putHeader("X-Real-IP", TestApi.newIp()), null)
                .statusCode());
    }

    // ------------------------------------------------------------------ errors

    @Test
    void unexpectedErrorIsGenericWithRequestIdAndRedactedLog() throws Exception
    {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        HttpResponse<Buffer> res;

        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

        try
        {
            res = api.get("/api/v1/probe/boom", RoleMatrix.session(api, RoleMatrix.EMPLOYEE));
        }
        finally
        {
            System.setOut(original);
        }

        String body = res.bodyAsString();
        String requestId = TestApi.error(res).getString("requestId");

        assertEquals(500, res.statusCode());
        assertEquals("internal_error", TestApi.error(res).getString("code"));
        assertEquals(requestId, res.headers().get(RequestId.HEADER));
        assertTrue(TestApi.error(res).getString("message").contains(requestId));
        assertFalse(body.contains("hunter2") || body.contains("IllegalStateException") || body.contains("at com."), body);

        String log = captured.toString(StandardCharsets.UTF_8);

        assertFalse(log.contains("hunter2") || log.contains("42.5"), "log must be redacted: " + log);
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface Call
    {
        HttpResponse<Buffer> run() throws Exception;
    }

    /**
     * Calls until the first 429; at least {@code limit} calls succeed first. A fixed window may roll
     * over during the loop, so up to {@code 2 * limit + 1} calls are allowed.
     */
    private static HttpResponse<Buffer> firstLimited(int limit, Call call) throws Exception
    {
        for (int i = 1; i <= 2 * limit + 1; i++)
        {
            HttpResponse<Buffer> res = call.run();

            if (res.statusCode() == 429)
            {
                assertTrue(i > limit, "limited after only " + (i - 1) + " calls");

                return res;
            }

            assertEquals(200, res.statusCode(), res.bodyAsString());
        }

        throw new AssertionError("never rate limited");
    }

    private static HttpResponse<Buffer> preflight(String origin) throws Exception
    {
        return send(client.request(HttpMethod.OPTIONS, PORT, "localhost", "/api/v1/auth/login")
                .putHeader("Origin", origin)
                .putHeader("Access-Control-Request-Method", "POST")
                .putHeader("Access-Control-Request-Headers", "content-type,x-csrf-token"), null);
    }

    private static HttpResponse<Buffer> send(HttpRequest<Buffer> req, JsonObject body) throws Exception
    {
        if (req.headers().get("X-Real-IP") == null)
        {
            req.putHeader("X-Real-IP", TestApi.newIp());
        }

        return (body == null ? req.send() : req.sendJsonObject(body)).toCompletionStage().toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
    }
}
