package com.pulse.platform.audit;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * The audit log endpoints (task 3.7, spec audit-log "Access to the audit log"), behind
 * {@code audit.read}. Every read and export is itself audited before the rows are returned.
 *
 * <ul>
 *   <li>{@code GET /api/v1/audit}: paged list, newest first; filters {@code actor}, {@code action},
 *       {@code targetType}, {@code targetId}, {@code outcome}, {@code from}, {@code to}</li>
 *   <li>{@code GET /api/v1/audit/export}: the same filters as CSV, at most 10,000 rows
 *       ({@code X-Export-Truncated: true} when cut)</li>
 * </ul>
 */
public final class AuditRoutes
{
    public static final String PERMISSION = "audit.read";

    private static final String[] CSV_COLUMNS = {"occurredAt", "actorId", "actorName", "actorRole", "action",
            "targetType", "targetId", "outcome", "sourceIp", "requestId", "summary"};

    private AuditRoutes()
    {
    }

    public static void mount(Router router, String apiPrefix, PolicyService policy, SqlExecutor sql)
    {
        router.get(apiPrefix + "/audit").handler(policy.requires(PERMISSION)).handler(ctx ->
                audited(ctx, sql, AuditEvents.AUDIT_READ)
                        .compose(v -> new AuditRepository(sql).list(ctx.queryParams()))
                        .onSuccess(ctx::json)
                        .onFailure(ctx::fail));

        router.get(apiPrefix + "/audit/export").handler(policy.requires(PERMISSION)).handler(ctx ->
                audited(ctx, sql, AuditEvents.AUDIT_EXPORT)
                        .compose(v -> new AuditRepository(sql).exportRows(ctx.queryParams()))
                        .onSuccess(result -> ctx.response()
                                .putHeader("Content-Type", "text/csv; charset=utf-8")
                                .putHeader("Content-Disposition", "attachment; filename=\"audit-log.csv\"")
                                .putHeader("X-Export-Truncated", String.valueOf(result.getBoolean("truncated")))
                                .end(csv(result.getJsonArray("items"))))
                        .onFailure(ctx::fail));
    }

    /** Records the read with the filters used, outside any transaction, before any row is returned. */
    private static Future<Void> audited(RoutingContext ctx, SqlExecutor sql, String action)
    {
        JsonObject filters = new JsonObject();
        MultiMap params = ctx.queryParams();

        for (String name : params.names())
        {
            filters.put(name, String.join(",", params.getAll(name)));
        }

        return AuditService.record(sql, AuditService.entry(PolicyService.user(ctx), action, "audit_log", null,
                new JsonObject().put("filters", filters)));
    }

    static String csv(JsonArray rows)
    {
        StringBuilder out = new StringBuilder(String.join(",", CSV_COLUMNS)).append("\r\n");

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            for (int i = 0; i < CSV_COLUMNS.length; i++)
            {
                Object value = row.getValue(CSV_COLUMNS[i]);

                if (i > 0)
                {
                    out.append(',');
                }

                out.append(cell(value == null ? "" : String.valueOf(value)));
            }

            out.append("\r\n");
        }

        return out.toString();
    }

    /** RFC 4180 quoting; a leading = + - @ is prefixed with ' so a spreadsheet never runs it as a formula. */
    private static String cell(String value)
    {
        String v = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;

        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r"))
        {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }

        return v;
    }
}
