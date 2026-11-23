package com.pulse.calc.work;

import com.pulse.calc.core.CalcContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Tuple;

/**
 * Reads of Track 2 tables for {@code calc/work} formulas. A table that is not there reads as no rows:
 * a test database built from the platform migrations alone (Track 1's CalcService tests close every
 * period, which runs every periodic formula). In the application every module's migrations run before
 * the server starts, so the tables always exist and this never applies.
 */
final class WorkSql
{

    private static final String UNDEFINED_TABLE = "42P01";

    private WorkSql()
    {
    }

    static Future<JsonArray> rows(CalcContext ctx, String statement, Tuple params)
    {
        return ctx.sql().queryList(statement, params).recover(error -> missingTable(error)
                ? Future.succeededFuture(new JsonArray())
                : Future.failedFuture(error));
    }

    static Future<JsonArray> rows(CalcContext ctx, String statement)
    {
        return rows(ctx, statement, Tuple.tuple());
    }

    /**
     * As {@link #rows}, for one row; null when there is none or the table is not there.
     */
    static Future<JsonObject> row(CalcContext ctx, String statement, Tuple params)
    {
        return ctx.sql().queryOne(statement, params).recover(error -> missingTable(error)
                ? Future.succeededFuture(null)
                : Future.failedFuture(error));
    }

    private static boolean missingTable(Throwable error)
    {
        return error instanceof PgException pg && UNDEFINED_TABLE.equals(pg.getSqlState());
    }
}
