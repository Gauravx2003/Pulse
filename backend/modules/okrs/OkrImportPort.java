package com.pulse.modules.okrs;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * What Track 3's CSV engine (tasks 16.2 and 16.3) uses to import and export OKRs (task 14.3). The OKR
 * import definition parses and groups rows; everything that reads or writes OKR tables goes through
 * this port, so the OKR rules live in one place (design.md decision 10, cross-track data).
 *
 * <p>Conventions shared by every method:
 * <ul>
 *   <li>{@code actor} is {@code {userId, personId, permissions: [...]}}, the same as for {@link OkrService}.</li>
 *   <li>Quarters are keys such as {@code 2026-Q3}; {@link OkrImportService#quarterKey} turns the CSV
 *       form {@code Q3 2026} into a key and {@link OkrImportService#quarterLabel} back.</li>
 *   <li>Writes join the caller's transaction when the implementation is built from the executor that
 *       {@code SqlExecutor.inTransaction} hands out, so one import commits or rolls back as a whole.</li>
 *   <li>Refusals fail with {@link OkrException} (status 400, 403 or 409) whose message is meant for the
 *       import error list.</li>
 * </ul>
 */
public interface OkrImportPort
{

    /**
     * The owner of an Individual objective by work email: resolves to {@code {person_id, user_id, name,
     * email}}, or to {@code {error}} with the message for the {@code owner_email} column (unknown user,
     * no performance profile, or not manageable by the actor).
     */
    Future<JsonObject> findOwner(JsonObject actor, String email);

    /**
     * For the Check step (writes nothing): the objective that an import group would update, matched by
     * quarter, owner or team, and text (ignoring case). Resolves to {@code {objective, editable}} where
     * {@code objective} is null when the group would create a new one.
     *
     * @param key {@code {quarter, level, owner_person_id | team, title}}
     */
    Future<JsonObject> findMatching(JsonObject actor, JsonObject key);

    /**
     * For the Commit step: creates the objective, or, in mode {@code upsert}, updates the matching one.
     * A key result that matches by text gets the imported start, target and unit, and a new current
     * value is recorded as a check-in ({@code via = 'import'}, the bulk check-in of spec growth/okrs);
     * other key results are added. In mode {@code new} an existing objective is skipped.
     *
     * @param group {@code {quarter, level, owner_person_id | team, title, culture_value, epic_key,
     *              key_results: [{description, start_value, target_value, current_value, unit}]}}
     * @param mode  {@code upsert} (add new and update matching) or {@code new} (add new only)
     * @return {@code {action: new | update | skip, objective_id, before, after_version, added_key_results,
     *         checkin_ids}}; store it as the before-image that {@link #undoUpsert} needs
     */
    Future<JsonObject> upsertObjective(JsonObject actor, JsonObject group, String mode);

    /**
     * Reverts one {@link #upsertObjective} result within the import's undo window. Fails with 409 when
     * the objective changed after the import, so undo never overwrites later work.
     */
    Future<Void> undoUpsert(JsonObject actor, JsonObject result);

    /**
     * Export rows for a quarter, one per key result, keyed and ordered like the import columns
     * ({@code quarter, level, owner_email, objective, key_result, start, target, current, unit, value,
     * epic}) so the file round-trips. Only Team objectives and owners visible to the actor are included.
     */
    Future<JsonArray> listForExport(JsonObject actor, String quarter);
}
