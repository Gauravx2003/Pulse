package com.pulse.platform.db;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.Objects;

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
}
