package com.pulse.platform.auth;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.OffsetDateTime;
import java.util.UUID;

/** SQL for sign-in, lockout, throttling and sessions (tasks 3.1, 3.2 and 3.3). */
public class AuthRepository extends BaseRepository
{
    private static final String USER_COLUMNS = "u.id, u.email, u.name, u.status, u.password_hash, "
            + "r.id AS role_id, r.name AS role, "
            + "(SELECT pe.id FROM people pe WHERE pe.user_id = u.id) AS person_id, "
            + "COALESCE((SELECT array_agg(p.permission ORDER BY p.permission) FROM role_permissions p "
            + "WHERE p.role_id = r.id), '{}') AS permissions";

    public AuthRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** The account for a typed email (case-insensitive), with role and permissions; null if none. */
    public Future<JsonObject> userByEmail(String email)
    {
        return queryOne("SELECT " + USER_COLUMNS + " FROM users u LEFT JOIN roles r ON r.id = u.role_id "
                + "WHERE lower(u.email) = lower($1)", Tuple.of(email));
    }

    /** The account for a user id, with role and permissions; null if none. */
    public Future<JsonObject> userById(String userId)
    {
        return queryOne("SELECT " + USER_COLUMNS + " FROM users u LEFT JOIN roles r ON r.id = u.role_id "
                + "WHERE u.id = $1", Tuple.of(UUID.fromString(userId)));
    }

    /**
     * Counts one sign-in attempt for {@code ip} in a fixed window and returns the count so far.
     * A new window starts when the old one is older than {@code windowSeconds}.
     */
    public Future<Integer> countIpAttempt(String ip, int windowSeconds)
    {
        return queryOne("INSERT INTO ip_throttle AS t (ip, window_start, attempts) VALUES ($1, now(), 1) "
                        + "ON CONFLICT (ip) DO UPDATE SET "
                        + "attempts = CASE WHEN t.window_start < now() - make_interval(secs => $2::int) THEN 1 "
                        + "ELSE t.attempts + 1 END, "
                        + "window_start = CASE WHEN t.window_start < now() - make_interval(secs => $2::int) THEN now() "
                        + "ELSE t.window_start END "
                        + "RETURNING attempts",
                Tuple.of(ip, windowSeconds))
                .map(row -> row.getInteger("attempts"));
    }

    /** {@code locked_until} for the email when it is in the future, else null. */
    public Future<OffsetDateTime> lockedUntil(String email)
    {
        return queryOne("SELECT locked_until FROM login_attempts WHERE email = lower($1) AND locked_until > now()",
                Tuple.of(email))
                .map(row -> row == null ? null : OffsetDateTime.parse(row.getString("locked_until")));
    }

    /**
     * Records a failed sign-in for the email. Failures older than the window start a new count;
     * reaching {@code maxFailures} sets {@code locked_until}. Returns {failures, locked}.
     */
    public Future<JsonObject> recordFailure(String email, int maxFailures, int windowSeconds, int lockSeconds)
    {
        return queryOne("INSERT INTO login_attempts AS a (email, failures, first_failed_at, updated_at) "
                        + "VALUES (lower($1), 1, now(), now()) "
                        + "ON CONFLICT (email) DO UPDATE SET "
                        + "failures = CASE WHEN a.first_failed_at < now() - make_interval(secs => $2::int) "
                        + "OR a.locked_until IS NOT NULL THEN 1 ELSE a.failures + 1 END, "
                        + "first_failed_at = CASE WHEN a.first_failed_at < now() - make_interval(secs => $2::int) "
                        + "OR a.locked_until IS NOT NULL THEN now() ELSE a.first_failed_at END, "
                        + "locked_until = NULL, updated_at = now() "
                        + "RETURNING failures",
                Tuple.of(email, windowSeconds))
                .compose(row ->
                {
                    int failures = row.getInteger("failures");

                    if (failures < maxFailures)
                    {
                        return Future.succeededFuture(new JsonObject().put("failures", failures).put("locked", false));
                    }

                    return execute("UPDATE login_attempts SET locked_until = now() + make_interval(secs => $2::int), "
                                    + "failures = 0, updated_at = now() WHERE email = lower($1)",
                            Tuple.of(email, lockSeconds))
                            .map(n -> new JsonObject().put("failures", failures).put("locked", true));
                });
    }

    public Future<Integer> clearFailures(String email)
    {
        return execute("DELETE FROM login_attempts WHERE email = lower($1)", Tuple.of(email));
    }

    public Future<Integer> insertSession(Buffer tokenHash, String userId, String csrfToken, int ttlSeconds,
                                         String sourceIp)
    {
        return execute("INSERT INTO sessions (token_hash, user_id, csrf_token, expires_at, source_ip) "
                        + "VALUES ($1, $2, $3, now() + make_interval(secs => $4::int), $5)",
                Tuple.of(tokenHash, UUID.fromString(userId), csrfToken, ttlSeconds, sourceIp));
    }

    public Future<Integer> touchLastLogin(String userId)
    {
        return execute("UPDATE users SET last_login_at = now() WHERE id = $1", Tuple.of(UUID.fromString(userId)));
    }

    /**
     * The session for a token hash with its user, role and permissions, and {@code expired} computed
     * by the database clock; null if there is no such session.
     */
    public Future<JsonObject> sessionByTokenHash(Buffer tokenHash)
    {
        return queryOne("SELECT s.csrf_token, s.expires_at <= now() AS expired, " + USER_COLUMNS
                + " FROM sessions s JOIN users u ON u.id = s.user_id LEFT JOIN roles r ON r.id = u.role_id "
                + "WHERE s.token_hash = $1", Tuple.of(tokenHash));
    }

    public Future<Integer> deleteSession(Buffer tokenHash)
    {
        return execute("DELETE FROM sessions WHERE token_hash = $1", Tuple.of(tokenHash));
    }

    /** Ends every session of a user (deactivation, deletion, password change). */
    public Future<Integer> deleteSessionsOfUser(String userId)
    {
        return execute("DELETE FROM sessions WHERE user_id = $1", Tuple.of(UUID.fromString(userId)));
    }

    /** Ends every session of a user except one (password change keeps the current session). */
    public Future<Integer> deleteOtherSessionsOfUser(String userId, Buffer keepTokenHash)
    {
        return execute("DELETE FROM sessions WHERE user_id = $1 AND token_hash <> $2",
                Tuple.of(UUID.fromString(userId), keepTokenHash));
    }

    /** Ends every session of every user holding a role (role permission change). */
    public Future<Integer> deleteSessionsOfRole(String roleId)
    {
        return execute("DELETE FROM sessions WHERE user_id IN (SELECT id FROM users WHERE role_id = $1)",
                Tuple.of(UUID.fromString(roleId)));
    }
}
