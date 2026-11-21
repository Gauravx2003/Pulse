package com.pulse.platform.db;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import com.pulse.platform.http.ApiException;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Base class of every repository (task 1.6). SQL lives only in subclasses; services and handlers
 * call repository methods and receive {@link JsonObject}/{@link JsonArray}.
 *
 * <pre>{@code
 * public class PeopleRepository extends BaseRepository {
 *     public PeopleRepository(SqlExecutor sql) { super(sql); }
 *
 *     public Future<JsonObject> byId(String id) {
 *         return queryOne("SELECT id, name, level FROM people WHERE id = $1", Tuple.of(UUID.fromString(id)));
 *     }
 *
 *     public Future<JsonObject> list(MultiMap params) {
 *         return page(ListQuery.from("SELECT id, name, level FROM people")
 *                 .filter("level", "level").sort("name", "name").defaultSort("name"), params);
 *     }
 * }
 *
 * // in a service: one transaction across repositories
 * ctx.sql().inTransaction(tx -> new PeopleRepository(tx).rename(id, name)
 *         .compose(v -> new OtherRepository(tx).touch(id)));
 * }</pre>
 *
 * <p>Repositories are cheap and stateless: create them from {@code ModuleContext.sql()} for normal
 * work, or from the executor that {@link SqlExecutor#inTransaction} hands you.
 */
public abstract class BaseRepository {

    /** Most rows an export returns (spec api-conventions "cap export size"). */
    public static final int MAX_EXPORT_ROWS = 10_000;

    private static final Pattern TABLE = Pattern.compile("[a-z_][a-z0-9_]*");

    protected final SqlExecutor sql;

    protected BaseRepository(SqlExecutor sql) {
        this.sql = Objects.requireNonNull(sql, "sql");
    }

    protected Future<JsonArray> queryList(String statement, Tuple params) {
        return sql.queryList(statement, params);
    }

    protected Future<JsonObject> queryOne(String statement, Tuple params) {
        return sql.queryOne(statement, params);
    }

    protected Future<Integer> execute(String statement, Tuple params) {
        return sql.execute(statement, params);
    }

    /**
     * Runs a {@link ListQuery} for the request's query parameters. Resolves to
     * {@code {items, page, pageSize, total, pageSizeCapped}}; {@code pageSizeCapped} is true when the
     * client asked for more than the maximum page size. Fails with {@link QueryValidationException}
     * for parameters the query does not allow.
     */
    protected Future<JsonObject> page(ListQuery query, MultiMap params) {
        ListQuery.Built built;
        try {
            built = query.build(params);
        } catch (QueryValidationException e) {
            return Future.failedFuture(e);
        }
        return Future.all(sql.queryList(built.pageSql(), built.pageParams()),
                        sql.queryOne(built.countSql(), built.countParams()))
                .map(both -> new JsonObject()
                        .put("items", both.<JsonArray>resultAt(0))
                        .put("page", built.page())
                        .put("pageSize", built.pageSize())
                        .put("total", both.<JsonObject>resultAt(1).getLong("total"))
                        .put("pageSizeCapped", built.pageSizeCapped()));
    }

    /**
     * Optimistic-lock update (task 3.8, spec api-conventions "Optimistic concurrency"). Runs
     * {@code UPDATE <table> SET <set>, version = version + 1, updated_at = now() WHERE id = ? AND version = ?
     * RETURNING *}. {@code set} uses {@code $1..$n} for {@code setParams}. Resolves to the updated row;
     * fails with 404 {@code not_found} when the row does not exist and with 409 {@code version_conflict}
     * (carrying {@code currentVersion}) when it was saved by someone else since {@code expectedVersion},
     * without overwriting anything.
     *
     * <pre>{@code
     * updateVersioned("okrs", "title = $1, status = $2", Tuple.of(title, status), id, expectedVersion)
     * }</pre>
     */
    protected Future<JsonObject> updateVersioned(String table, String set, Tuple setParams, UUID id, int expectedVersion)
    {
        if (!TABLE.matcher(table).matches())
        {
            throw new IllegalArgumentException("Not a table name: " + table);
        }

        Tuple params = Tuple.tuple();

        int size = setParams == null ? 0 : setParams.size();

        for (int i = 0; i < size; i++)
        {
            params.addValue(setParams.getValue(i));
        }

        params.addValue(id);
        params.addValue(expectedVersion);

        String statement = "UPDATE " + table + " SET " + set + ", version = version + 1, updated_at = now() WHERE id = $"
                + (size + 1) + " AND version = $" + (size + 2) + " RETURNING *";

        return sql.queryOne(statement, params).compose(row ->
        {
            if (row != null)
            {
                return Future.succeededFuture(row);
            }

            return sql.queryOne("SELECT version FROM " + table + " WHERE id = $1", Tuple.of(id)).compose(current ->
                    current == null
                            ? Future.failedFuture(ApiException.notFound("not_found", "Not found"))
                            : Future.failedFuture(ApiException.conflict("version_conflict",
                                    "Someone else saved this record since you opened it. Reload to see their changes.")
                                    .currentVersion(current.getInteger("version"))));
        });
    }

    /**
     * Rows of a list query for export: the request's filters and sort, no paging, at most
     * {@link #MAX_EXPORT_ROWS}. Resolves to {@code {items, truncated}}.
     */
    protected Future<JsonObject> export(ListQuery query, MultiMap params)
    {
        MultiMap filters = MultiMap.caseInsensitiveMultiMap().addAll(params);

        filters.remove("page");
        filters.remove("pageSize");

        ListQuery.Built built;

        try
        {
            built = query.pageSizes(MAX_EXPORT_ROWS + 1, MAX_EXPORT_ROWS + 1).build(filters);
        }
        catch (QueryValidationException e)
        {
            return Future.failedFuture(e);
        }

        return sql.queryList(built.pageSql(), built.pageParams()).map(rows ->
        {
            boolean truncated = rows.size() > MAX_EXPORT_ROWS;

            if (truncated)
            {
                rows.remove(MAX_EXPORT_ROWS);
            }

            return new JsonObject().put("items", rows).put("truncated", truncated);
        });
    }
}
