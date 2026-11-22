package com.pulse.platform.ingest;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL of {@link IngestService}: the catalogue of ingest tables and the idempotent upsert by
 * {@code (source, external_id)}. Table, column and type names are never taken from a connector as-is:
 * {@link IngestService} checks them against {@link #columns()}, which reads them from PostgreSQL itself,
 * and every value travels as a parameter.
 */
final class IngestRepository extends BaseRepository
{
    IngestRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** Every column of every table in the current schema: {@code {table_name, column_name, type}}. */
    Future<JsonArray> columns()
    {
        return queryList("SELECT c.relname AS table_name, a.attname AS column_name, "
                + "format_type(a.atttypid, a.atttypmod) AS type "
                + "FROM pg_attribute a "
                + "JOIN pg_class c ON c.oid = a.attrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p') "
                + "AND a.attnum > 0 AND NOT a.attisdropped "
                + "ORDER BY c.relname, a.attnum", Tuple.tuple());
    }

    /**
     * One column value of an upsert: a plain value cast from text to {@code type}, or a reference to the
     * id of the row of {@code refTable} with ({@code refSource}, {@code refExternalId}).
     */
    record Value(String column, String type, String text, String refTable, String refSource, String refExternalId)
    {
        static Value plain(String column, String type, String text)
        {
            return new Value(column, type, text, null, null, null);
        }

        static Value ref(String column, String refTable, String refSource, String refExternalId)
        {
            return new Value(column, null, null, refTable, refSource, refExternalId);
        }
    }

    /**
     * Inserts the row or, when ({@code source}, {@code externalId}) exists, updates the given columns
     * and {@code synced_at}. {@code updated_at} and {@code version} move only when a value changed.
     * Resolves to {@code {inserted, changed}}.
     */
    Future<JsonObject> upsert(String table, List<Value> values, String source, String externalId,
                              boolean hasUpdatedAt, boolean hasVersion)
    {
        List<Object> params = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        List<String> exprs = new ArrayList<>();

        for (Value v : values)
        {
            columns.add(quote(v.column()));

            if (v.refTable() != null)
            {
                params.add(v.refSource());
                params.add(v.refExternalId());
                exprs.add("(SELECT r.id FROM " + quote(v.refTable()) + " r WHERE r.source = $" + (params.size() - 1)
                        + " AND r.external_id = $" + params.size() + ")");
            }
            else
            {
                params.add(v.text());
                exprs.add("$" + params.size() + "::text::" + v.type());
            }
        }

        params.add(source);
        columns.add("source");
        exprs.add("$" + params.size());

        params.add(externalId);
        columns.add("external_id");
        exprs.add("$" + params.size());

        columns.add("synced_at");
        exprs.add("now()");

        List<String> dataColumns = values.stream().map(v -> quote(v.column())).toList();

        String differs = dataColumns.isEmpty() ? "false"
                : "ROW(" + String.join(", ", dataColumns.stream().map(c -> "t." + c + "::text").toList()) + ")"
                + " IS DISTINCT FROM ROW("
                + String.join(", ", dataColumns.stream().map(c -> "EXCLUDED." + c + "::text").toList()) + ")";

        List<String> sets = new ArrayList<>();

        dataColumns.forEach(c -> sets.add(c + " = EXCLUDED." + c));

        sets.add("synced_at = EXCLUDED.synced_at");

        if (hasUpdatedAt)
        {
            sets.add("updated_at = CASE WHEN " + differs + " THEN now() ELSE t.updated_at END");
        }

        if (hasVersion)
        {
            sets.add("version = CASE WHEN " + differs + " THEN t.version + 1 ELSE t.version END");
        }

        String changed = hasUpdatedAt ? "(t.updated_at = now())" : "true";

        String statement = "INSERT INTO " + quote(table) + " AS t (" + String.join(", ", columns) + ") "
                + "VALUES (" + String.join(", ", exprs) + ") "
                + "ON CONFLICT (source, external_id) DO UPDATE SET " + String.join(", ", sets) + " "
                + "RETURNING (xmax = 0) AS inserted, " + changed + " AS changed";

        return queryOne(statement, Tuple.wrap(params));
    }

    private static String quote(String identifier)
    {
        return "\"" + identifier + "\"";
    }
}
