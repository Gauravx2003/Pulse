package com.pulse.testsupport;

import com.pulse.HttpServerVerticle;
import com.pulse.platform.auth.SessionHandler;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.pgclient.PgPool;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The real HTTP server on a throw-away schema with the platform migrations, base seed and demo seed
 * (needs PostgreSQL, see {@link TestDb}). Every request gets its own client IP unless one is given,
 * so lockout and throttle counters do not couple tests.
 */
public final class TestApi implements AutoCloseable
{
    public static final String DEMO_PASSWORD = "demo123";

    private static final AtomicInteger IPS = new AtomicInteger(1);

    private final TestDb db;

    private final PgPool owner;

    private final WebClient client;

    private final int port;

    private final JsonObject config;

    private TestApi(TestDb db, PgPool owner, WebClient client, int port, JsonObject config)
    {
        this.db = db;
        this.owner = owner;
        this.client = client;
        this.port = port;
        this.config = config;
    }

    /** Starts the server on {@code port}; {@code extraConfig} is merged into the verticle config. */
    public static TestApi start(int port, JsonObject extraConfig) throws Exception
    {
        TestDb db = TestDb.open();
        PgPool owner = db.ownerPool();

        ScriptCatalog platform = ScriptCatalog.load(TestApi.class.getClassLoader(), List.of(ScriptCatalog.PLATFORM));

        await(MigrationRunner.migrate(owner, platform));
        await(MigrationRunner.seed(owner, platform, true));

        JsonObject config = new JsonObject()
                .put("http.port", port)
                .put("db.searchPath", db.schema())
                .put("http.trustProxy", true)
                .mergeIn(extraConfig == null ? new JsonObject() : extraConfig);

        await(db.vertx().deployVerticle(new HttpServerVerticle(), new DeploymentOptions().setConfig(config)));

        return new TestApi(db, owner, WebClient.create(db.vertx()), port, config);
    }

    public JsonObject config()
    {
        return config;
    }

    public io.vertx.core.Vertx vertx()
    {
        return db.vertx();
    }

    /** Executor on the owner pool, for arranging data and calling services directly. */
    public SqlExecutor sql()
    {
        return new SqlExecutor(owner);
    }

    public static String newIp()
    {
        return "10.1." + (IPS.get() / 250) + "." + (IPS.getAndIncrement() % 250 + 1);
    }

    // ------------------------------------------------------------------ sessions

    /** A signed-in browser: cookie header value, CSRF token and the sign-in body. */
    public record Session(String cookie, String csrf, JsonObject body)
    {
    }

    /** Signs in and fails the test unless it succeeds. */
    public Session signIn(String email, String password) throws Exception
    {
        HttpResponse<Buffer> res = post("/api/v1/auth/login", null,
                new JsonObject().put("email", email).put("password", password));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return session(res);
    }

    /** The session a successful sign-in or set-password response started. */
    public static Session session(HttpResponse<Buffer> res)
    {
        return new Session(cookie(res), res.bodyAsJsonObject().getString("csrfToken"), res.bodyAsJsonObject());
    }

    /** {@code pulse_session=<token>} from a Set-Cookie header. */
    public static String cookie(HttpResponse<Buffer> res)
    {
        String setCookie = res.headers().get("Set-Cookie");

        assertNotNull(setCookie, "expected a session cookie: " + res.bodyAsString());

        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    // ------------------------------------------------------------------ requests

    public HttpResponse<Buffer> get(String path, Session session) throws Exception
    {
        return send(client.get(port, "localhost", path), session, null);
    }

    /** POST with the session's cookie and CSRF token (when a session is given). */
    public HttpResponse<Buffer> post(String path, Session session, JsonObject body) throws Exception
    {
        return send(client.post(port, "localhost", path), session, body);
    }

    /** PUT with the session's cookie and CSRF token. */
    public HttpResponse<Buffer> put(String path, Session session, JsonObject body) throws Exception
    {
        return send(client.put(port, "localhost", path), session, body);
    }

    private static HttpResponse<Buffer> send(HttpRequest<Buffer> req, Session session, JsonObject body) throws Exception
    {
        req.putHeader("X-Real-IP", newIp());

        if (session != null)
        {
            req.putHeader("Cookie", session.cookie());

            if (session.csrf() != null)
            {
                req.putHeader(SessionHandler.CSRF_HEADER, session.csrf());
            }
        }

        Future<HttpResponse<Buffer>> sent = body == null ? req.send() : req.sendJsonObject(body);

        return sent.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    public static JsonObject error(HttpResponse<Buffer> res)
    {
        return res.bodyAsJsonObject().getJsonObject("error");
    }

    // ------------------------------------------------------------------ database

    public void exec(String sql) throws Exception
    {
        await(owner.query(sql).execute());
    }

    public long count(String sql) throws Exception
    {
        return await(owner.query(sql).execute()).iterator().next().getLong(0);
    }

    public JsonObject queryOne(String sql) throws Exception
    {
        return await(sql().queryOne(sql));
    }

    public static <T> T await(Future<T> future) throws Exception
    {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception
    {
        db.close();
    }
}
