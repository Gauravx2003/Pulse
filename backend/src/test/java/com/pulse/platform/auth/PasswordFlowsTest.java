package com.pulse.platform.auth;

import com.pulse.platform.http.ApiException;
import com.pulse.testsupport.TestApi;
import com.pulse.testsupport.TestApi.Session;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.pulse.testsupport.TestApi.DEMO_PASSWORD;
import static com.pulse.testsupport.TestApi.await;
import static com.pulse.testsupport.TestApi.error;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3.3: invite, set-password, reset and change flows against the real HTTP server and
 * PostgreSQL. Verifies single use, expiry, unknown-email indistinguishability, the 8-character
 * policy and session revocation.
 */
class PasswordFlowsTest
{
    private static final int PORT = 18_193;

    private static final Pattern LINK_TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");

    private static final String NEW_PASSWORD = "Sunrise-2026";

    private static TestApi api;

    private static AuthSettings settings;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("app.url", "https://pulse.test/"));
        settings = AuthSettings.from(api.config());
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    // ------------------------------------------------------------------ invite

    @Test
    void acceptedInviteActivatesTheUserUsesUpTheLinkAndSignsIn() throws Exception
    {
        String userId = invitedUser("new.joiner@pulse.demo", "Nikhil Rao");
        String token = invite(userId);

        JsonObject mail = latestMail("new.joiner@pulse.demo");

        assertTrue(mail.getString("body").contains("https://pulse.test/set-password#token=" + token), mail.getString("body"));
        assertFalse(mail.getString("body").toLowerCase().contains("password:"), "no password is sent");
        assertEquals(0L, api.count("SELECT count(*) FROM password_tokens WHERE encode(token_hash, 'escape') LIKE '%" + token + "%'"),
                "only the hash of the link token is stored");
        assertEquals(0L, api.count("SELECT count(*) FROM audit_log WHERE summary::text LIKE '%" + token + "%'"));
        assertEquals(1L, api.count("SELECT count(*) FROM audit_log WHERE action = 'auth.invite.sent' AND target_id = '" + userId + "'"));

        HttpResponse<Buffer> set = setPassword(token, NEW_PASSWORD);

        assertEquals(200, set.statusCode(), set.bodyAsString());
        assertEquals("new.joiner@pulse.demo", set.bodyAsJsonObject().getJsonObject("user").getString("email"));
        assertEquals("Active", api.queryOne("SELECT status FROM users WHERE id = '" + userId + "'").getString("status"));
        assertEquals(1L, api.count("SELECT count(*) FROM audit_log WHERE action = 'auth.invite.accepted' AND actor_id = '" + userId + "'"));

        Session session = TestApi.session(set);

        assertEquals(200, api.get("/api/v1/me", session).statusCode(), "a session was created");
        assertEquals(200, api.post("/api/v1/auth/login", null,
                new JsonObject().put("email", "new.joiner@pulse.demo").put("password", NEW_PASSWORD)).statusCode());

        HttpResponse<Buffer> reused = setPassword(token, "Another-pass-1");

        assertEquals(422, reused.statusCode());
        assertEquals("link_invalid", error(reused).getString("code"));
        assertEquals(PasswordService.MSG_INVITE_LINK_INVALID, error(reused).getString("message"));
    }

    @Test
    void expiredInviteIsRefusedAndTellsTheUserToAskHr() throws Exception
    {
        String userId = invitedUser("late.joiner@pulse.demo", "Late Joiner");
        String token = invite(userId);

        api.exec("UPDATE password_tokens SET expires_at = now() - interval '1 second' WHERE user_id = '" + userId + "'");

        HttpResponse<Buffer> res = setPassword(token, NEW_PASSWORD);

        assertEquals(422, res.statusCode());
        assertEquals(PasswordService.MSG_INVITE_LINK_INVALID, error(res).getString("message"));
        assertEquals("Invited", api.queryOne("SELECT status FROM users WHERE id = '" + userId + "'").getString("status"));
    }

    @Test
    void aNewInviteReplacesTheEarlierLink() throws Exception
    {
        String userId = invitedUser("twice.invited@pulse.demo", "Twice Invited");
        String first = invite(userId);
        String second = invite(userId);

        assertEquals(422, setPassword(first, NEW_PASSWORD).statusCode());
        assertEquals(200, setPassword(second, NEW_PASSWORD).statusCode());
    }

    @Test
    void onlyInvitedUsersCanBeInvited() throws Exception
    {
        String activeId = api.queryOne("SELECT id FROM users WHERE email = 'kiran@pulse.demo'").getString("id");

        ExecutionException e = assertThrows(ExecutionException.class, () -> invite(activeId));

        ApiException api409 = assertInstanceOf(ApiException.class, e.getCause());

        assertEquals(409, api409.status());
    }

    @Test
    void passwordPolicyIsCheckedBeforeTheLinkIsUsed() throws Exception
    {
        String userId = invitedUser("policy.check@pulse.demo", "Policy Check");
        String token = invite(userId);

        HttpResponse<Buffer> shortOne = setPassword(token, "abc123");

        assertEquals(422, shortOne.statusCode());
        assertEquals("password_policy", error(shortOne).getString("code"));
        assertEquals(PasswordPolicy.MSG_TOO_SHORT, error(shortOne).getString("message"));
        assertTrue(error(shortOne).getString("message").contains("8 characters"));

        assertEquals(PasswordPolicy.MSG_PERSONAL, error(setPassword(token, "Policy.Check@pulse.demo")).getString("message"),
                "the email, in any case, is refused");
        assertEquals(PasswordPolicy.MSG_PERSONAL, error(setPassword(token, "policy check")).getString("message"),
                "the name is refused");

        assertEquals(200, setPassword(token, NEW_PASSWORD).statusCode(), "refused attempts did not burn the link");
    }

    @Test
    void unknownLinkIsRefused() throws Exception
    {
        HttpResponse<Buffer> res = setPassword("not-a-real-token", NEW_PASSWORD);

        assertEquals(422, res.statusCode());
        assertEquals(PasswordService.MSG_LINK_UNKNOWN, error(res).getString("message"));
    }

    // ------------------------------------------------------------------ reset

    @Test
    void resetRequestForUnknownEmailIsIndistinguishableAndQueuesNothing() throws Exception
    {
        long before = api.count("SELECT count(*) FROM outbox");

        HttpResponse<Buffer> known = forgot("arjun@pulse.demo");
        HttpResponse<Buffer> unknown = forgot("nobody-here@pulse.demo");

        assertEquals(200, known.statusCode(), known.bodyAsString());
        assertEquals(known.statusCode(), unknown.statusCode());
        assertEquals(known.bodyAsString(), unknown.bodyAsString());
        assertEquals(PasswordService.MSG_RESET_REQUESTED, unknown.bodyAsJsonObject().getString("message"));
        assertEquals(before + 1, api.count("SELECT count(*) FROM outbox"), "only the known email got a message");
        assertEquals(0L, api.count("SELECT count(*) FROM outbox WHERE recipient = 'nobody-here@pulse.demo'"));
        assertTrue(latestMail("arjun@pulse.demo").getString("body").contains("https://pulse.test/reset-password#token="));
    }

    @Test
    void deactivatedAccountsGetTheSameAnswerAndNoLink() throws Exception
    {
        api.exec("UPDATE users SET status = 'Deactivated' WHERE email = 'rohan@pulse.demo'");

        HttpResponse<Buffer> res = forgot("rohan@pulse.demo");

        assertEquals(200, res.statusCode());
        assertEquals(PasswordService.MSG_RESET_REQUESTED, res.bodyAsJsonObject().getString("message"));
        assertEquals(0L, api.count("SELECT count(*) FROM outbox WHERE recipient = 'rohan@pulse.demo'"));
    }

    @Test
    void completedResetEndsEverySessionAndIsAudited() throws Exception
    {
        Session before = api.signIn("dev@pulse.demo", DEMO_PASSWORD);

        assertEquals(200, forgot("dev@pulse.demo").statusCode());

        String token = tokenOf(latestMail("dev@pulse.demo"));

        HttpResponse<Buffer> res = setPassword(token, NEW_PASSWORD);

        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertEquals(401, api.get("/api/v1/me", before).statusCode(), "the earlier session ended");
        assertEquals(200, api.get("/api/v1/me", TestApi.session(res)).statusCode());
        assertEquals(401, api.post("/api/v1/auth/login", null,
                new JsonObject().put("email", "dev@pulse.demo").put("password", DEMO_PASSWORD)).statusCode());
        assertEquals(1L, api.count("SELECT count(*) FROM audit_log a JOIN users u ON u.id = a.actor_id "
                + "WHERE a.action = 'auth.password.reset' AND u.email = 'dev@pulse.demo' "
                + "AND (a.summary ->> 'sessionsEnded')::int >= 1"));

        HttpResponse<Buffer> reused = setPassword(token, "Yet-another-1");

        assertEquals(422, reused.statusCode());
        assertEquals(PasswordService.MSG_RESET_LINK_INVALID, error(reused).getString("message"));
    }

    @Test
    void expiredResetLinkIsRefused() throws Exception
    {
        assertEquals(200, forgot("sara@pulse.demo").statusCode());

        String token = tokenOf(latestMail("sara@pulse.demo"));

        api.exec("UPDATE password_tokens SET expires_at = now() - interval '1 second' "
                + "WHERE user_id = (SELECT id FROM users WHERE email = 'sara@pulse.demo')");

        HttpResponse<Buffer> res = setPassword(token, NEW_PASSWORD);

        assertEquals(422, res.statusCode());
        assertEquals(PasswordService.MSG_RESET_LINK_INVALID, error(res).getString("message"));
        assertEquals(200, api.post("/api/v1/auth/login", null,
                new JsonObject().put("email", "sara@pulse.demo").put("password", DEMO_PASSWORD)).statusCode(),
                "the password did not change");
    }

    // ------------------------------------------------------------------ change while signed in

    @Test
    void changingThePasswordKeepsThisSessionAndEndsTheOthers() throws Exception
    {
        Session here = api.signIn("neha@pulse.demo", DEMO_PASSWORD);
        Session elsewhere = api.signIn("neha@pulse.demo", DEMO_PASSWORD);

        HttpResponse<Buffer> noCsrf = api.post("/api/v1/auth/password/change", new Session(here.cookie(), null, null),
                change(DEMO_PASSWORD, NEW_PASSWORD));

        assertEquals(403, noCsrf.statusCode(), "writes need the CSRF token");

        HttpResponse<Buffer> wrongCurrent = api.post("/api/v1/auth/password/change", here, change("not-it", NEW_PASSWORD));

        assertEquals(422, wrongCurrent.statusCode());
        assertEquals(PasswordService.MSG_CURRENT_WRONG, error(wrongCurrent).getString("message"));
        assertEquals(1L, api.count("SELECT count(*) FROM audit_log a JOIN users u ON u.id = a.actor_id "
                + "WHERE a.action = 'auth.password.changed' AND a.outcome = 'refused' AND u.email = 'neha@pulse.demo'"));

        HttpResponse<Buffer> weak = api.post("/api/v1/auth/password/change", here, change(DEMO_PASSWORD, "short"));

        assertEquals(PasswordPolicy.MSG_TOO_SHORT, error(weak).getString("message"));

        assertEquals(204, api.post("/api/v1/auth/password/change", here, change(DEMO_PASSWORD, NEW_PASSWORD)).statusCode());
        assertEquals(200, api.get("/api/v1/me", here).statusCode(), "the current session stays");
        assertEquals(401, api.get("/api/v1/me", elsewhere).statusCode(), "other sessions end");
        assertEquals(200, api.post("/api/v1/auth/login", null,
                new JsonObject().put("email", "neha@pulse.demo").put("password", NEW_PASSWORD)).statusCode());
        assertEquals(0L, api.count("SELECT count(*) FROM audit_log WHERE summary::text LIKE '%" + NEW_PASSWORD + "%'"));
    }

    @Test
    void passwordChangeNeedsASession() throws Exception
    {
        assertEquals(401, api.post("/api/v1/auth/password/change", null, change(DEMO_PASSWORD, NEW_PASSWORD)).statusCode());
    }

    // ------------------------------------------------------------------ helpers

    private static String invitedUser(String email, String name) throws Exception
    {
        return api.queryOne("INSERT INTO users (email, name, role_id, status) VALUES ('" + email + "', '" + name + "', "
                + "(SELECT id FROM roles WHERE name = 'Employee'), 'Invited') RETURNING id").getString("id");
    }

    /** Issues an invite as hr@ and returns the token from the queued message. */
    private static String invite(String userId) throws Exception
    {
        String hrId = api.queryOne("SELECT id FROM users WHERE email = 'hr@pulse.demo'").getString("id");

        await(api.sql().inTransaction(tx -> PasswordService.issueInvite(tx, settings, userId, hrId, "10.9.9.9")));

        String email = api.queryOne("SELECT email FROM users WHERE id = '" + userId + "'").getString("email");

        return tokenOf(latestMail(email));
    }

    private static JsonObject latestMail(String recipient) throws Exception
    {
        return api.queryOne("SELECT subject, body, payload FROM outbox WHERE channel = 'email' AND recipient = '"
                + recipient + "' ORDER BY created_at DESC, id DESC LIMIT 1");
    }

    private static String tokenOf(JsonObject mail)
    {
        Matcher m = LINK_TOKEN.matcher(mail.getString("body"));

        assertTrue(m.find(), mail.getString("body"));

        return m.group(1);
    }

    private static HttpResponse<Buffer> setPassword(String token, String password) throws Exception
    {
        return api.post("/api/v1/auth/password/set", null, new JsonObject().put("token", token).put("password", password));
    }

    private static HttpResponse<Buffer> forgot(String email) throws Exception
    {
        return api.post("/api/v1/auth/password/forgot", null, new JsonObject().put("email", email));
    }

    private static JsonObject change(String current, String next)
    {
        return new JsonObject().put("currentPassword", current).put("newPassword", next);
    }
}
