package com.pulse.platform.auth;

import com.pulse.platform.audit.AuditService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Sign-in, sign-out and session checks (tasks 3.1 and 3.2, spec platform/auth-session).
 *
 * <p>Order of checks on sign-in: per-IP throttle (429), per-email lockout (429, also for unknown
 * emails), password (bcrypt, also run for unknown emails), then account state: deactivated and
 * missing-role are reported only after a correct password. Wrong email and wrong password give the
 * same message.
 */
public class AuthService
{
    public static final String MSG_INVALID = "Email or password is incorrect.";

    public static final String MSG_DEACTIVATED = "This account is deactivated. Contact HR.";

    public static final String MSG_ROLE_MISSING = "Your role no longer exists. Contact HR.";

    public static final String MSG_LOCKED = "Too many failed sign-ins. Try again in 15 minutes.";

    public static final String MSG_THROTTLED = "Too many sign-in attempts from this network. Try again later.";

    public static final String MSG_SIGN_IN = "Sign in to continue.";

    private final Vertx vertx;

    private final SqlExecutor sql;

    private final AuthSettings settings;

    private final Set<String> catalogue;

    /**
     * @param catalogue every permission registered by the platform and the modules; the built-in
     *                  Admin role always holds all of them (spec section 2, "Admin always keeps
     *                  every permission").
     */
    public AuthService(Vertx vertx, SqlExecutor sql, AuthSettings settings, Collection<String> catalogue)
    {
        this.vertx = vertx;
        this.sql = sql;
        this.settings = settings;
        this.catalogue = new LinkedHashSet<>(catalogue);
    }

    public AuthSettings settings()
    {
        return settings;
    }

    /**
     * Signs in. Resolves to {@code {token, csrfToken, user}} where {@code user} is the session user
     * (see {@link #sessionUser}); fails with {@link ApiException}.
     *
     * @param previousToken the session cookie the browser sent, if any; it is ended (rotation)
     */
    public Future<JsonObject> signIn(String email, String password, String sourceIp, String previousToken)
    {
        if (email == null || email.isBlank() || password == null || password.isEmpty()
                || email.length() > 254 || password.length() > 200)
        {
            return Future.failedFuture(ApiException.badRequest("invalid_sign_in", "Enter your work email and password."));
        }

        String typedEmail = email.strip();

        AuthRepository repo = new AuthRepository(sql);

        return repo.countIpAttempt(sourceIp, settings.ipWindowSeconds())
                .compose(attempts ->
                {
                    if (attempts > settings.ipLimit())
                    {
                        return Future.failedFuture(ApiException.tooManyRequests("rate_limited", MSG_THROTTLED));
                    }

                    return repo.lockedUntil(typedEmail);
                })
                .compose(lockedUntil ->
                {
                    if (lockedUntil != null)
                    {
                        return Future.failedFuture(ApiException.tooManyRequests("sign_in_locked", MSG_LOCKED));
                    }

                    return repo.userByEmail(typedEmail);
                })
                .compose(user -> PasswordHasher.verify(vertx, password, user == null ? null : user.getString("password_hash"))
                        .compose(ok -> ok
                                ? accept(user, sourceIp, previousToken)
                                : reject(user, typedEmail, sourceIp)));
    }

    private Future<JsonObject> reject(JsonObject user, String typedEmail, String sourceIp)
    {
        AuthRepository repo = new AuthRepository(sql);

        String userId = user == null ? null : user.getString("id");

        return repo.recordFailure(typedEmail, settings.maxFailures(), settings.failureWindowSeconds(), settings.lockSeconds())
                .compose(result -> sql.inTransaction(tx ->
                {
                    Future<Void> failed = AuditService.record(tx, entry("auth.sign_in.failed", "refused", userId, sourceIp)
                            .put("summary", new JsonObject().put("reason", "invalid_credentials")));

                    if (!result.getBoolean("locked"))
                    {
                        return failed;
                    }

                    return failed.compose(v -> AuditService.record(tx, entry("auth.lockout", "refused", userId, sourceIp)
                            .put("summary", new JsonObject()
                                    .put("failures", result.getInteger("failures"))
                                    .put("lockSeconds", settings.lockSeconds()))));
                }))
                .compose(v -> Future.failedFuture(ApiException.unauthorized("invalid_credentials", MSG_INVALID)));
    }

    private Future<JsonObject> accept(JsonObject user, String sourceIp, String previousToken)
    {
        String userId = user.getString("id");

        if (!"Active".equals(user.getString("status")))
        {
            return AuditService.record(sql, entry("auth.sign_in.failed", "refused", userId, sourceIp)
                            .put("summary", new JsonObject().put("reason", "deactivated")))
                    .compose(v -> Future.failedFuture(ApiException.forbidden("account_deactivated", MSG_DEACTIVATED)));
        }

        if (user.getString("role_id") == null)
        {
            return AuditService.record(sql, entry("auth.sign_in.failed", "refused", userId, sourceIp)
                            .put("summary", new JsonObject().put("reason", "role_missing")))
                    .compose(v -> Future.failedFuture(ApiException.forbidden("role_missing", MSG_ROLE_MISSING)));
        }

        return sql.inTransaction(tx -> openSession(tx, user, sourceIp, previousToken)
                .compose(session -> AuditService.record(tx, entry("auth.sign_in", "success", userId, sourceIp)
                                .put("actorRole", user.getString("role")))
                        .map(session)));
    }

    /**
     * Starts a session for {@code user} (a row of {@link AuthRepository#userByEmail} or
     * {@link AuthRepository#userById}) inside {@code tx}: ends the browser's previous session
     * (rotation), clears the email's failed sign-ins and records the login time. Used by sign-in and
     * by setting a password from an invite or reset link (task 3.3).
     *
     * @return {@code {token, csrfToken, user}}
     */
    Future<JsonObject> openSession(SqlExecutor tx, JsonObject user, String sourceIp, String previousToken)
    {
        String userId = user.getString("id");
        String token = SessionTokens.newToken();
        String csrfToken = SessionTokens.newToken();

        AuthRepository repo = new AuthRepository(tx);

        Future<Integer> rotated = previousToken == null
                ? Future.succeededFuture(0)
                : repo.deleteSession(SessionTokens.hash(previousToken));

        return rotated
                .compose(n -> repo.clearFailures(user.getString("email")))
                .compose(n -> repo.insertSession(SessionTokens.hash(token), userId, csrfToken,
                        settings.sessionTtlSeconds(), sourceIp))
                .compose(n -> repo.touchLastLogin(userId))
                .map(n -> new JsonObject()
                        .put("token", token)
                        .put("csrfToken", csrfToken)
                        .put("user", sessionUser(user)));
    }

    /**
     * Resolves a session cookie to its user, or fails with 401. A missing, unknown or expired session,
     * a deactivated user or a user whose role was deleted all end in 401; the last two also end every
     * session of that user (revocation on deactivation).
     *
     * @return {@code {user, csrfToken}}
     */
    public Future<JsonObject> authenticate(String token)
    {
        if (token == null || token.isBlank())
        {
            return Future.failedFuture(ApiException.unauthorized("unauthenticated", MSG_SIGN_IN));
        }

        Buffer tokenHash = SessionTokens.hash(token);

        AuthRepository repo = new AuthRepository(sql);

        return repo.sessionByTokenHash(tokenHash).compose(row ->
        {
            if (row == null)
            {
                return Future.failedFuture(ApiException.unauthorized("unauthenticated", MSG_SIGN_IN));
            }

            if (row.getBoolean("expired"))
            {
                return repo.deleteSession(tokenHash)
                        .compose(n -> Future.failedFuture(ApiException.unauthorized("session_expired", MSG_SIGN_IN)));
            }

            if (!"Active".equals(row.getString("status")) || row.getString("role_id") == null)
            {
                return repo.deleteSessionsOfUser(row.getString("id"))
                        .compose(n -> Future.failedFuture(ApiException.unauthorized("session_revoked", MSG_SIGN_IN)));
            }

            return Future.succeededFuture(new JsonObject()
                    .put("user", sessionUser(row))
                    .put("csrfToken", row.getString("csrf_token")));
        });
    }

    /** Ends the session of {@code token}; audited. */
    public Future<Void> signOut(String token, JsonObject user, String sourceIp)
    {
        return sql.inTransaction(tx -> new AuthRepository(tx).deleteSession(SessionTokens.hash(token))
                .compose(n -> AuditService.record(tx, entry("auth.sign_out", "success", user.getString("id"), sourceIp)
                        .put("actorRole", user.getString("role")))));
    }

    /** Ends every session of a user: deactivation, deletion, password change (tasks 3.3, 5.1). */
    public static Future<Integer> revokeUser(SqlExecutor tx, String userId)
    {
        return new AuthRepository(tx).deleteSessionsOfUser(userId);
    }

    /** Ends every session of the holders of a role whose permissions changed (task 5.1). */
    public static Future<Integer> revokeRole(SqlExecutor tx, String roleId)
    {
        return new AuthRepository(tx).deleteSessionsOfRole(roleId);
    }

    /** {@code {id, email, name, role, roleId, personId, permissions, landing}} without the password hash. */
    JsonObject sessionUser(JsonObject row)
    {
        Set<String> permissions = new LinkedHashSet<>();

        row.getJsonArray("permissions", new JsonArray()).forEach(p -> permissions.add(String.valueOf(p)));

        if ("Admin".equals(row.getString("role")))
        {
            permissions.addAll(catalogue);
        }

        return new JsonObject()
                .put("id", row.getString("id"))
                .put("email", row.getString("email"))
                .put("name", row.getString("name"))
                .put("role", row.getString("role"))
                .put("roleId", row.getString("role_id"))
                .put("personId", row.getString("person_id"))
                .put("permissions", new JsonArray(permissions.stream().sorted().toList()))
                .put("landing", permissions.contains("team.view") ? "/actions" : "/overview");
    }

    /** The permission catalogue keys; the built-in Admin role holds all of them. */
    public Set<String> catalogue()
    {
        return Set.copyOf(catalogue);
    }

    static JsonObject entry(String action, String outcome, String userId, String sourceIp)
    {
        return new JsonObject()
                .put("action", action)
                .put("outcome", outcome)
                .put("actorId", userId)
                .put("targetType", userId == null ? null : "user")
                .put("targetId", userId)
                .put("sourceIp", sourceIp);
    }
}
