package com.pulse.modules.imports.pipeline;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Generic reads and writes on the business tables an import changed, used only by undo.
 *
 * <p>Table and column names cannot be bound as parameters, so they are checked against a strict
 * identifier pattern and quoted here; the service additionally accepts only tables the import type
 * declared in {@code targetTables()}. Values always travel as parameters. Target tables have a
 * {@code uuid id} and an {@code integer version} column (design.md decision 4).
 */
public class TargetRowRepository extends BaseRepository
{
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    public TargetRowRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** {@code [{id, version}]} of the rows that still exist among {@code ids}. */
    public Future<JsonArray> versions(String table, List<UUID> ids)
    {
        return queryList("SELECT id, version FROM " + quote(table) + " WHERE id = ANY($1)",
                Tuple.of(ids.toArray(new UUID[0])));
    }

    public Future<Integer> delete(String table, UUID id)
    {
        return execute("DELETE FROM " + quote(table) + " WHERE id = $1", Tuple.of(id));
    }

    /**
     * Puts the columns of {@code before} back into the live row. {@code id} and {@code version} are
     * not restored; the version moves forward so later optimistic-lock checks see the change.
     */
    public Future<Integer> restore(String table, UUID id, JsonObject before)
    {
        List<String> columns = new ArrayList<>();

        for (String column : before.fieldNames())
        {
            if (!"id".equals(column) && !"version".equals(column))
            {
                columns.add(quote(column));
            }
        }

        if (columns.isEmpty())
        {
            return Future.succeededFuture(0);
        }

        String list = String.join(", ", columns);

        return execute("UPDATE " + quote(table) + " SET (" + list + ") = (SELECT " + list
                        + " FROM jsonb_populate_record(NULL::" + quote(table) + ", $2::jsonb)), version = version + 1 WHERE id = $1",
                Tuple.of(id, before));
    }

    /** Inserts {@code before} again as a whole row (undo of a delete). */
    public Future<Integer> reinsert(String table, JsonObject before)
    {
        return execute("INSERT INTO " + quote(table) + " SELECT * FROM jsonb_populate_record(NULL::" + quote(table) + ", $1::jsonb)",
                Tuple.of(before));
    }

    static String quote(String identifier)
    {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches())
        {
            throw new IllegalArgumentException("Not a plain identifier: " + identifier);
        }

        return "\"" + identifier + "\"";
    }
}
