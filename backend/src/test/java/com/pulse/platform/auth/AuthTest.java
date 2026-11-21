package com.pulse.platform.auth;

import com.pulse.HttpServerVerticle;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.pgclient.PgPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tasks 3.1 and 3.2 against the real HTTP server and PostgreSQL (V100, V101, base and demo seed in a
 * throw-away schema). Every test signs in as its own demo user from its own client IP so lockout and
 * throttle counters do not interfere.
 */
class AuthTest
{
    private static final int PORT = 18_191;

    private static final int IP_LIMIT = 8;

    private static final String PASSWORD = "demo123";

    private static final AtomicInteger IPS = new AtomicInteger(1);

    private static TestDb db;

    private static PgPool owner;

    private static WebClient client;

    @BeforeAll
    static void start() throws Exception
    {
        db = TestDb.open();
        owner = db.ownerPool();

        ScriptCatalog platform = ScriptCatalog.load(AuthTest.class.getClassLoader(), List.of(ScriptCatalog.PLATFORM));

        await(MigrationRunner.migrate(owner, platform));
        await(MigrationRunner.seed(owner, platform, true));

        JsonObject config = new JsonObject()
                .put("http.port", PORT)
                .put("db.searchPath", db.schema())
                .put("http.trustProxy", true)
                .put("auth.ipLimit", IP_LIMIT);

        await(db.vertx().deployVerticle(new HttpServerVerticle(), new DeploymentOptions().setConfig(config)));

        client = WebClient.create(db.vertx());
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (db != null)
        {
            db.close();
        }
    }

    // ------------------------------------------------------------------ 3.1 sign-in

    @Test
    void validCredentialsCreateASessionAndLandOnTheRolesFirstPage() throws Exception
    {
        String ip = newIp();

        HttpResponse<Buffer> res = login("priya@pulse.demo", PASSWORD, ip);

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject body = res.bodyAsJsonObject();

        assertEquals("Employee", body.getString("role"));
        assertEquals("/overview", body.getString("landing"));
        assertEquals("priya@pulse.demo", body.getJsonObject("user").getString("email"));
        assertNotNull(body.getString("csrfToken"));
        assertFalse(body.encode().contains("password"), "no password or hash in the response");

        String setCookie = res.headers().get("Set-Cookie");
        String attributes = setCookie.toLowerCase(java.util.Locale.ROOT); // cookie attributes are case-insensitive

        assertTrue(setCookie.startsWith(SessionHandler.COOKIE + "="), setCookie);
        assertTrue(attributes.contains("; httponly"), setCookie);
        assertTrue(attributes.contains("; secure"), setCookie);
        assertTrue(attributes.contains("; samesite=strict"), setCookie);
        assertTrue(attributes.contains("; max-age=43200"), setCookie);
        assertTrue(attributes.contains("; path=/"), setCookie);

        HttpResponse<Buffer> me = get("/api/v1/me", cookie(res), ip);

        assertEquals(200, me.statusCode());
        assertEquals("priya@pulse.demo", me.bodyAsJsonObject().getJsonObject("user").getString("email"));

        assertEquals(1L, count("SELECT count(*) FROM users WHERE email = 'priya@pulse.demo' AND last_login_at IS NOT NULL"));
        assertTrue(count("SELECT count(*) FROM audit_log WHERE action = 'auth.sign_in' AND outcome = 'success'") >= 1);

        HttpResponse<Buffer> manager = login("rahul@pulse.demo", PASSWORD, newIp());

        assertEquals("/actions", manager.bodyAsJsonObject().getString("landing"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameAnswer() throws Exception
    {
        HttpResponse<Buffer> wrongPassword = login("kiran@pulse.demo", "not-the-password", newIp());
        HttpResponse<Buffer> unknownEmail = login("nobody@pulse.demo", "not-the-password", newIp());

        assertEquals(401, wrongPassword.statusCode());
        assertEquals(401, unknownEmail.statusCode());
        assertEquals(error(wrongPassword).getString("code"), error(unknownEmail).getString("code"));
        assertEquals(AuthService.MSG_INVALID, error(wrongPassword).getString("message"));
        assertEquals(AuthService.MSG_INVALID, error(unknownEmail).getString("message"));
    }

    @Test
    void deactivatedAccountIsRefusedWithItsMessage() throws Exception
    {
        exec("UPDATE users SET status = 'Deactivated' WHERE email = 'anil@pulse.demo'");

        HttpResponse<Buffer> res = login("anil@pulse.demo", PASSWORD, newIp());

        assertEquals(403, res.statusCode());
        assertEquals(AuthService.MSG_DEACTIVATED, error(res).getString("message"));

        // A wrong password still gives the generic answer: no hint that the account exists.
        assertEquals(AuthService.MSG_INVALID, error(login("anil@pulse.demo", "wrong", newIp())).getString("message"));
    }

    @Test
    void accountWhoseRoleWasDeletedIsRefused() throws Exception
    {
        exec("UPDATE users SET role_id = NULL WHERE email = 'rohan@pulse.demo'");

        HttpResponse<Buffer> res = login("rohan@pulse.demo", PASSWORD, newIp());

        assertEquals(403, res.statusCode());
        assertEquals(AuthService.MSG_ROLE_MISSING, error(res).getString("message"));
    }

    @Test
    void fiveFailuresLockTheAccountEvenForTheRightPasswordAndAreAudited() throws Exception
    {
        for (int i = 0; i < 5; i++)
        {
            assertEquals(401, login("arjun@pulse.demo", "wrong-" + i, newIp()).statusCode());
        }

        HttpResponse<Buffer> locked = login("arjun@pulse.demo", PASSWORD, newIp());

        assertEquals(429, locked.statusCode());
        assertEquals(AuthService.MSG_LOCKED, error(locked).getString("message"));
        assertEquals(1L, count("SELECT count(*) FROM audit_log a JOIN users u ON u.id = a.actor_id "
                + "WHERE a.action = 'auth.lockout' AND u.email = 'arjun@pulse.demo'"));
    }

    @Test
    void unknownEmailsLockOutTheSameWay() throws Exception
    {
        for (int i = 0; i < 5; i++)
        {
            login("ghost@pulse.demo", "wrong-" + i, newIp());
        }

        HttpResponse<Buffer> res = login("ghost@pulse.demo", "anything", newIp());

        assertEquals(429, res.statusCode());
        assertEquals(AuthService.MSG_LOCKED, error(res).getString("message"));
    }

    @Test
    void lockoutEndsAfterFifteenMinutes() throws Exception
    {
        for (int i = 0; i < 5; i++)
        {
            login("meera@pulse.demo", "wrong-" + i, newIp());
        }

        assertEquals(429, login("meera@pulse.demo", PASSWORD, newIp()).statusCode());

        exec("UPDATE login_attempts SET locked_until = now() - interval '1 second' WHERE email = 'meera@pulse.demo'");

        assertEquals(200, login("meera@pulse.demo", PASSWORD, newIp()).statusCode());
        assertEquals(0L, count("SELECT count(*) FROM login_attempts WHERE email = 'meera@pulse.demo'"),
                "a successful sign-in clears the failures");
    }

    @Test
    void manyEmailsFromOneIpAreRateLimited() throws Exception
    {
        String ip = newIp();

        for (int i = 0; i < IP_LIMIT; i++)
        {
            assertEquals(401, login("visitor" + i + "@example.com", "x", ip).statusCode());
        }

        HttpResponse<Buffer> res = login("visitor-last@example.com", "x", ip);

        assertEquals(429, res.statusCode());
        assertEquals("rate_limited", error(res).getString("code"));
        assertEquals(200, login("sana@pulse.demo", PASSWORD, newIp()).statusCode(), "other IPs are not affected");
    }

    @Test
    void passwordsAndTokensAreNeverStoredInClear() throws Exception
    {
        HttpResponse<Buffer> res = login("anjali@pulse.demo", PASSWORD, newIp());

        String token = cookie(res).substring((SessionHandler.COOKIE + "=").length());

        assertEquals(0L, count("SELECT count(*) FROM audit_log WHERE summary::text LIKE '%demo123%' "
                + "OR summary::text LIKE '%" + token + "%'"));
        assertEquals(0L, count("SELECT count(*) FROM sessions WHERE encode(token_hash, 'escape') LIKE '%" + token + "%'"));
        assertEquals(1L, count("SELECT count(*) FROM sessions s JOIN users u ON u.id = s.user_id "
                + "WHERE u.email = 'anjali@pulse.demo' AND s.token_hash = sha256(convert_to('" + token + "', 'UTF8'))"));
    }

    // ------------------------------------------------------------------ 3.2 sessions

    @Test
    void apiRequiresASession() throws Exception
    {
        HttpResponse<Buffer> me = get("/api/v1/me", null, newIp());
        HttpResponse<Buffer> module = get("/api/v1/sample/ping", null, newIp());
        HttpResponse<Buffer> forged = get("/api/v1/me", SessionHandler.COOKIE + "=made-up-token", newIp());

        assertEquals(401, me.statusCode());
        assertEquals("unauthenticated", error(me).getString("code"));
        assertEquals(401, module.statusCode());
        assertEquals(401, forged.statusCode());
    }

    @Test
    void writesNeedTheSessionsCsrfToken() throws Exception
    {
        String ip = newIp();

        HttpResponse<Buffer> res = login("kavya@pulse.demo", PASSWORD, ip);

        String cookie = cookie(res);
        String csrf = res.bodyAsJsonObject().getString("csrfToken");

        HttpResponse<Buffer> missing = post("/api/v1/auth/logout", cookie, null, ip);
        HttpResponse<Buffer> forged = post("/api/v1/auth/logout", cookie, "forged-token", ip);

        assertEquals(403, missing.statusCode());
        assertEquals("csrf_invalid", error(missing).getString("code"));
        assertEquals(403, forged.statusCode());
        assertEquals(200, get("/api/v1/me", cookie, ip).statusCode(), "the session survives a refused write");

        HttpResponse<Buffer> out = post("/api/v1/auth/logout", cookie, csrf, ip);

        assertEquals(204, out.statusCode());
        assertTrue(out.headers().get("Set-Cookie").contains("Max-Age=0"), out.headers().get("Set-Cookie"));
        assertEquals(401, get("/api/v1/me", cookie, ip).statusCode(), "signed out sessions cannot be reused");
        assertEquals(1L, count("SELECT count(*) FROM audit_log WHERE action = 'auth.sign_out'"));
    }

    @Test
    void deactivationEndsTheSessionOnTheNextRequest() throws Exception
    {
        String ip = newIp();
        String cookie = cookie(login("sara@pulse.demo", PASSWORD, ip));

        assertEquals(200, get("/api/v1/me", cookie, ip).statusCode());

        exec("UPDATE users SET status = 'Deactivated' WHERE email = 'sara@pulse.demo'");

        HttpResponse<Buffer> next = get("/api/v1/me", cookie, ip);

        assertEquals(401, next.statusCode());
        assertEquals(0L, count("SELECT count(*) FROM sessions s JOIN users u ON u.id = s.user_id WHERE u.email = 'sara@pulse.demo'"));

        exec("UPDATE users SET status = 'Active' WHERE email = 'sara@pulse.demo'");

        assertEquals(401, get("/api/v1/me", cookie, ip).statusCode(), "a revoked session cannot be reused");
    }

    @Test
    void sessionsExpireAfterTwelveHours() throws Exception
    {
        String ip = newIp();
        String cookie = cookie(login("dev@pulse.demo", PASSWORD, ip));

        assertEquals(1L, count("SELECT count(*) FROM sessions s JOIN users u ON u.id = s.user_id "
                + "WHERE u.email = 'dev@pulse.demo' AND s.expires_at BETWEEN now() + interval '11 hours 59 minutes' "
                + "AND now() + interval '12 hours 1 minute'"));

        exec("UPDATE sessions SET expires_at = now() - interval '1 second' "
                + "WHERE user_id = (SELECT id FROM users WHERE email = 'dev@pulse.demo')");

        HttpResponse<Buffer> res = get("/api/v1/me", cookie, ip);

        assertEquals(401, res.statusCode());
        assertEquals("session_expired", error(res).getString("code"));
    }

    @Test
    void signInRotatesTheSessionId() throws Exception
    {
        String ip = newIp();
        String first = cookie(login("rohit@pulse.demo", PASSWORD, ip));

        HttpResponse<Buffer> again = client.post(PORT, "localhost", "/api/v1/auth/login")
                .putHeader("X-Real-IP", ip)
                .putHeader("Cookie", first)
                .sendJsonObject(new JsonObject().put("email", "rohit@pulse.demo").put("password", PASSWORD))
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);

        String second = cookie(again);

        assertFalse(first.equals(second));
        assertEquals(401, get("/api/v1/me", first, ip).statusCode(), "the old session id is gone");
        assertEquals(200, get("/api/v1/me", second, ip).statusCode());
    }

    @Test
    void permissionChangeOnARoleEndsItsHoldersSessions() throws Exception
    {
        String ip = newIp();
        String cookie = cookie(login("neha@pulse.demo", PASSWORD, ip));

        String employeeRole = await(new SqlExecutor(owner).queryOne("SELECT id FROM roles WHERE name = 'Employee'"))
                .getString("id");

        await(new SqlExecutor(owner).inTransaction(tx -> AuthService.revokeRole(tx, employeeRole)));

        assertEquals(401, get("/api/v1/me", cookie, ip).statusCode());
    }

    @Test
    void adminAlwaysHoldsEveryRegisteredPermission() throws Exception
    {
        String ip = newIp();

        JsonObject me = get("/api/v1/me", cookie(login("hr@pulse.demo", PASSWORD, ip)), ip).bodyAsJsonObject();

        assertTrue(me.getJsonArray("permissions").contains("sample.view"), "module permissions included");
        assertTrue(me.getJsonArray("permissions").contains("users.all"));
        assertEquals("/actions", me.getString("landing"));
        assertTrue(me.getJsonArray("menu").encode().contains("/sample"));

        HttpResponse<Buffer> ping = get("/api/v1/sample/ping", cookie(login("hr@pulse.demo", PASSWORD, ip)), ip);

        assertEquals(200, ping.statusCode(), "module routes are served to signed-in users");
        assertEquals("sample", ping.bodyAsJsonObject().getString("module"));
    }

    @Test
    void malformedSignInIsA400() throws Exception
    {
        HttpResponse<Buffer> res = client.post(PORT, "localhost", "/api/v1/auth/login")
                .putHeader("X-Real-IP", newIp())
                .putHeader("Content-Type", "application/json")
                .sendBuffer(Buffer.buffer("{not json"))
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertEquals(400, res.statusCode());
        assertNull(res.headers().get("Set-Cookie"));
    }

    // ------------------------------------------------------------------ helpers

    private static String newIp()
    {
        return "10.0." + (IPS.get() / 250) + "." + (IPS.getAndIncrement() % 250 + 1);
    }

    private static HttpResponse<Buffer> login(String email, String password, String ip) throws Exception
    {
        return client.post(PORT, "localhost", "/api/v1/auth/login")
                .putHeader("X-Real-IP", ip)
                .sendJsonObject(new JsonObject().put("email", email).put("password", password))
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static HttpResponse<Buffer> get(String path, String cookie, String ip) throws Exception
    {
        var req = client.get(PORT, "localhost", path).putHeader("X-Real-IP", ip);

        if (cookie != null)
        {
            req.putHeader("Cookie", cookie);
        }

        return req.send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static HttpResponse<Buffer> post(String path, String cookie, String csrf, String ip) throws Exception
    {
        var req = client.post(PORT, "localhost", path).putHeader("X-Real-IP", ip).putHeader("Cookie", cookie);

        if (csrf != null)
        {
            req.putHeader(SessionHandler.CSRF_HEADER, csrf);
        }

        return req.send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    /** {@code pulse_session=<token>} from a Set-Cookie header. */
    private static String cookie(HttpResponse<Buffer> res)
    {
        String setCookie = res.headers().get("Set-Cookie");

        assertNotNull(setCookie, "expected a session cookie: " + res.bodyAsString());

        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static JsonObject error(HttpResponse<Buffer> res)
    {
        return res.bodyAsJsonObject().getJsonObject("error");
    }

    private static void exec(String sql) throws Exception
    {
        await(owner.query(sql).execute());
    }

    private static long count(String sql) throws Exception
    {
        return await(owner.query(sql).execute()).iterator().next().getLong(0);
    }

    private static <T> T await(Future<T> future) throws Exception
    {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
