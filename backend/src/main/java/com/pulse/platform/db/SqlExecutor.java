package com.pulse.platform.db;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import io.vertx.sqlclient.data.Numeric;

import java.lang.reflect.Array;
import java.time.temporal.Temporal;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * The one way to talk to PostgreSQL (task 1.6, {@code openspec/config.yaml}). Wraps the shared pool,
 * or a connection inside {@link #inTransaction}, and returns rows as {@link JsonObject} keyed by
 * column name, so repositories, services and handlers pass JSON and never DTO classes.
 *
 * <p>Only parameterized statements: values always travel in a {@link Tuple} with {@code $1, $2 ...}
 * placeholders, never concatenated into SQL. Use it from {@link BaseRepository} subclasses only;
 * handlers and services never hold SQL.
 *
 * <p>Value conversion: uuid → string, date/time/timestamp → ISO-8601 string, numeric → BigDecimal,
 * json/jsonb → JsonObject/JsonArray, bytea → base64 string, arrays → JsonArray, null stays null.
 */
public final class SqlExecutor {

    private final SqlClient client;

    public SqlExecutor(SqlClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** All rows. */
    public Future<JsonArray> queryList(String sql, Tuple params) {
        return client.preparedQuery(sql).execute(params).map(SqlExecutor::toJsonArray);
    }

    public Future<JsonArray> queryList(String sql) {
        return queryList(sql, Tuple.tuple());
    }

    /** The first row, or {@code null} when there is none. */
    public Future<JsonObject> queryOne(String sql, Tuple params) {
        return client.preparedQuery(sql).execute(params).map(rows -> {
            var it = rows.iterator();
            return it.hasNext() ? toJson(it.next()) : null;
        });
    }

    public Future<JsonObject> queryOne(String sql) {
        return queryOne(sql, Tuple.tuple());
    }

    /** INSERT, UPDATE or DELETE; resolves to the number of rows affected. */
    public Future<Integer> execute(String sql, Tuple params) {
        return client.preparedQuery(sql).execute(params).map(RowSet::rowCount);
    }

    /**
     * Runs {@code work} in one transaction: committed when its future succeeds, rolled back when it
     * fails. Build the repositories you need from the given executor so every statement (including
     * the audit entry, task 3.7) joins the same transaction.
     */
    public <T> Future<T> inTransaction(Function<SqlExecutor, Future<T>> work) {
        if (!(client instanceof Pool pool)) {
            // Already inside a transaction: join it.
            return work.apply(this);
        }
        return pool.withTransaction(conn -> work.apply(new SqlExecutor(conn)));
    }

    static JsonArray toJsonArray(RowSet<Row> rows) {
        JsonArray out = new JsonArray();
        for (Row row : rows) {
            out.add(toJson(row));
        }
        return out;
    }

    static JsonObject toJson(Row row) {
        JsonObject json = new JsonObject();
        for (int i = 0; i < row.size(); i++) {
            json.put(row.getColumnName(i), toJsonValue(row.getValue(i)));
        }
        return json;
    }

    static Object toJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof JsonObject || value instanceof JsonArray) {
            return value;
        }
        if (value instanceof Numeric n) {
            return n.bigDecimalValue() != null ? n.bigDecimalValue() : n.doubleValue();
        }
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof UUID || value instanceof Temporal || value instanceof Enum<?>) {
            return value.toString();
        }
        if (value instanceof Buffer b) {
            return Base64.getEncoder().encodeToString(b.getBytes());
        }
        if (value.getClass().isArray()) {
            JsonArray arr = new JsonArray();
            for (int i = 0; i < Array.getLength(value); i++) {
                arr.add(toJsonValue(Array.get(value, i)));
            }
            return arr;
        }
        return value.toString();
    }
}
