package com.pulse.platform.audit;

import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * Writes audit entries (spec platform/audit-log). Always pass the executor of the caller's
 * transaction so the entry commits or rolls back with the action. Entries never contain passwords,
 * tokens, session ids or free text. Task 3.7 adds the event catalogue and the read endpoint.
 *
 * <p>Entry fields: {@code actorId}, {@code actorRole}, {@code action}, {@code targetType},
 * {@code targetId}, {@code outcome} ("success" or "refused"), {@code sourceIp}, {@code requestId},
 * {@code summary} (JsonObject).
 */
public final class AuditService
{
    private AuditService()
    {
    }

    public static Future<Void> record(SqlExecutor tx, JsonObject entry)
    {
        String actorId = entry.getString("actorId");

        return tx.execute("INSERT INTO audit_log (actor_id, actor_role, action, target_type, target_id, outcome, "
                                + "source_ip, request_id, summary) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)",
                        Tuple.of(actorId == null ? null : UUID.fromString(actorId),
                                entry.getString("actorRole"),
                                entry.getString("action"),
                                entry.getString("targetType"),
                                entry.getString("targetId"),
                                entry.getString("outcome", "success"),
                                entry.getString("sourceIp"),
                                entry.getString("requestId"),
                                entry.getJsonObject("summary", new JsonObject())))
                .mapEmpty();
    }
}
