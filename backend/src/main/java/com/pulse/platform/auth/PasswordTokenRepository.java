package com.pulse.platform.auth;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/** SQL for invite and reset links and for setting a password (task 3.3). */
public class PasswordTokenRepository extends BaseRepository
{
    public PasswordTokenRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** {id, email, name, status} of a user; null if none. */
    public Future<JsonObject> userById(String userId)
    {
        return queryOne("SELECT id, email, name, status FROM users WHERE id = $1", Tuple.of(UUID.fromString(userId)));
    }

    /** Removes the user's unused links of {@code purpose}, so only the newest link works. */
    public Future<Integer> deleteUnused(String userId, String purpose)
    {
        return execute("DELETE FROM password_tokens WHERE user_id = $1 AND purpose = $2 AND used_at IS NULL",
                Tuple.of(UUID.fromString(userId), purpose));
    }

    public Future<Integer> insert(String userId, String purpose, Buffer tokenHash, int ttlSeconds, String createdBy)
    {
        return execute("INSERT INTO password_tokens (user_id, purpose, token_hash, expires_at, created_by) "
                        + "VALUES ($1, $2, $3, now() + make_interval(secs => $4::int), $5)",
                Tuple.of(UUID.fromString(userId), purpose, tokenHash, ttlSeconds,
                        createdBy == null ? null : UUID.fromString(createdBy)));
    }

    /**
     * The link for a token hash with its user: {@code {token_id, purpose, usable, user_id, email,
     * name, status}}, where {@code usable} is false once used or expired (database clock); null if
     * there is no such link. With {@code lock} the row is locked until the transaction ends, so two
     * concurrent submissions cannot both use it.
     */
    public Future<JsonObject> byTokenHash(Buffer tokenHash, boolean lock)
    {
        return queryOne("SELECT t.id AS token_id, t.purpose, (t.used_at IS NULL AND t.expires_at > now()) AS usable, "
                + "u.id AS user_id, u.email, u.name, u.status "
                + "FROM password_tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = $1"
                + (lock ? " FOR UPDATE OF t" : ""), Tuple.of(tokenHash));
    }

    public Future<Integer> markUsed(String tokenId)
    {
        return execute("UPDATE password_tokens SET used_at = now() WHERE id = $1 AND used_at IS NULL",
                Tuple.of(UUID.fromString(tokenId)));
    }

    /** Stores a new bcrypt hash; an Invited user becomes Active on first password set. */
    public Future<Integer> setPassword(String userId, String passwordHash)
    {
        return execute("UPDATE users SET password_hash = $2, "
                        + "status = CASE WHEN status = 'Invited' THEN 'Active' ELSE status END, "
                        + "updated_at = now(), version = version + 1 WHERE id = $1",
                Tuple.of(UUID.fromString(userId), passwordHash));
    }

    /** The current bcrypt hash of a user (password change); null if none. */
    public Future<String> passwordHash(String userId)
    {
        return queryOne("SELECT password_hash FROM users WHERE id = $1", Tuple.of(UUID.fromString(userId)))
                .map(row -> row == null ? null : row.getString("password_hash"));
    }
}
