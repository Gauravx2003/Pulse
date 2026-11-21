package com.pulse.platform.audit;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.ListQuery;
import com.pulse.platform.db.QueryValidationException;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Reads of the audit log (task 3.7). The actor's name comes from the live {@code users} row, so once a
 * user's personal data is deleted their entries show only the opaque actor id (spec audit-log "Audit
 * retention"); the log itself never stores names or emails.
 */
final class AuditRepository extends BaseRepository
{
    private static final String SELECT = "SELECT a.id, a.occurred_at AS \"occurredAt\", a.actor_id AS \"actorId\", "
            + "u.name AS \"actorName\", a.actor_role AS \"actorRole\", a.action, a.target_type AS \"targetType\", "
            + "a.target_id AS \"targetId\", a.outcome, a.source_ip AS \"sourceIp\", a.request_id AS \"requestId\", "
            + "a.summary FROM audit_log a LEFT JOIN users u ON u.id = a.actor_id";

    AuditRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** {@code {items, page, pageSize, total, pageSizeCapped}}, newest first. */
    Future<JsonObject> list(MultiMap params)
    {
        ListQuery query;

        try
        {
            query = query(params);
        }
        catch (QueryValidationException e)
        {
            return Future.failedFuture(e);
        }

        return page(query, withoutDates(params));
    }

    /** {@code {items, truncated}}: at most {@link #MAX_EXPORT_ROWS} entries, newest first. */
    Future<JsonObject> exportRows(MultiMap params)
    {
        ListQuery query;

        try
        {
            query = query(params);
        }
        catch (QueryValidationException e)
        {
            return Future.failedFuture(e);
        }

        return export(query, withoutDates(params));
    }

    /**
     * Filters: {@code actor} (user id), {@code action}, {@code targetType}, {@code targetId},
     * {@code outcome}, and {@code from} / {@code to} (inclusive UTC dates, YYYY-MM-DD).
     */
    private static ListQuery query(MultiMap params)
    {
        ListQuery q = ListQuery.from(SELECT)
                .filter("actor", "a.actor_id")
                .filter("action", "a.action")
                .filter("targetType", "a.target_type")
                .filter("targetId", "a.target_id")
                .filter("outcome", "a.outcome")
                .sort("occurredAt", "a.occurred_at")
                .sort("action", "a.action")
                .defaultSort("-occurredAt")
                .tieBreaker("a.id");

        LocalDate from = date(params, "from");
        LocalDate to = date(params, "to");

        if (from != null)
        {
            q.where("a.occurred_at >= ?", from.atStartOfDay().atOffset(ZoneOffset.UTC));
        }

        if (to != null)
        {
            OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

            q.where("a.occurred_at < ?", end);
        }

        return q;
    }

    private static LocalDate date(MultiMap params, String name)
    {
        String raw = params.get(name);

        if (raw == null || raw.isBlank())
        {
            return null;
        }

        try
        {
            return LocalDate.parse(raw.strip());
        }
        catch (DateTimeParseException e)
        {
            throw new QueryValidationException(name, "'" + name + "' must be a date (YYYY-MM-DD)");
        }
    }

    private static MultiMap withoutDates(MultiMap params)
    {
        MultiMap rest = MultiMap.caseInsensitiveMultiMap().addAll(params);

        rest.remove("from");
        rest.remove("to");

        return rest;
    }
}
