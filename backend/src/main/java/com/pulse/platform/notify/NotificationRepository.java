package com.pulse.platform.notify;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/** SQL of the {@code notifications} table (V100) for {@link NotificationService}. Every read is per recipient. */
final class NotificationRepository extends BaseRepository
{
    NotificationRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** The recipient as a session-like user ({@code id, role, status, person_id, permissions}); null when missing or deleted. */
    Future<JsonObject> recipient(UUID userId)
    {
        return queryOne("SELECT u.id, u.status, r.name AS role, "
                        + "(SELECT pe.id FROM people pe WHERE pe.user_id = u.id) AS person_id, "
                        + "COALESCE((SELECT array_agg(p.permission ORDER BY p.permission) FROM role_permissions p "
                        + "WHERE p.role_id = r.id), '{}') AS permissions "
                        + "FROM users u LEFT JOIN roles r ON r.id = u.role_id WHERE u.id = $1 AND u.deleted_at IS NULL",
                Tuple.of(userId));
    }

    Future<JsonObject> insert(UUID userId, String kind, String text, String link)
    {
        return queryOne("INSERT INTO notifications (user_id, kind, text, link) VALUES ($1, $2, $3, $4) "
                        + "RETURNING id, kind, text, link, created_at, read_at",
                Tuple.of(userId, kind, text, link));
    }

    /** The newest {@code limit} notifications of the user, newest first. */
    Future<JsonArray> list(UUID userId, int limit)
    {
        return queryList("SELECT id, kind, text, link, created_at, read_at FROM notifications "
                + "WHERE user_id = $1 ORDER BY created_at DESC, id DESC LIMIT $2", Tuple.of(userId, limit));
    }

    Future<Integer> unread(UUID userId)
    {
        return queryOne("SELECT count(*)::int AS n FROM notifications WHERE user_id = $1 AND read_at IS NULL",
                Tuple.of(userId)).map(row -> row.getInteger("n"));
    }

    /** Marks one of the user's notifications read; 0 when it is not theirs or does not exist. */
    Future<Integer> markRead(UUID userId, UUID id)
    {
        return execute("UPDATE notifications SET read_at = COALESCE(read_at, now()) WHERE id = $1 AND user_id = $2",
                Tuple.of(id, userId));
    }

    Future<Integer> markAllRead(UUID userId)
    {
        return execute("UPDATE notifications SET read_at = now() WHERE user_id = $1 AND read_at IS NULL", Tuple.of(userId));
    }
}
