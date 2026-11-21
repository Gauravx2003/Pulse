package com.pulse.platform.auth;

import com.pulse.platform.audit.AuditService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.outbox.OutboxRepository;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/**
 * Invite, set-password, reset and change flows (task 3.3, spec platform/auth-session "Invite and
 * first sign-in", "Password reset and change", "Password policy").
 *
 * <ul>
 *   <li>Links are single-use and expiring. Only the SHA-256 of the token is stored; the token travels
 *       in the URL fragment ({@code #token=}), so it never reaches server or proxy logs. Each link goes
 *       to the outbox; no password is ever sent by any channel.</li>
 *   <li>A reset request answers the same for known and unknown emails and queues nothing for an
 *       unknown one.</li>
 *   <li>Setting a password from a link ends every session of the user, activates an Invited user and
 *       signs the browser in. Changing it while signed in ends every other session.</li>
 * </ul>
 */
public class PasswordService
{
    public static final String PURPOSE_INVITE = "invite";

    public static final String PURPOSE_RESET = "reset";

    public static final String MSG_RESET_REQUESTED =
            "If an account exists for this email, we have sent a link to reset the password.";

    public static final String MSG_INVITE_LINK_INVALID =
            "This link has expired or was already used. Ask HR for a new invite.";

    public static final String MSG_RESET_LINK_INVALID =
            "This link has expired or was already used. Request a new password reset link.";

    public static final String MSG_LINK_UNKNOWN =
            "This link is not valid. Ask HR for a new invite or request a new password reset link.";

    public static final String MSG_CURRENT_WRONG = "Current password is incorrect.";

    public static final String MSG_ENTER_PASSWORD = "Enter a new password.";

    private static final int MAX_TOKEN_LENGTH = 100;

    private final Vertx vertx;

    private final SqlExecutor sql;

    private final AuthService auth;

    public PasswordService(Vertx vertx, SqlExecutor sql, AuthService auth)
    {
        this.vertx = vertx;
        this.sql = sql;
        this.auth = auth;
    }

    // ------------------------------------------------------------------ invite

    /**
     * Sends an Invited user a set-password link through the outbox, replacing any earlier unused
     * invite link; audited. Call it with the executor of the caller's transaction (e.g. the add-user
     * or resend-invite action of task 5.1) so the link and the audit entry commit with the action.
     * Fails with 404 for an unknown user and 409 when the user is not Invited.
     */
    public static Future<Void> issueInvite(SqlExecutor tx, AuthSettings settings, String userId, String actorId,
                                           String sourceIp)
    {
        PasswordTokenRepository repo = new PasswordTokenRepository(tx);

        return repo.userById(userId).compose(user ->
        {
            if (user == null)
            {
                return Future.failedFuture(new ApiException(404, "not_found", "User not found."));
            }

            if (!"Invited".equals(user.getString("status")))
            {
                return Future.failedFuture(new ApiException(409, "invite_not_needed",
                        "Only users waiting for their first sign-in can be invited."));
            }

            String token = SessionTokens.newToken();
            String link = settings.appUrl() + "/set-password#token=" + token;

            String body = "Hello " + user.getString("name") + ",\n\n"
                    + "You have been invited to Pulse. Choose your password here:\n" + link + "\n\n"
                    + "The link works once and expires in " + duration(settings.inviteTtlSeconds()) + ". "
                    + "If it has expired, ask HR for a new invite.";

            return repo.deleteUnused(userId, PURPOSE_INVITE)
                    .compose(n -> repo.insert(userId, PURPOSE_INVITE, SessionTokens.hash(token),
                            settings.inviteTtlSeconds(), actorId))
                    .compose(n -> new OutboxRepository(tx).enqueue("email", user.getString("email"),
                            "You're invited to Pulse", body,
                            new JsonObject().put("kind", "auth.invite").put("userId", userId)))
                    .compose(v -> AuditService.record(tx, AuthService.entry("auth.invite.sent", "success", actorId, sourceIp)
                            .put("targetType", "user")
                            .put("targetId", userId)
                            .put("summary", new JsonObject().put("expiresInSeconds", settings.inviteTtlSeconds()))));
        });
    }

    // ------------------------------------------------------------------ reset request

    /**
     * Queues a reset link for an active account. The answer is the same whether or not the email
     * has an account, and nothing is queued when it has none. Counts against the per-IP sign-in
     * throttle so the endpoint cannot be used to flood mailboxes or probe emails.
     */
    public Future<JsonObject> requestReset(String email, String sourceIp)
    {
        if (email == null || email.isBlank() || email.length() > 254)
        {
            return Future.failedFuture(ApiException.badRequest("invalid_email", "Enter your work email."));
        }

        String typedEmail = email.strip();
        AuthSettings settings = auth.settings();
        AuthRepository repo = new AuthRepository(sql);

        JsonObject answer = new JsonObject().put("message", MSG_RESET_REQUESTED);

        return repo.countIpAttempt(sourceIp, settings.ipWindowSeconds())
                .compose(attempts ->
                {
                    if (attempts > settings.ipLimit())
                    {
                        return Future.failedFuture(ApiException.tooManyRequests("rate_limited", AuthService.MSG_THROTTLED));
                    }

                    return repo.userByEmail(typedEmail);
                })
                .compose(user ->
                {
                    if (user == null || !"Active".equals(user.getString("status")) || user.getString("role_id") == null)
                    {
                        return Future.succeededFuture(answer);
                    }

                    return sql.inTransaction(tx -> queueReset(tx, settings, user, sourceIp)).map(answer);
                });
    }

    private static Future<Void> queueReset(SqlExecutor tx, AuthSettings settings, JsonObject user, String sourceIp)
    {
        String userId = user.getString("id");
        String token = SessionTokens.newToken();
        String link = settings.appUrl() + "/reset-password#token=" + token;

        String body = "Hello " + user.getString("name") + ",\n\n"
                + "Someone asked to reset your Pulse password. Choose a new password here:\n" + link + "\n\n"
                + "The link works once and expires in " + duration(settings.resetTtlSeconds()) + ". "
                + "If you did not ask for this, you can ignore this message; your password stays the same.";

        PasswordTokenRepository repo = new PasswordTokenRepository(tx);

        return repo.deleteUnused(userId, PURPOSE_RESET)
                .compose(n -> repo.insert(userId, PURPOSE_RESET, SessionTokens.hash(token), settings.resetTtlSeconds(), null))
                .compose(n -> new OutboxRepository(tx).enqueue("email", user.getString("email"),
                        "Reset your Pulse password", body,
                        new JsonObject().put("kind", "auth.reset").put("userId", userId)))
                .compose(v -> AuditService.record(tx, AuthService.entry("auth.password.reset_requested", "success", userId, sourceIp)
                        .put("actorRole", user.getString("role"))));
    }

    // ------------------------------------------------------------------ set password from a link

    /**
     * Sets a password from an invite or reset link. The policy is checked before the link is used,
     * so a weak password does not burn it. On success the link is used up, an Invited user becomes
     * Active, every session of the user ends, the browser is signed in and the event is audited.
     *
     * @param previousToken the session cookie the browser sent, if any; it is ended (rotation)
     * @return {@code {token, csrfToken, user}} like sign-in
     */
    public Future<JsonObject> setPassword(String token, String password, String sourceIp, String previousToken)
    {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH)
        {
            return Future.failedFuture(ApiException.unprocessable("link_invalid", MSG_LINK_UNKNOWN));
        }

        if (password == null || password.isEmpty())
        {
            return Future.failedFuture(ApiException.badRequest("invalid_password", MSG_ENTER_PASSWORD));
        }

        Buffer tokenHash = SessionTokens.hash(token);

        return new PasswordTokenRepository(sql).byTokenHash(tokenHash, false)
                .compose(link ->
                {
                    ApiException unusable = unusable(link);

                    if (unusable != null)
                    {
                        return Future.failedFuture(unusable);
                    }

                    String violation = PasswordPolicy.violation(password, link.getString("email"), link.getString("name"));

                    if (violation != null)
                    {
                        return Future.failedFuture(ApiException.unprocessable("password_policy", violation));
                    }

                    return PasswordHasher.hash(vertx, password)
                            .compose(hash -> sql.inTransaction(tx -> applyLink(tx, tokenHash, hash, sourceIp, previousToken)));
                });
    }

    private Future<JsonObject> applyLink(SqlExecutor tx, Buffer tokenHash, String passwordHash, String sourceIp,
                                         String previousToken)
    {
        PasswordTokenRepository tokens = new PasswordTokenRepository(tx);

        // Locked re-read: a concurrent submission of the same link waits here and then sees it used.
        return tokens.byTokenHash(tokenHash, true).compose(link ->
        {
            ApiException unusable = unusable(link);

            if (unusable != null)
            {
                return Future.failedFuture(unusable);
            }

            String userId = link.getString("user_id");
            String purpose = link.getString("purpose");

            return tokens.markUsed(link.getString("token_id"))
                    .compose(n -> tokens.setPassword(userId, passwordHash))
                    .compose(n -> AuthService.revokeUser(tx, userId))
                    .compose(ended -> new AuthRepository(tx).userById(userId)
                            .compose(user ->
                            {
                                if (user.getString("role_id") == null)
                                {
                                    return Future.failedFuture(ApiException.forbidden("role_missing", AuthService.MSG_ROLE_MISSING));
                                }

                                return auth.openSession(tx, user, sourceIp, previousToken)
                                        .compose(session -> AuditService.record(tx, AuthService.entry(
                                                                PURPOSE_INVITE.equals(purpose) ? "auth.invite.accepted" : "auth.password.reset",
                                                                "success", userId, sourceIp)
                                                        .put("actorRole", user.getString("role"))
                                                        .put("summary", new JsonObject().put("sessionsEnded", ended)))
                                                .map(session));
                            }));
        });
    }

    /** Why a link cannot be used, or null when it can. Deactivated users get the same answer as a used link. */
    private static ApiException unusable(JsonObject link)
    {
        if (link == null)
        {
            return ApiException.unprocessable("link_invalid", MSG_LINK_UNKNOWN);
        }

        if (!link.getBoolean("usable") || "Deactivated".equals(link.getString("status")))
        {
            return ApiException.unprocessable("link_invalid", PURPOSE_INVITE.equals(link.getString("purpose"))
                    ? MSG_INVITE_LINK_INVALID
                    : MSG_RESET_LINK_INVALID);
        }

        return null;
    }

    // ------------------------------------------------------------------ change while signed in

    /**
     * Changes the signed-in user's password after checking the current one. Every other session of
     * the user ends; the current one stays. Audited, including a refused attempt.
     *
     * @param user          the session user ({@code ctx.get(SessionHandler.USER)})
     * @param sessionToken  the current session cookie value, which is kept
     */
    public Future<Void> changePassword(JsonObject user, String sessionToken, String currentPassword, String newPassword,
                                       String sourceIp)
    {
        if (currentPassword == null || currentPassword.isEmpty() || currentPassword.length() > 200)
        {
            return Future.failedFuture(ApiException.badRequest("invalid_password", "Enter your current password."));
        }

        if (newPassword == null || newPassword.isEmpty())
        {
            return Future.failedFuture(ApiException.badRequest("invalid_password", MSG_ENTER_PASSWORD));
        }

        String userId = user.getString("id");

        return new PasswordTokenRepository(sql).passwordHash(userId)
                .compose(stored -> PasswordHasher.verify(vertx, currentPassword, stored))
                .compose(ok ->
                {
                    if (!ok)
                    {
                        return AuditService.record(sql, AuthService.entry("auth.password.changed", "refused", userId, sourceIp)
                                        .put("actorRole", user.getString("role"))
                                        .put("summary", new JsonObject().put("reason", "current_password_wrong")))
                                .compose(v -> Future.failedFuture(
                                        ApiException.unprocessable("current_password_wrong", MSG_CURRENT_WRONG)));
                    }

                    String violation = PasswordPolicy.violation(newPassword, user.getString("email"), user.getString("name"));

                    if (violation != null)
                    {
                        return Future.failedFuture(ApiException.unprocessable("password_policy", violation));
                    }

                    return PasswordHasher.hash(vertx, newPassword).compose(hash -> sql.inTransaction(tx ->
                            new PasswordTokenRepository(tx).setPassword(userId, hash)
                                    .compose(n -> new AuthRepository(tx).deleteOtherSessionsOfUser(userId,
                                            SessionTokens.hash(sessionToken)))
                                    .compose(ended -> AuditService.record(tx,
                                            AuthService.entry("auth.password.changed", "success", userId, sourceIp)
                                                    .put("actorRole", user.getString("role"))
                                                    .put("summary", new JsonObject().put("sessionsEnded", ended))))));
                });
    }

    /** "7 days", "1 hour", "30 minutes" for the link e-mails. */
    static String duration(int seconds)
    {
        if (seconds % 86_400 == 0)
        {
            return plural(seconds / 86_400, "day");
        }

        if (seconds % 3_600 == 0)
        {
            return plural(seconds / 3_600, "hour");
        }

        return plural(Math.max(1, seconds / 60), "minute");
    }

    private static String plural(int n, String unit)
    {
        return n + " " + unit + (n == 1 ? "" : "s");
    }
}
