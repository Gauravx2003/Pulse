package com.pulse.modules.imports.type;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * One kind of CSV import (Users, OKRs, Attendance, Customer visits, Holiday calendar ...).
 *
 * <h2>Joining the registry</h2>
 * A module joins by putting a public, no-arg class whose simple name ends in {@code ImportType}
 * anywhere under its own package {@code com.pulse.modules.<module>} (for example
 * {@code com.pulse.modules.attendance.AttendanceImportType}). {@link ImportTypeRegistry} finds it by
 * classpath scan, the same way modules themselves are found, so no shared file is edited.
 *
 * <h2>Pipeline</h2>
 * The shared pipeline (task 16.2) parses and cell-checks the file with {@link #columns()}, then
 * calls {@link #check} for the rules that need several cells or the database, and later
 * {@link #commit} inside one transaction. The import type never parses CSV and never writes
 * staging, history or before-images itself.
 *
 * <h2>Row shape</h2>
 * Rows passed to {@link #check} come from {@code CsvValidator}:
 * <pre>{@code {"line": 2, "values": {...}, "errors": [...], "warnings": [...]}}</pre>
 * {@link #check} returns the same rows, adding errors and warnings
 * ({@code {"line", "column" (may be null), "message"}}) and setting {@code "action"} on each row
 * without errors:
 * <ul>
 *   <li>{@code "new"}: commit will insert it;</li>
 *   <li>{@code "update"}: commit will change an existing record;</li>
 *   <li>{@code "unchanged"}: matches what is stored, or a row to skip; reported as skipped.</li>
 * </ul>
 * Rows that have errors are never passed to {@link #commit}.
 *
 * <p>Implementations are stateless: one instance serves every request on every event loop.
 */
public interface ImportType
{
    /** Registry key and URL segment, lower-case, e.g. {@code "attendance"}. */
    String id();

    /** Card and history title, e.g. {@code "Attendance"}. */
    String name();

    /** What the data feeds, shown on the import card. */
    String feeds();

    /**
     * Holding any one of these permissions lets a user check and commit this import. The routes
     * enforce it through the policy service once it lands (task 3.5).
     */
    List<String> permissions();

    /** Column definitions built with {@code CsvColumns}, in template order. */
    JsonArray columns();

    /**
     * Modes the user may choose, first one is the default:
     * {@code [{"key": "replace_months", "label": "Replace months"}, ...]}.
     */
    JsonArray modes();

    /** One example data row for the downloadable template, keyed by column key. */
    JsonObject templateExample();

    /**
     * Tables this import writes. Undo restores before-images only into these tables, so a stored
     * before-image can never name an arbitrary table.
     */
    List<String> targetTables();

    /**
     * Rules that need several cells or the database (unknown user, duplicates in the file,
     * overlaps, permission on the target record). Must not write anything (spec: "Check does not
     * write").
     *
     * @param rows  validated rows (see class comment); the method may change and return them
     * @param mode  one of {@link #modes()}
     * @param actor the signed-in user ({@code {id, role, personId, permissions}}, as {@code PolicyService.user})
     * @param sql    read access for lookups
     * @param policy visibility decisions (never filter people by hand)
     * @return the same rows with errors, warnings and {@code action} set
     */
    Future<JsonArray> check(JsonArray rows, String mode, JsonObject actor, SqlExecutor sql, PolicyService policy);

    /**
     * Applies the ready rows (action {@code new} or {@code update}) inside the pipeline's transaction.
     *
     * <p>Every changed record must be reported so undo can restore it:
     * <pre>{@code
     * {"imported": 97,
     *  "changes": [{"table": "attendance_records", "id": "<uuid>", "action": "insert|update|delete",
     *               "before": {...full row before, null for insert...},
     *               "afterVersion": 2   // the row's version column after the import, null for delete
     *              }, ...],
     *  "notes": ["Managers were notified"]}   // optional lines for the Done step
     * }</pre>
     *
     * @param rows  ready rows only, in file order
     * @param tx    the commit transaction; every statement must use it
     */
    Future<JsonObject> commit(JsonArray rows, String mode, JsonObject actor, SqlExecutor tx, PolicyService policy);

    /**
     * Current data in the template's column layout, so an exported file checks back unchanged
     * (spec: "Templates and round-trip exports"). Each item is keyed by column key. Must apply the
     * actor's visibility.
     *
     * @param query the request's query parameters (for example {@code {"quarter": "Q3 2026"}})
     */
    Future<JsonArray> exportRows(JsonObject actor, JsonObject query, SqlExecutor sql, PolicyService policy);

    /**
     * True when this type undoes its own changes through {@link #undoChange} instead of the
     * pipeline's generic row restore, because one change spans several tables that the owning
     * module must restore itself (OKRs: objective, key results and check-ins). The pipeline still
     * refuses undo first when a reported record's {@code version} moved after the import.
     */
    default boolean restoresItself()
    {
        return false;
    }

    /**
     * Reverts one reported change (newest first) inside the undo transaction; only called when
     * {@link #restoresItself()} is true. {@code before} is the change's stored {@code before}.
     */
    default Future<Void> undoChange(JsonObject before, JsonObject actor, SqlExecutor tx, PolicyService policy)
    {
        return Future.failedFuture(new UnsupportedOperationException(id() + " does not restore itself"));
    }
}
