package com.pulse.modules.work;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The {@code nudges} record (task 8.3) and what sending one reads: the chat tool that is connected, the
 * assignee's address and the sender's recent nudges for the rate limit. Visibility of the issue is
 * checked by the caller through {@link IssueRepository#byKey}.
 */
public class NudgeRepository extends BaseRepository
{

    private static final String UNDEFINED_TABLE = "42P01";

    public NudgeRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * The outbox channel of the connected chat tool, Slack first, then Teams; null when neither is
     * connected or its token has expired (or the integrations module is not installed).
     */
    public Future<String> chatChannel(LocalDate today)
    {
        return queryOne("""
                SELECT CASE tool WHEN 'sl' THEN 'slack' ELSE 'teams' END AS channel
                FROM integration_settings
                WHERE tool IN ('sl', 'ms') AND method IS NOT NULL
                  AND (token_expires_on IS NULL OR token_expires_on >= $1)
                ORDER BY tool = 'sl' DESC
                LIMIT 1""", Tuple.of(today))
                .map(row -> row == null ? null : row.getString("channel"))
                .recover(error -> error instanceof PgException pg && UNDEFINED_TABLE.equals(pg.getSqlState())
                        ? Future.succeededFuture(null)
                        : Future.failedFuture(error));
    }

    /**
     * {@code {name, userId, email}} of a person; the email is null when the person has no sign-in.
     */
    public Future<JsonObject> recipient(UUID personId)
    {
        return queryOne("""
                SELECT p.name, u.id AS "userId", u.email
                FROM people p LEFT JOIN users u ON u.id = p.user_id
                WHERE p.id = $1""", Tuple.of(personId));
    }

    /**
     * Serialises the sender's nudges until the transaction ends, so two requests at once cannot both
     * pass the rate limit.
     */
    public Future<Void> lockSender(UUID senderId)
    {
        return queryOne("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtext($1))) l",
                Tuple.of("nudge:" + senderId))
                .mapEmpty();
    }

    /**
     * Seconds until the sender may nudge this issue again, or null when they may now.
     */
    public Future<Long> issueRetryAfter(UUID issueId, UUID senderId, long windowSeconds)
    {
        return queryOne("""
                SELECT ceil(extract(epoch FROM max(created_at) + make_interval(secs => $3) - now()))::bigint AS "retryAfter"
                FROM nudges
                WHERE issue_id = $1 AND sender_id = $2 AND created_at > now() - make_interval(secs => $3)""",
                Tuple.of(issueId, senderId, (double) windowSeconds))
                .map(row -> row == null ? null : row.getLong("retryAfter"));
    }

    /**
     * Seconds until the sender is under {@code limit} nudges in the window again, or null when they are.
     */
    public Future<Long> senderRetryAfter(UUID senderId, long windowSeconds, int limit)
    {
        return queryOne("""
                SELECT count(*) AS sent,
                       ceil(extract(epoch FROM min(created_at) + make_interval(secs => $2) - now()))::bigint AS "retryAfter"
                FROM nudges
                WHERE sender_id = $1 AND created_at > now() - make_interval(secs => $2)""",
                Tuple.of(senderId, (double) windowSeconds))
                .map(row -> row.getLong("sent") < limit ? null : row.getLong("retryAfter"));
    }

    /**
     * Records a nudge; resolves to {@code {id, sentAt}}.
     */
    public Future<JsonObject> insert(UUID issueId, UUID senderId, UUID recipientId, String channel)
    {
        return queryOne("""
                INSERT INTO nudges (issue_id, sender_id, recipient_id, channel) VALUES ($1, $2, $3, $4)
                RETURNING id, created_at AS "sentAt"
                """, Tuple.of(issueId, senderId, recipientId, channel));
    }

    /**
     * {@code {count, lastSentAt}} of an issue's nudges by anyone.
     */
    public Future<JsonObject> summary(UUID issueId)
    {
        return queryOne("""
                SELECT count(*) AS count, max(created_at) AS "lastSentAt"
                FROM nudges WHERE issue_id = $1""", Tuple.of(issueId));
    }
}
