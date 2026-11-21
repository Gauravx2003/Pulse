package com.pulse.platform.audit;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.RequestId;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes audit entries (task 3.7, spec platform/audit-log, design.md decision 7).
 *
 * <p><b>Same transaction.</b> Pass the executor of the caller's transaction, so the entry commits or
 * rolls back with the action:
 *
 * <pre>{@code
 * ctx.sql().inTransaction(tx -> repo(tx).approve(id)
 *         .compose(row -> AuditService.record(tx, AuditService.entry(user, AuditEvents.INCREMENT_APPROVE,
 *                 "proposal", id, new JsonObject().put("before", "Proposed").put("after", "Approved")))));
 * }</pre>
 *
 * A refusal must survive the failed action, so refusals are written with the plain (non-transaction)
 * executor.
 *
 * <p><b>Entry fields:</b> {@code actorId}, {@code actorRole}, {@code action} (an {@link AuditEvents}
 * name), {@code targetType}, {@code targetId}, {@code outcome} ("success" or "refused"),
 * {@code sourceIp}, {@code requestId}, {@code summary} (structured before/after). The id and the UTC
 * time are set by the database. Request id and source IP default to the current request's
 * ({@link RequestId}); the actor role defaults to the actor's current role.
 *
 * <p><b>No secrets or free text.</b> Summary values under secret-looking names (password, token,
 * secret, api key, cookie, session id) and free-text names (comment, notes, text, feedback ...) are replaced
 * by {@value #REDACTED}, and other long strings are cut to {@value #MAX_TEXT} characters. Name the
 * fields that changed instead of their values.
 */
public final class AuditService
{
    public static final String REDACTED = "[redacted]";

    public static final int MAX_TEXT = 200;

    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(password|passwd|token|secret|api_?key|cookie|session_?id|credential_value|private_key).*");

    private static final Set<String> FREE_TEXT_KEYS = Set.of("comment", "comments", "note", "notes", "text",
            "feedback", "body", "answer", "answers", "reflection", "message");

    private static final Set<String> OUTCOMES = Set.of("success", "refused");

    private AuditService()
    {
    }

    /** An entry for a signed-in actor ({@code {id, role}} as on the request) and a target. */
    public static JsonObject entry(JsonObject actor, String action, String targetType, String targetId,
                                   JsonObject summary)
    {
        return new JsonObject()
                .put("actorId", actor == null ? null : actor.getString("id"))
                .put("actorRole", actor == null ? null : actor.getString("role"))
                .put("action", action)
                .put("targetType", targetType)
                .put("targetId", targetId)
                .put("outcome", "success")
                .put("summary", summary == null ? new JsonObject() : summary);
    }

    public static Future<Void> record(SqlExecutor tx, JsonObject entry)
    {
        String action = entry.getString("action");

        if (!AuditEvents.isKnown(action))
        {
            return Future.failedFuture(new IllegalArgumentException("Unknown audit event '" + action
                    + "'. Use an AuditEvents name or declare it in the module descriptor."));
        }

        String outcome = entry.getString("outcome", "success");

        if (!OUTCOMES.contains(outcome))
        {
            return Future.failedFuture(new IllegalArgumentException("Audit outcome must be success or refused"));
        }

        UUID actorId;

        try
        {
            actorId = entry.getString("actorId") == null ? null : UUID.fromString(entry.getString("actorId"));
        }
        catch (IllegalArgumentException e)
        {
            return Future.failedFuture(new IllegalArgumentException("actorId must be a user id"));
        }

        String requestId = entry.getString("requestId", RequestId.current());
        String sourceIp = entry.getString("sourceIp", RequestId.currentIp());

        return tx.execute("INSERT INTO audit_log (actor_id, actor_role, action, target_type, target_id, outcome, "
                                + "source_ip, request_id, summary) VALUES ($1, COALESCE($2, (SELECT r.name FROM users u "
                                + "JOIN roles r ON r.id = u.role_id WHERE u.id = $1)), $3, $4, $5, $6, $7, $8, $9)",
                        Tuple.of(actorId,
                                entry.getString("actorRole"),
                                action,
                                entry.getString("targetType"),
                                entry.getString("targetId"),
                                outcome,
                                sourceIp,
                                requestId,
                                sanitize(entry.getJsonObject("summary", new JsonObject()))))
                .mapEmpty();
    }

    /** A copy of {@code summary} without secret values or free text (see the class comment). */
    public static JsonObject sanitize(JsonObject summary)
    {
        JsonObject out = new JsonObject();

        for (String key : summary.fieldNames())
        {
            Object value = summary.getValue(key);

            if (value != null && (SECRET_KEY.matcher(key).matches() || FREE_TEXT_KEYS.contains(key.toLowerCase())))
            {
                out.put(key, REDACTED);
            }
            else
            {
                out.put(key, clean(value));
            }
        }

        return out;
    }

    private static Object clean(Object value)
    {
        if (value instanceof JsonObject o)
        {
            return sanitize(o);
        }

        if (value instanceof JsonArray a)
        {
            JsonArray out = new JsonArray();

            a.forEach(item -> out.add(clean(item)));

            return out;
        }

        if (value instanceof String s && s.length() > MAX_TEXT)
        {
            return s.substring(0, MAX_TEXT) + "…";
        }

        return value;
    }
}
