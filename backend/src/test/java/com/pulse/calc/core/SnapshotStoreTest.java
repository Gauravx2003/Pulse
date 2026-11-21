package com.pulse.calc.core;

import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4.9: the snapshot and rule-version store against spec platform/calculation-engine "Snapshots
 * and rule versioning" ("Rule edited after close", "Submitted appraisal is frozen"), as the application
 * role on the migrated and seeded schema.
 */
class SnapshotStoreTest
{
    private static final JsonObject ACTOR = new JsonObject().put("id", UUID.randomUUID().toString()).put("role", "Admin");

    private static final AtomicInteger KEYS = new AtomicInteger();

    private static TestDb db;

    private static SqlExecutor sql;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(SnapshotStoreTest.class.getClassLoader(), List.of(ScriptCatalog.PLATFORM));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));
        await(MigrationRunner.seed(db.ownerPool(), catalog));

        sql = new SqlExecutor(db.pool(Db.DEFAULT_APP_ROLE));
    }

    @AfterAll
    static void tearDown() throws Exception
    {
        if (db != null)
        {
            db.close();
        }
    }

    @Test
    void ruleEditedAfterCloseDoesNotChangeTheSnapshot() throws Exception
    {
        String key = key("s:S42");

        JsonObject v1 = await(SnapshotStore.currentRules(sql));

        int before = v1.getInteger("version");

        JsonObject result = new JsonObject().put("stale", 3).put("staleDays", v1.getJsonObject("rules").getInteger("staleDays"));

        await(SnapshotStore.freeze(sql, SnapshotStore.PERIOD, key, before, new JsonObject().put("open", 7), result,
                ACTOR.getString("id")));

        // An Admin changes the stale threshold, as the rules backend (task 5.2) does: a new version.
        int after = editStaleDays(9);

        assertEquals(after, await(SnapshotStore.currentRules(sql)).getInteger("version"));
        assertEquals(9, await(SnapshotStore.currentRules(sql)).getJsonObject("rules").getInteger("staleDays"));

        JsonObject stored = await(SnapshotStore.find(sql, SnapshotStore.PERIOD, key));

        assertEquals(result, stored.getJsonObject("result"));
        assertEquals(before, stored.getInteger("ruleVersion"));
        assertEquals(v1.getJsonObject("rules"), await(SnapshotStore.rules(sql, before)).getJsonObject("rules"));
    }

    @Test
    void submittedAppraisalIsFrozenUntilReplacedWithAReason() throws Exception
    {
        String key = key(UUID.randomUUID().toString());

        int version = await(SnapshotStore.currentRules(sql)).getInteger("version");

        JsonObject submitted = new JsonObject().put("score", 3.75).put("pct", 8.5);

        await(SnapshotStore.freeze(sql, SnapshotStore.APPRAISAL, key, version, new JsonObject(), submitted, null));

        editStaleDays(4);

        assertEquals(submitted, await(SnapshotStore.find(sql, SnapshotStore.APPRAISAL, key)).getJsonObject("result"));

        // A second freeze is refused; only replace changes it, and only with a reason.
        assertFailsWith(IllegalStateException.class, () -> SnapshotStore.freeze(sql, SnapshotStore.APPRAISAL, key,
                version, new JsonObject(), new JsonObject().put("score", 1), null));
        assertFailsWith(IllegalArgumentException.class, () -> SnapshotStore.replace(sql, SnapshotStore.APPRAISAL, key,
                version, new JsonObject(), new JsonObject(), ACTOR, "  short "));

        JsonObject sentBack = await(SnapshotStore.replace(sql, SnapshotStore.APPRAISAL, key, version + 1, new JsonObject(),
                new JsonObject().put("score", 3.5).put("pct", 7), ACTOR, "Sent back by HR: recompute"));

        JsonArray history = await(SnapshotStore.history(sql, SnapshotStore.APPRAISAL, key));

        assertEquals(2, history.size());
        assertNotNull(history.getJsonObject(0).getString("supersededAt"));
        assertEquals(sentBack.getString("id"), history.getJsonObject(0).getString("supersededBy"));
        assertNull(history.getJsonObject(1).getString("supersededAt"));
        assertEquals(7, await(SnapshotStore.find(sql, SnapshotStore.APPRAISAL, key)).getJsonObject("result").getInteger("pct"));

        // The appraisal send-back is audited by the appraisal module, not here.
        assertEquals(0L, auditCount(key));
    }

    @Test
    void periodRecalculationIsAudited() throws Exception
    {
        String key = key("s:S41");

        int version = await(SnapshotStore.currentRules(sql)).getInteger("version");

        await(SnapshotStore.freeze(sql, SnapshotStore.PERIOD, key, version, new JsonObject(), new JsonObject().put("stale", 2),
                null));

        JsonObject row = await(SnapshotStore.replace(sql, SnapshotStore.PERIOD, key, version, new JsonObject(),
                new JsonObject().put("stale", 1), ACTOR, "Jira import of S41 was late"));

        assertEquals("Jira import of S41 was late", row.getString("reason"));
        assertEquals(1L, auditCount(key));

        // Replacing a subject that was never frozen fails and writes nothing.
        assertFailsWith(IllegalStateException.class, () -> SnapshotStore.replace(sql, SnapshotStore.PERIOD,
                key("s:none"), version, new JsonObject(), new JsonObject(), ACTOR, "No such snapshot here"));
    }

    @Test
    void resultsAreStoredGuarded() throws Exception
    {
        String key = key(UUID.randomUUID().toString());

        JsonObject raw = new JsonObject().put("score", null).put("weights", new JsonArray().add(-0.0).add(Double.NaN));

        JsonObject row = await(SnapshotStore.freeze(sql, SnapshotStore.REVIEW_360, key,
                await(SnapshotStore.currentRules(sql)).getInteger("version"), new JsonObject(), raw, null));

        JsonObject stored = row.getJsonObject("result");

        assertTrue(ResultGuard.isClean(stored), stored.encode());
        assertTrue(ResultGuard.isNotApplicable(stored.getValue("score")));
        assertTrue(ResultGuard.isNotApplicable(stored.getJsonArray("weights").getValue(1)));
    }

    @Test
    void theApplicationCannotChangeASnapshotOrARuleVersion() throws Exception
    {
        String key = key("s:S40");

        int version = await(SnapshotStore.currentRules(sql)).getInteger("version");

        JsonObject row = await(SnapshotStore.freeze(sql, SnapshotStore.PERIOD, key, version, new JsonObject(),
                new JsonObject().put("stale", 2), null));

        Tuple id = Tuple.of(UUID.fromString(row.getString("id")));

        assertThrows(Exception.class, () -> await(sql.execute("UPDATE calc_snapshots SET result = '{}'::jsonb WHERE id = $1", id)));
        assertThrows(Exception.class, () -> await(sql.execute("UPDATE calc_snapshots SET rule_version = 1 WHERE id = $1", id)));
        assertThrows(Exception.class, () -> await(sql.execute("DELETE FROM calc_snapshots WHERE id = $1", id)));
        assertThrows(Exception.class, () -> await(sql.execute("UPDATE rule_versions SET rule_set = '{}'::jsonb WHERE version = $1",
                Tuple.of(version))));
        assertThrows(Exception.class, () -> await(sql.execute("DELETE FROM rule_versions WHERE version = $1", Tuple.of(version))));

        assertEquals(2, await(SnapshotStore.find(sql, SnapshotStore.PERIOD, key)).getJsonObject("result").getInteger("stale"));
    }

    @Test
    void unknownKindsAndBlankKeysAreRefused()
    {
        assertFailsWith(IllegalArgumentException.class, () -> SnapshotStore.freeze(sql, "sprint", "s:S42", 1,
                new JsonObject(), new JsonObject(), null));
        assertFailsWith(IllegalArgumentException.class, () -> SnapshotStore.freeze(sql, SnapshotStore.PERIOD, " ", 1,
                new JsonObject(), new JsonObject(), null));
    }

    // ------------------------------------------------------------------ helpers

    /** Inserts a new rule version with {@code staleDays} changed, as a rules save does; returns it. */
    private static int editStaleDays(int days) throws Exception
    {
        return await(sql.inTransaction(tx -> SnapshotStore.currentRules(tx).compose(cur ->
        {
            int next = cur.getInteger("version") + 1;

            JsonObject set = cur.getJsonObject("rules").copy().put("staleDays", days);

            return tx.execute("INSERT INTO rule_versions (version, rule_set, diff, reason) VALUES ($1, $2, $3, 'test')",
                            Tuple.of(next, set, new JsonObject().put("staleDays", new JsonObject()
                                    .put("before", cur.getJsonObject("rules").getValue("staleDays")).put("after", days))))
                    .compose(n -> tx.execute("UPDATE rules SET value = to_jsonb($1::int), version = $2 WHERE key = 'staleDays'",
                            Tuple.of(days, next)))
                    .map(n -> next);
        })));
    }

    /** Asserts that the future fails with {@code type} (await wraps it in an ExecutionException). */
    private static void assertFailsWith(Class<? extends Throwable> type, Supplier<Future<?>> call)
    {
        ExecutionException e = assertThrows(ExecutionException.class, () -> await(call.get()));

        assertInstanceOf(type, e.getCause(), String.valueOf(e.getCause()));
    }

    private static long auditCount(String key) throws Exception
    {
        return await(sql.queryOne("SELECT count(*) AS n FROM audit_log WHERE action = $1 AND target_id = $2",
                Tuple.of(AuditEvents.CALC_RECALCULATED, key))).getLong("n");
    }

    private static String key(String base)
    {
        return base + "#" + KEYS.incrementAndGet();
    }
}
