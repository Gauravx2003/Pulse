package com.pulse.platform.ingest;

import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single write path into every table that carries the ingest columns {@code source},
 * {@code external_id} and {@code synced_at} (design.md decision 6, task 5.4). The seed loader and Sync
 * now both hand it the normalized batch of a {@link ConnectorPort}; it upserts each record by
 * ({@code source}, {@code external_id}), stamps {@code synced_at}, and never deletes, so running the same
 * batch twice creates no duplicates and a live source never touches seed rows.
 *
 * <p>Ingest tables are found in the database itself (a table qualifies when it has the three columns),
 * so a track that adds the columns to its table can ingest into it with no change here. A record field
 * that is not a column of its table, a write to a column the service owns, or a table without the
 * ingest columns fails the whole batch, which runs in one transaction.
 */
public final class IngestService
{
    /** Key of a reference value: {@code {"$ref": "people", "external_id": "ps"}}. */
    public static final String REF = "$ref";

    /** Columns the service itself sets; a record may not write them. */
    static final Set<String> OWNED = Set.of("id", "source", "external_id", "synced_at", "created_at", "updated_at",
            "version");

    private static final Set<String> INGEST_COLUMNS = Set.of("source", "external_id", "synced_at");

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    private static final Pattern TYPE = Pattern.compile("[a-z0-9_ ,()\\[\\]]+");

    private static final int MAX_TEXT = 255;

    private final SqlExecutor sql;

    public IngestService(SqlExecutor sql)
    {
        this.sql = sql;
    }

    /**
     * Upserts a normalized batch in one transaction. Resolves to
     * {@code {source, total, entities: {table: {inserted, updated, unchanged}}}}.
     */
    public Future<JsonObject> ingest(JsonObject batch)
    {
        String source;
        JsonArray groups;

        try
        {
            source = text(batch, "source");
            groups = batch.getJsonArray("entities", new JsonArray());
        }
        catch (RuntimeException e)
        {
            return Future.failedFuture(e);
        }

        return sql.inTransaction(tx ->
        {
            IngestRepository repo = new IngestRepository(tx);

            return repo.columns().compose(rows ->
            {
                Map<String, Map<String, String>> tables = ingestTables(rows);
                JsonObject counts = new JsonObject();
                Future<Void> chain = Future.succeededFuture();

                for (Object g : groups)
                {
                    JsonObject group = (JsonObject) g;

                    chain = chain.compose(v -> ingestGroup(repo, tables, source, group, counts));
                }

                return chain.map(v -> new JsonObject()
                        .put("source", source)
                        .put("total", total(counts))
                        .put("entities", counts));
            });
        });
    }

    private Future<Void> ingestGroup(IngestRepository repo, Map<String, Map<String, String>> tables, String source,
                                     JsonObject group, JsonObject counts)
    {
        String table = group.getString("entity");
        Map<String, String> columns = table == null ? null : tables.get(table);

        if (columns == null)
        {
            return Future.failedFuture(new IllegalArgumentException("'" + table
                    + "' is not an ingest table (it needs the columns source, external_id and synced_at)"));
        }

        JsonObject tally = counts.getJsonObject(table);

        if (tally == null)
        {
            tally = new JsonObject().put("inserted", 0).put("updated", 0).put("unchanged", 0);
            counts.put(table, tally);
        }

        JsonObject t = tally;
        Future<Void> chain = Future.succeededFuture();

        for (Object r : group.getJsonArray("records", new JsonArray()))
        {
            chain = chain.compose(v ->
            {
                List<IngestRepository.Value> values;
                String externalId;

                try
                {
                    JsonObject record = (JsonObject) r;

                    externalId = text(record, "external_id");
                    values = values(table, columns, tables, source, record);
                }
                catch (RuntimeException e)
                {
                    return Future.failedFuture(e);
                }

                return repo.upsert(table, values, source, externalId, columns.containsKey("updated_at"),
                                columns.containsKey("version"))
                        .map(row ->
                        {
                            String key = Boolean.TRUE.equals(row.getBoolean("inserted")) ? "inserted"
                                    : Boolean.TRUE.equals(row.getBoolean("changed")) ? "updated" : "unchanged";

                            t.put(key, t.getInteger(key) + 1);

                            return null;
                        });
            });
        }

        return chain;
    }

    private static List<IngestRepository.Value> values(String table, Map<String, String> columns,
                                                       Map<String, Map<String, String>> tables, String source,
                                                       JsonObject record)
    {
        List<IngestRepository.Value> out = new ArrayList<>();

        for (Map.Entry<String, Object> field : record)
        {
            String column = field.getKey();

            if ("external_id".equals(column))
            {
                continue;
            }

            if (OWNED.contains(column))
            {
                throw new IllegalArgumentException("Column " + table + "." + column + " is set by IngestService");
            }

            String type = columns.get(column);

            if (type == null || !IDENTIFIER.matcher(column).matches())
            {
                throw new IllegalArgumentException("Unknown column " + table + "." + column);
            }

            Object value = field.getValue();

            if (value instanceof JsonObject ref && ref.containsKey(REF))
            {
                String refTable = ref.getString(REF);

                if (refTable == null || !tables.containsKey(refTable))
                {
                    throw new IllegalArgumentException("Reference from " + table + "." + column
                            + " to '" + refTable + "', which is not an ingest table");
                }

                out.add(IngestRepository.Value.ref(column, refTable, ref.getString("source", source),
                        ref.getString("external_id")));
            }
            else
            {
                if (!TYPE.matcher(type).matches())
                {
                    throw new IllegalArgumentException("Unsupported type " + type + " of " + table + "." + column);
                }

                out.add(IngestRepository.Value.plain(column, type, asText(type, value)));
            }
        }

        return out;
    }

    /** The value as the text PostgreSQL casts to {@code type}. */
    static String asText(String type, Object value)
    {
        if (value == null)
        {
            return null;
        }

        if (type.startsWith("json"))
        {
            return Json.encode(value);
        }

        if (type.endsWith("[]"))
        {
            JsonArray items = value instanceof JsonArray a ? a : new JsonArray().add(value);
            List<String> parts = new ArrayList<>();

            for (Object item : items)
            {
                parts.add(item == null ? "NULL"
                        : "\"" + String.valueOf(item).replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            }

            return "{" + String.join(",", parts) + "}";
        }

        if (value instanceof JsonObject || value instanceof JsonArray)
        {
            throw new IllegalArgumentException("A " + type + " column takes a single value, not " + value);
        }

        return String.valueOf(value);
    }

    /** Ingest table name to its columns and their types. */
    static Map<String, Map<String, String>> ingestTables(JsonArray rows)
    {
        Map<String, Map<String, String>> all = new HashMap<>();

        for (Object o : rows)
        {
            JsonObject row = (JsonObject) o;

            all.computeIfAbsent(row.getString("table_name"), k -> new LinkedHashMap<>())
                    .put(row.getString("column_name"), row.getString("type"));
        }

        all.values().removeIf(columns -> !columns.keySet().containsAll(INGEST_COLUMNS));

        return all;
    }

    private static int total(JsonObject counts)
    {
        int n = 0;

        for (String table : counts.fieldNames())
        {
            JsonObject c = counts.getJsonObject(table);

            n += c.getInteger("inserted") + c.getInteger("updated") + c.getInteger("unchanged");
        }

        return n;
    }

    private static String text(JsonObject o, String key)
    {
        Object v = o.getValue(key);

        if (v == null || String.valueOf(v).isBlank())
        {
            throw new IllegalArgumentException("Missing " + key + " in ingest record");
        }

        String s = String.valueOf(v);

        if (s.length() > MAX_TEXT)
        {
            throw new IllegalArgumentException(key + " longer than " + MAX_TEXT + " characters");
        }

        return s;
    }
}
