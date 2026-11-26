package com.pulse.calc.core;

import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Tuple;

import java.util.Set;
import java.util.UUID;

/**
 * The snapshot and rule-version store (task 4.9, spec platform/calculation-engine "Snapshots and rule
 * versioning", appraisal "Frozen at submit", review-360 publish).
 *
 * <p><b>Rule versions.</b> Every save of Rules &amp; thresholds inserts a full rule set into
 * {@code rule_versions} (task 5.2); {@link #currentRules} and {@link #rules} read them. Old versions
 * never change (the application role cannot update them).
 *
 * <p><b>Snapshots.</b> {@link #freeze} stores the inputs, the guarded result ({@link ResultGuard}) and
 * the rule version of a calculation that must not move: a closed period ({@link #PERIOD}, key
 * {@code "s:S42"}), a submitted appraisal proposal ({@link #APPRAISAL}, key the proposal id), a
 * published 360° cycle ({@link #REVIEW_360}, key the cycle id). There is one live snapshot per kind and
 * key; editing a rule or the data afterwards changes live numbers only. {@link #replace} is the one
 * way to change a snapshot: it needs a written reason, keeps the old row as superseded history and,
 * for a period, writes the {@code calc.recalculated} audit entry in the same transaction. Who may call
 * it (an Admin for a period, the appraisal send-back for a proposal) is checked by the caller.
 *
 * <p>Rows: {@code {id, kind, subjectKey, ruleVersion, inputs, result, reason, createdAt, createdBy,
 * supersededAt, supersededBy}}.
 */
public final class SnapshotStore
{
    public static final String PERIOD = "period";

    public static final String APPRAISAL = "appraisal";

    public static final String REVIEW_360 = "review_360";

    public static final Set<String> KINDS = Set.of(PERIOD, APPRAISAL, REVIEW_360);

    /** Shortest reason accepted for a replacement. */
    public static final int MIN_REASON = 10;

    private static final String COLUMNS = "id, kind, subject_key AS \"subjectKey\", rule_version AS \"ruleVersion\", "
            + "inputs, result, reason, created_at AS \"createdAt\", created_by AS \"createdBy\", "
            + "superseded_at AS \"supersededAt\", superseded_by AS \"supersededBy\"";

    private static final String UNIQUE_VIOLATION = "23505";

    private SnapshotStore()
    {
    }

    // ------------------------------------------------------------------ rule versions

    /**
     * The latest rule version: {@code {version, rules}}; null before any version exists. A rule the stored
     * version lacks reads its default ({@link RuleCatalogue#withDefaults}).
     */
    public static Future<JsonObject> currentRules(SqlExecutor sql)
    {
        return sql.queryOne("SELECT version, rule_set AS rules FROM rule_versions ORDER BY version DESC LIMIT 1")
                .map(SnapshotStore::withDefaults);
    }

    /**
     * A rule version: {@code {version, rules}}; null when there is none. A rule the stored version lacks reads
     * its default.
     */
    public static Future<JsonObject> rules(SqlExecutor sql, int version)
    {
        return sql.queryOne("SELECT version, rule_set AS rules FROM rule_versions WHERE version = $1", Tuple.of(version))
                .map(SnapshotStore::withDefaults);
    }

    private static JsonObject withDefaults(JsonObject row)
    {
        return row == null ? null : row.put("rules", RuleCatalogue.withDefaults(row.getJsonObject("rules")));
    }

    // ------------------------------------------------------------------ snapshots

    /**
     * Stores the first snapshot of a subject. Fails with {@link IllegalStateException} when the
     * subject already has a live snapshot: a frozen result is changed only through {@link #replace}.
     *
     * @return the stored row
     */
    public static Future<JsonObject> freeze(SqlExecutor sql, String kind, String subjectKey, int ruleVersion,
                                            JsonObject inputs, JsonObject result, String actorId)
    {
        try
        {
            check(kind, subjectKey);
        }
        catch (IllegalArgumentException e)
        {
            return Future.failedFuture(e);
        }

        return insert(sql, UUID.randomUUID(), kind, subjectKey, ruleVersion, inputs, result, null, actorId)
                .recover(e -> Future.failedFuture(isUniqueViolation(e)
                        ? new IllegalStateException(kind + " " + subjectKey + " is already frozen; replace it with a reason.")
                        : e));
    }

    /** The live snapshot of a subject, or null. */
    public static Future<JsonObject> find(SqlExecutor sql, String kind, String subjectKey)
    {
        return sql.queryOne("SELECT " + COLUMNS + " FROM calc_snapshots WHERE kind = $1 AND subject_key = $2 "
                + "AND superseded_at IS NULL", Tuple.of(kind, subjectKey));
    }

    /** Every snapshot of a subject, oldest first, the superseded ones included. */
    public static Future<JsonArray> history(SqlExecutor sql, String kind, String subjectKey)
    {
        return sql.queryList("SELECT " + COLUMNS + " FROM calc_snapshots WHERE kind = $1 AND subject_key = $2 "
                + "ORDER BY created_at, superseded_at NULLS LAST", Tuple.of(kind, subjectKey));
    }

    /**
     * Replaces the live snapshot of a subject with a recalculated one, in one transaction: the old row
     * is marked superseded, the new row keeps the reason, and a period recalculation is audited.
     *
     * @param actor  {@code {id, role}} of the caller, as on the request
     * @param reason why (at least {@link #MIN_REASON} characters)
     * @return the new row
     */
    public static Future<JsonObject> replace(SqlExecutor sql, String kind, String subjectKey, int ruleVersion,
                                             JsonObject inputs, JsonObject result, JsonObject actor, String reason)
    {
        String trimmed = reason == null ? "" : reason.strip();

        try
        {
            check(kind, subjectKey);

            if (trimmed.length() < MIN_REASON)
            {
                throw new IllegalArgumentException("reason: Give a reason of at least " + MIN_REASON + " characters.");
            }
        }
        catch (IllegalArgumentException e)
        {
            return Future.failedFuture(e);
        }

        String actorId = actor == null ? null : actor.getString("id");

        return sql.inTransaction(tx -> find(tx, kind, subjectKey).compose(old ->
        {
            if (old == null)
            {
                return Future.failedFuture(new IllegalStateException(kind + " " + subjectKey + " has no snapshot to replace."));
            }

            UUID id = UUID.randomUUID();

            return tx.execute("UPDATE calc_snapshots SET superseded_at = now(), superseded_by = $1 WHERE id = $2",
                            Tuple.of(id, UUID.fromString(old.getString("id"))))
                    .compose(n -> insert(tx, id, kind, subjectKey, ruleVersion, inputs, result, trimmed, actorId))
                    .compose(row -> !PERIOD.equals(kind) ? Future.succeededFuture(row)
                            : AuditService.record(tx, AuditService.entry(actor, AuditEvents.CALC_RECALCULATED, "period",
                                    subjectKey, new JsonObject()
                                            .put("snapshot", row.getString("id"))
                                            .put("replaced", old.getString("id"))
                                            .put("ruleVersion", new JsonObject().put("before", old.getInteger("ruleVersion"))
                                                    .put("after", ruleVersion))))
                            .map(v -> row));
        }));
    }

    // ------------------------------------------------------------------ helpers

    private static Future<JsonObject> insert(SqlExecutor sql, UUID id, String kind, String subjectKey, int ruleVersion,
                                             JsonObject inputs, JsonObject result, String reason, String actorId)
    {
        return sql.queryOne("INSERT INTO calc_snapshots (id, kind, subject_key, rule_version, inputs, result, reason, "
                        + "created_by) VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING " + COLUMNS,
                Tuple.of(id, kind, subjectKey, ruleVersion, ResultGuard.guardObject(inputs), ResultGuard.guardObject(result),
                        reason, actorId == null ? null : UUID.fromString(actorId)));
    }

    private static void check(String kind, String subjectKey)
    {
        if (!KINDS.contains(kind))
        {
            throw new IllegalArgumentException("kind: Unknown snapshot kind '" + kind + "'.");
        }

        if (subjectKey == null || subjectKey.isBlank())
        {
            throw new IllegalArgumentException("subjectKey: A snapshot needs a subject key.");
        }
    }

    private static boolean isUniqueViolation(Throwable e)
    {
        return e instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState());
    }
}
