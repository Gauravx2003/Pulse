package com.pulse.modules.imports.type;

import com.pulse.modules.imports.csv.CsvColumns;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Test-only import type (lives in test sources, so the running application never offers it).
 * Writes the table {@link #CREATE_TABLE} that the pipeline tests create. Found by
 * {@link ImportTypeRegistry#discover} because its name ends in {@code ImportType}.
 *
 * <p>Modes: {@code add_update} (default), {@code add_only} (existing titles are skipped) and
 * {@code replace_all} (notes missing from the file are deleted).
 */
public class NotesImportType implements ImportType
{
    /**
     * A real catalogue permission (Admin and Manager hold it, Employees do not), so the HTTP tests can
     * run this type through the platform's sign-in and role matrix.
     */
    public static final String PERMISSION = "users.team";

    public static final String CREATE_TABLE = """
            CREATE TABLE test_notes (
                id      uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
                title   text    NOT NULL UNIQUE,
                due     date,
                version integer NOT NULL DEFAULT 1
            )
            """;

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
        return List.of(PERMISSION);
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
                new JsonObject().put("key", "add_only").put("label", "Add only"),
                new JsonObject().put("key", "replace_all").put("label", "Replace all"));
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
    public Future<JsonArray> check(JsonArray rows, String mode, JsonObject actor, SqlExecutor sql, PolicyService policy)
    {
        return sql.queryList("SELECT id, title, due FROM test_notes", Tuple.tuple()).map(stored ->
        {
            Map<String, JsonObject> byTitle = new HashMap<>();

            for (Object item : stored)
            {
                byTitle.put(((JsonObject) item).getString("title"), (JsonObject) item);
            }

            Map<String, Integer> firstLine = new HashMap<>();

            for (Object item : rows)
            {
                JsonObject row = (JsonObject) item;

                JsonObject values = row.getJsonObject("values");

                String title = values.getString("title");

                if (title == null)
                {
                    continue;
                }

                Integer earlier = firstLine.putIfAbsent(title, row.getInteger("line"));

                if (earlier != null)
                {
                    row.getJsonArray("errors").add(new JsonObject().put("line", row.getInteger("line")).put("column", "title")
                            .put("message", "Same title as line " + earlier + "."));

                    continue;
                }

                if (!row.getJsonArray("errors").isEmpty())
                {
                    continue;
                }

                JsonObject existing = byTitle.get(title);

                if (existing == null)
                {
                    row.put("action", "new");
                }
                else if ("add_only".equals(mode) || equalDue(existing.getString("due"), values.getString("due")))
                {
                    row.put("action", "unchanged");
                }
                else
                {
                    row.put("action", "update").put("targetId", existing.getString("id"));
                }
            }

            return rows;
        });
    }

    private static boolean equalDue(String stored, String incoming)
    {
        return stored == null ? incoming == null : stored.equals(incoming);
    }

    @Override
    public Future<JsonObject> commit(JsonArray rows, String mode, JsonObject actor, SqlExecutor tx, PolicyService policy)
    {
        JsonArray changes = new JsonArray();

        Future<Void> chain = Future.succeededFuture();

        Set<String> titles = new HashSet<>();

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            JsonObject values = row.getJsonObject("values");

            titles.add(values.getString("title"));

            if ("new".equals(row.getString("action")))
            {
                chain = chain.compose(v -> tx.queryOne("INSERT INTO test_notes (title, due) VALUES ($1, $2) RETURNING id, version",
                                Tuple.of(values.getString("title"), date(values.getString("due"))))
                        .map(inserted ->
                        {
                            changes.add(change("insert", inserted.getString("id"), null, inserted.getInteger("version")));

                            return null;
                        }));
            }
            else
            {
                String id = row.getString("targetId");

                chain = chain.compose(v -> tx.queryOne("SELECT * FROM test_notes WHERE id = $1 FOR UPDATE", Tuple.of(UUID.fromString(id)))
                        .compose(before -> tx.queryOne("UPDATE test_notes SET due = $2, version = version + 1 WHERE id = $1 RETURNING version",
                                        Tuple.of(UUID.fromString(id), date(values.getString("due"))))
                                .map(after ->
                                {
                                    changes.add(change("update", id, before, after.getInteger("version")));

                                    return null;
                                })));
            }
        }

        if ("replace_all".equals(mode))
        {
            chain = chain.compose(v -> tx.queryList("SELECT * FROM test_notes", Tuple.tuple())).compose(all ->
            {
                List<Future<Integer>> deletes = new ArrayList<>();

                for (Object item : all)
                {
                    JsonObject note = (JsonObject) item;

                    if (!titles.contains(note.getString("title")))
                    {
                        changes.add(change("delete", note.getString("id"), note, null));

                        deletes.add(tx.execute("DELETE FROM test_notes WHERE id = $1", Tuple.of(UUID.fromString(note.getString("id")))));
                    }
                }

                return Future.all(deletes).mapEmpty();
            });
        }

        return chain.map(v -> new JsonObject()
                .put("imported", rows.size())
                .put("changes", changes)
                .put("notes", JsonArray.of("Test notes updated")));
    }

    private static LocalDate date(String iso)
    {
        return iso == null ? null : LocalDate.parse(iso);
    }

    private static JsonObject change(String action, String id, JsonObject before, Integer afterVersion)
    {
        return new JsonObject()
                .put("table", "test_notes")
                .put("id", id)
                .put("action", action)
                .put("before", before)
                .put("afterVersion", afterVersion);
    }

    @Override
    public Future<JsonArray> exportRows(JsonObject actor, JsonObject query, SqlExecutor sql, PolicyService policy)
    {
        return sql.queryList("SELECT title, due FROM test_notes ORDER BY title", Tuple.tuple());
    }
}
