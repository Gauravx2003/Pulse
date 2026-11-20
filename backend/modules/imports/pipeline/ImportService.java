package com.pulse.modules.imports.pipeline;

import com.pulse.modules.imports.csv.CsvImportException;
import com.pulse.modules.imports.csv.CsvParser;
import com.pulse.modules.imports.csv.CsvValidator;
import com.pulse.modules.imports.type.ImportType;
import com.pulse.modules.imports.type.ImportTypeRegistry;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The shared Check → Commit → Undo pipeline of spec platform/csv-import-framework (task 16.2).
 *
 * <ul>
 *   <li><b>Check</b> parses and cell-checks the file off the event loop, runs the import type's own
 *       rules, stores the ready rows in {@code import_checks} and returns counts, errors, warnings
 *       and a 12-row preview. It never touches business data.</li>
 *   <li><b>Commit</b> runs in one transaction under a per-type advisory lock, so commits of one type
 *       never interleave. It accepts only an unexpired check of the same user and type, is
 *       idempotent (a repeat returns the first result), writes history and before-images and deletes
 *       the staging row.</li>
 *   <li><b>Undo</b> (importer or Admin, within 24 hours) restores every before-image in reverse
 *       order, and is refused, listing the records, when any of them changed after the import.</li>
 * </ul>
 *
 * <p>{@code actor} is {@code {"userId": "<uuid>", "permissions": [...]}}. Every refusal is an
 * {@link ImportFailure} carrying its HTTP status.
 */
public class ImportService
{
    /** Published after a commit or undo so caches that read imported data can be invalidated (task 4.10). */
    public static final String COMMITTED_ADDRESS = "pulse.imports.committed";

    public static final String UNDONE_ADDRESS = "pulse.imports.undone";

    /**
     * Lets a user see everyone's history and undo anyone's import (spec: "the importer or an Admin").
     * TODO-STUB: replace with the policy service decision once task 3.5 lands.
     */
    public static final String ADMIN_PERMISSION = "all.view";

    static final int PREVIEW_ROWS = 12;

    static final int MAX_LISTED_MESSAGES = 1_000;

    private final Vertx vertx;

    private final SqlExecutor sql;

    private final ImportTypeRegistry registry;

    public ImportService(Vertx vertx, SqlExecutor sql, ImportTypeRegistry registry)
    {
        this.vertx = vertx;

        this.sql = sql;

        this.registry = registry;
    }

    // ------------------------------------------------------------------ check

    public Future<JsonObject> check(String typeId, String requestedMode, String fileName, byte[] bytes, JsonObject actor)
    {
        ImportType type;

        String mode;

        try
        {
            type = allowedType(typeId, actor);

            mode = resolveMode(type, requestedMode);
        }
        catch (ImportFailure e)
        {
            return Future.failedFuture(e);
        }

        String name = fileName == null || fileName.isBlank() ? type.id() + ".csv" : fileName.strip();

        Future<JsonObject> validated = vertx.executeBlocking(() ->
        {
            JsonObject parsed = CsvParser.parse(bytes, name);

            return CsvValidator.validate(parsed, type.columns()).put("fileHash", sha256(bytes));
        }, false);

        return validated
                .recover(ImportService::asImportFailure)
                .compose(file -> type.check(file.getJsonArray("rows"), mode, actor, sql)
                        .compose(rows ->
                        {
                            JsonObject result = summarize(type, mode, name, file, rows);

                            ImportCheckRepository checks = new ImportCheckRepository(sql);

                            return checks.deleteExpired()
                                    .compose(removed -> checks.insert(actor.getString("userId"), type.id(), mode, name,
                                            file.getString("fileHash"), bytes.length, readyRows(rows), result))
                                    .map(saved -> result.copy()
                                            .put("checkId", saved.getString("id"))
                                            .put("expiresAt", saved.getString("expires_at")));
                        }));
    }

    private static Future<JsonObject> asImportFailure(Throwable error)
    {
        if (error instanceof CsvImportException csv)
        {
            return Future.failedFuture(new ImportFailure(422, csv.code(), csv.getMessage()));
        }

        return Future.failedFuture(error);
    }

    /** Counts, messages and preview of a checked file (spec: "Three-step import"). */
    static JsonObject summarize(ImportType type, String mode, String fileName, JsonObject file, JsonArray rows)
    {
        int newRows = 0;

        int updateRows = 0;

        int skipped = 0;

        int errorRows = 0;

        int warningCount = 0;

        JsonArray errorList = new JsonArray();

        JsonArray warningList = new JsonArray();

        JsonArray preview = new JsonArray();

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            JsonArray errors = row.getJsonArray("errors", new JsonArray());

            JsonArray warnings = row.getJsonArray("warnings", new JsonArray());

            String status;

            if (!errors.isEmpty())
            {
                errorRows++;

                status = "error";
            }
            else
            {
                status = row.getString("action", "unchanged");

                switch (status)
                {
                    case "new" -> newRows++;
                    case "update" -> updateRows++;
                    default ->
                    {
                        status = "unchanged";

                        skipped++;
                    }
                }
            }

            warningCount += warnings.size();

            addAll(errorList, errors);

            addAll(warningList, warnings);

            if (preview.size() < PREVIEW_ROWS)
            {
                preview.add(new JsonObject()
                        .put("line", row.getInteger("line"))
                        .put("status", status)
                        .put("values", row.getJsonObject("values"))
                        .put("errors", errors)
                        .put("warnings", warnings));
            }
        }

        int ready = newRows + updateRows;

        String summary = ready + " ready (" + newRows + " new, " + updateRows + " to update)"
                + " · " + skipped + " skipped · " + errorRows + " with errors · " + warningCount + " warnings";

        return new JsonObject()
                .put("type", type.id())
                .put("typeName", type.name())
                .put("mode", mode)
                .put("fileName", fileName)
                .put("rowsRead", rows.size())
                .put("ready", ready)
                .put("new", newRows)
                .put("update", updateRows)
                .put("skipped", skipped)
                .put("errors", errorRows)
                .put("warnings", warningCount)
                .put("errorList", errorList)
                .put("warningList", warningList)
                .put("listsTruncated", errorList.size() >= MAX_LISTED_MESSAGES || warningList.size() >= MAX_LISTED_MESSAGES)
                .put("ignoredColumns", file.getJsonArray("ignoredColumns"))
                .put("preview", preview)
                .put("summary", summary);
    }

    private static void addAll(JsonArray target, JsonArray messages)
    {
        for (Object message : messages)
        {
            if (target.size() >= MAX_LISTED_MESSAGES)
            {
                return;
            }

            target.add(message);
        }
    }

    /** Rows without errors whose action is new or update: exactly what commit will apply. */
    static JsonArray readyRows(JsonArray rows)
    {
        JsonArray ready = new JsonArray();

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            String action = row.getString("action");

            if (row.getJsonArray("errors", new JsonArray()).isEmpty() && ("new".equals(action) || "update".equals(action)))
            {
                JsonObject copy = row.copy();

                copy.remove("errors");

                ready.add(copy);
            }
        }

        return ready;
    }

    // ------------------------------------------------------------------ commit and cancel

    public Future<JsonObject> commit(String typeId, String checkId, JsonObject actor)
    {
        ImportType type;

        try
        {
            type = allowedType(typeId, actor);

            requireUuid(checkId);
        }
        catch (ImportFailure e)
        {
            return Future.failedFuture(e);
        }

        String userId = actor.getString("userId");

        return sql.inTransaction(tx ->
        {
            ImportLogRepository logs = new ImportLogRepository(tx);

            return logs.lockType(type.id())
                    .compose(locked -> logs.byCheckId(checkId))
                    .compose(existing ->
                    {
                        if (existing != null)
                        {
                            if (!userId.equals(existing.getString("user_id")) || !type.id().equals(existing.getString("import_type")))
                            {
                                return Future.failedFuture(checkNotFound());
                            }

                            return Future.succeededFuture(existing);
                        }

                        return applyCheck(type, checkId, actor, tx);
                    });
        })
                .map(ImportService::toImportResult)
                .onSuccess(result -> vertx.eventBus().publish(COMMITTED_ADDRESS, new JsonObject()
                        .put("type", type.id())
                        .put("importId", result.getString("importId"))));
    }

    private Future<JsonObject> applyCheck(ImportType type, String checkId, JsonObject actor, SqlExecutor tx)
    {
        String userId = actor.getString("userId");

        ImportCheckRepository checks = new ImportCheckRepository(tx);

        ImportLogRepository logs = new ImportLogRepository(tx);

        return checks.lockValid(checkId, userId, type.id()).compose(check ->
        {
            if (check == null)
            {
                return Future.failedFuture(checkNotFound());
            }

            JsonObject result = check.getJsonObject("result");

            return type.commit(check.getJsonArray("rows_data"), check.getString("mode"), actor, tx).compose(outcome ->
            {
                JsonArray changes = outcome.getJsonArray("changes", new JsonArray());

                try
                {
                    verifyChanges(type, changes);
                }
                catch (ImportFailure e)
                {
                    return Future.failedFuture(e);
                }

                JsonObject summary = new JsonObject()
                        .put("summary", result.getString("summary"))
                        .put("new", result.getInteger("new"))
                        .put("update", result.getInteger("update"))
                        .put("ignoredColumns", result.getJsonArray("ignoredColumns"))
                        .put("notes", outcome.getJsonArray("notes", new JsonArray()));

                JsonObject entry = new JsonObject()
                        .put("checkId", checkId)
                        .put("importType", type.id())
                        .put("mode", check.getString("mode"))
                        .put("fileName", check.getString("file_name"))
                        .put("fileHash", check.getString("file_hash"))
                        .put("userId", userId)
                        .put("rowsRead", result.getInteger("rowsRead"))
                        .put("imported", outcome.getInteger("imported", 0))
                        .put("skipped", result.getInteger("skipped"))
                        .put("errors", result.getInteger("errors"))
                        .put("warnings", result.getInteger("warnings"))
                        .put("summary", summary);

                return logs.insert(entry)
                        .compose(importId -> new BeforeImageRepository(tx).insertAll(importId, changes)
                                .compose(stored -> checks.delete(checkId, userId))
                                .compose(deleted -> logs.byId(importId)));
            });
        });
    }

    /** Every reported change must name a declared target table, a uuid and a known action. */
    private static void verifyChanges(ImportType type, JsonArray changes)
    {
        Set<String> tables = new HashSet<>(type.targetTables());

        for (Object item : changes)
        {
            JsonObject change = item instanceof JsonObject c ? c : new JsonObject();

            String action = change.getString("action", "");

            boolean known = action.equals("insert") || action.equals("update") || action.equals("delete");

            boolean consistent = action.equals("insert") == (change.getValue("before") == null)
                    && action.equals("delete") == (change.getValue("afterVersion") == null);

            if (!tables.contains(change.getString("table")) || !isUuid(change.getString("id")) || !known || !consistent)
            {
                throw new ImportFailure(500, ImportFailure.BAD_CHANGE,
                        "Import type '" + type.id() + "' reported an invalid change: " + change.encode());
            }
        }
    }

    public Future<Void> cancel(String checkId, JsonObject actor)
    {
        if (!isUuid(checkId))
        {
            return Future.failedFuture(checkNotFound());
        }

        return new ImportCheckRepository(sql).delete(checkId, actor.getString("userId")).compose(deleted ->
                deleted == 0 ? Future.failedFuture(checkNotFound()) : Future.succeededFuture());
    }

    // ------------------------------------------------------------------ undo and history

    public Future<JsonObject> undo(String importId, JsonObject actor)
    {
        if (!isUuid(importId))
        {
            return Future.failedFuture(importNotFound());
        }

        String userId = actor.getString("userId");

        return sql.inTransaction(tx ->
        {
            ImportLogRepository logs = new ImportLogRepository(tx);

            return logs.lockForUndo(importId).compose(log ->
            {
                if (log == null || (!userId.equals(log.getString("user_id")) && !holds(actor, ADMIN_PERMISSION)))
                {
                    return Future.failedFuture(importNotFound());
                }

                if (log.getValue("undone_at") != null)
                {
                    return Future.failedFuture(new ImportFailure(409, ImportFailure.ALREADY_UNDONE, "This import has already been undone."));
                }

                if (!log.getBoolean("undo_open"))
                {
                    return Future.failedFuture(new ImportFailure(409, ImportFailure.UNDO_EXPIRED,
                            "Imports can be undone for 24 hours. This one is older."));
                }

                ImportType type = registry.find(log.getString("import_type")).orElse(null);

                if (type == null)
                {
                    return Future.failedFuture(new ImportFailure(409, ImportFailure.UNKNOWN_TYPE,
                            "This kind of import is no longer available, so it cannot be undone."));
                }

                return logs.lockType(type.id())
                        .compose(locked -> new BeforeImageRepository(tx).forUndo(importId))
                        .compose(images -> refuseOnConflicts(type, images, tx)
                                .compose(clear -> restore(images, tx))
                                .map(images.size()))
                        .compose(restored -> logs.markUndone(importId, userId).map(restored));
            })
                    .compose(restored -> logs.byId(importId).map(log -> toImportResult(log).put("restored", restored)));
        })
                .onSuccess(result -> vertx.eventBus().publish(UNDONE_ADDRESS, new JsonObject()
                        .put("type", result.getString("type"))
                        .put("importId", importId)));
    }

    /** Fails with the list of records changed since the import (spec: "Conflicting later change"). */
    private static Future<Void> refuseOnConflicts(ImportType type, JsonArray images, SqlExecutor tx)
    {
        Set<String> allowed = new HashSet<>(type.targetTables());

        // Only the newest change of each record decides; images arrive newest first.
        Map<String, Map<String, JsonObject>> latestByTable = new LinkedHashMap<>();

        for (Object item : images)
        {
            JsonObject image = (JsonObject) item;

            String table = image.getString("target_table");

            if (!allowed.contains(table))
            {
                return Future.failedFuture(new ImportFailure(500, ImportFailure.BAD_CHANGE,
                        "Stored before-image names table '" + table + "', which import type '" + type.id() + "' does not declare."));
            }

            latestByTable.computeIfAbsent(table, t -> new LinkedHashMap<>()).putIfAbsent(image.getString("target_id"), image);
        }

        TargetRowRepository targets = new TargetRowRepository(tx);

        List<Future<JsonArray>> reads = new ArrayList<>();

        List<String> tables = new ArrayList<>(latestByTable.keySet());

        for (String table : tables)
        {
            List<UUID> ids = new ArrayList<>();

            for (String id : latestByTable.get(table).keySet())
            {
                ids.add(UUID.fromString(id));
            }

            reads.add(targets.versions(table, ids));
        }

        return Future.all(reads).compose(all ->
        {
            JsonArray conflicts = new JsonArray();

            for (int i = 0; i < tables.size(); i++)
            {
                String table = tables.get(i);

                Map<String, Integer> current = new HashMap<>();

                for (Object row : all.<JsonArray>resultAt(i))
                {
                    current.put(((JsonObject) row).getString("id"), ((JsonObject) row).getInteger("version"));
                }

                for (JsonObject image : latestByTable.get(table).values())
                {
                    String id = image.getString("target_id");

                    boolean deletedByImport = "delete".equals(image.getString("action"));

                    boolean unchanged = deletedByImport
                            ? !current.containsKey(id)
                            : image.getInteger("after_version").equals(current.get(id));

                    if (!unchanged)
                    {
                        conflicts.add(new JsonObject()
                                .put("table", table)
                                .put("id", id)
                                .put("reason", current.containsKey(id) ? "changed after the import" : "deleted after the import"));
                    }
                }
            }

            if (conflicts.isEmpty())
            {
                return Future.succeededFuture();
            }

            return Future.failedFuture(new ImportFailure(409, ImportFailure.UNDO_CONFLICT,
                    "Undo refused: " + conflicts.size() + " record" + (conflicts.size() == 1 ? " was" : "s were")
                            + " changed after this import, so restoring it is not safe.",
                    conflicts));
        });
    }

    /** Applies the before-images newest first: insert → delete, update → restore, delete → re-insert. */
    private static Future<Void> restore(JsonArray images, SqlExecutor tx)
    {
        TargetRowRepository targets = new TargetRowRepository(tx);

        Future<Integer> chain = Future.succeededFuture(0);

        for (Object item : images)
        {
            JsonObject image = (JsonObject) item;

            String table = image.getString("target_table");

            UUID id = UUID.fromString(image.getString("target_id"));

            chain = chain.compose(previous -> switch (image.getString("action"))
            {
                case "insert" -> targets.delete(table, id);
                case "update" -> targets.restore(table, id, image.getJsonObject("before_data"));
                default -> targets.reinsert(table, image.getJsonObject("before_data"));
            });
        }

        return chain.mapEmpty();
    }

    /** History newest first: everyone's for an Admin, otherwise the caller's own imports. */
    public Future<JsonObject> history(MultiMap params, JsonObject actor)
    {
        String onlyUser = holds(actor, ADMIN_PERMISSION) ? null : actor.getString("userId");

        return new ImportLogRepository(sql).history(params, onlyUser);
    }

    // ------------------------------------------------------------------ helpers

    private ImportType allowedType(String typeId, JsonObject actor)
    {
        ImportType type = registry.find(typeId).orElseThrow(() ->
                new ImportFailure(404, ImportFailure.UNKNOWN_TYPE, "There is no import called '" + typeId + "'."));

        if (!ImportTypeRegistry.isAllowed(type, permissions(actor)))
        {
            throw new ImportFailure(403, ImportFailure.FORBIDDEN, "You do not have permission to import " + type.name() + ".");
        }

        return type;
    }

    private static String resolveMode(ImportType type, String requested)
    {
        if (requested == null || requested.isBlank())
        {
            return ImportTypeRegistry.defaultMode(type);
        }

        if (!ImportTypeRegistry.hasMode(type, requested))
        {
            List<String> keys = new ArrayList<>();

            for (Object mode : type.modes())
            {
                keys.add(((JsonObject) mode).getString("key"));
            }

            throw new ImportFailure(422, ImportFailure.BAD_MODE,
                    "Unknown mode '" + requested + "'. Use one of: " + String.join(", ", keys) + ".");
        }

        return requested;
    }

    private static List<String> permissions(JsonObject actor)
    {
        List<String> granted = new ArrayList<>();

        for (Object permission : actor.getJsonArray("permissions", new JsonArray()))
        {
            granted.add(String.valueOf(permission));
        }

        return granted;
    }

    private static boolean holds(JsonObject actor, String permission)
    {
        return permissions(actor).contains(permission);
    }

    /** The public shape of one history row. */
    static JsonObject toImportResult(JsonObject log)
    {
        return new JsonObject()
                .put("importId", log.getString("id"))
                .put("checkId", log.getString("check_id"))
                .put("type", log.getString("import_type"))
                .put("mode", log.getString("mode"))
                .put("fileName", log.getString("file_name"))
                .put("userId", log.getString("user_id"))
                .put("by", log.getString("user_name"))
                .put("committedAt", log.getString("committed_at"))
                .put("rowsRead", log.getInteger("rows_read"))
                .put("imported", log.getInteger("imported"))
                .put("skipped", log.getInteger("skipped"))
                .put("errors", log.getInteger("errors"))
                .put("warnings", log.getInteger("warnings"))
                .put("summary", log.getJsonObject("summary"))
                .put("undoUntil", log.getString("undo_until"))
                .put("undoState", log.getString("undo_state"));
    }

    private static ImportFailure checkNotFound()
    {
        return new ImportFailure(404, ImportFailure.CHECK_NOT_FOUND,
                "This check has expired or does not exist. Check the file again.");
    }

    private static ImportFailure importNotFound()
    {
        return new ImportFailure(404, ImportFailure.IMPORT_NOT_FOUND, "Import not found.");
    }

    private static void requireUuid(String value)
    {
        if (!isUuid(value))
        {
            throw checkNotFound();
        }
    }

    private static boolean isUuid(String value)
    {
        if (value == null)
        {
            return false;
        }

        try
        {
            UUID.fromString(value);

            return value.length() == 36;
        }
        catch (IllegalArgumentException e)
        {
            return false;
        }
    }

    static String sha256(byte[] bytes)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
