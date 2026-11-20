package com.pulse.modules.imports.type;

import com.pulse.modules.imports.csv.CsvColumns;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * Test-only import type (lives in test sources, so the running application never offers it).
 * Found by {@link ImportTypeRegistry#discover} because its name ends in {@code ImportType}.
 * Part C of task 16.2 gives it a real table to commit into.
 */
public class NotesImportType implements ImportType
{
    @Override
    public String id()
    {
        return "test_notes";
    }

    @Override
    public String name()
    {
        return "Test notes";
    }

    @Override
    public String feeds()
    {
        return "Nothing; used by the import framework tests";
    }

    @Override
    public List<String> permissions()
    {
        return List.of("test.notes.import");
    }

    @Override
    public JsonArray columns()
    {
        return JsonArray.of(
                CsvColumns.required("title", CsvColumns.TEXT),
                CsvColumns.optional("due", CsvColumns.DATE));
    }

    @Override
    public JsonArray modes()
    {
        return JsonArray.of(
                new JsonObject().put("key", "add_update").put("label", "Add and update"),
                new JsonObject().put("key", "add_only").put("label", "Add only"));
    }

    @Override
    public JsonObject templateExample()
    {
        return new JsonObject().put("title", "Write the runbook").put("due", "2026-09-30");
    }

    @Override
    public List<String> targetTables()
    {
        return List.of("test_notes");
    }

    @Override
    public Future<JsonArray> check(JsonArray rows, String mode, JsonObject actor, SqlExecutor sql)
    {
        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            if (row.getJsonArray("errors").isEmpty())
            {
                row.put("action", "new");
            }
        }

        return Future.succeededFuture(rows);
    }

    @Override
    public Future<JsonObject> commit(JsonArray rows, String mode, JsonObject actor, SqlExecutor tx)
    {
        return Future.succeededFuture(new JsonObject().put("imported", 0).put("changes", new JsonArray()));
    }

    @Override
    public Future<JsonArray> exportRows(JsonObject actor, SqlExecutor sql)
    {
        return Future.succeededFuture(new JsonArray());
    }
}
