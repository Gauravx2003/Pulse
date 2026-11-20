package com.pulse.platform.db;

import io.vertx.pgclient.PgException;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 2.1: the real V100 migration applied to an empty schema, checked as the app role. */
class BaseSchemaTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private TestDb db;
    private PgPool owner;
    private PgPool app;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDb.open();
        owner = db.ownerPool();
        ScriptCatalog platform = ScriptCatalog.load(getClass().getClassLoader(), List.of(ScriptCatalog.PLATFORM));
        List<SqlScript> applied = await(MigrationRunner.migrate(owner, platform));
        assertTrue(applied.stream().anyMatch(s -> s.version() == 100), "V100 should apply on an empty schema");
        app = db.pool(Db.DEFAULT_APP_ROLE);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void createsEveryBaseTable() throws Exception {
        Set<String> tables = new TreeSet<>();
        for (Row r : await(owner.preparedQuery("SELECT tablename FROM pg_tables WHERE schemaname = $1")
                .execute(Tuple.of(db.schema)))) {
            tables.add(r.getString(0));
        }
        assertTrue(tables.containsAll(Set.of("users", "roles", "role_permissions", "people", "reporting_lines",
                "sessions", "audit_log", "rules", "rule_versions", "holidays", "sprints", "releases",
                "notifications", "outbox", "sync_log", "culture_signals")), tables.toString());
    }

    @Test
    void appRoleCanInsertButNotUpdateOrDeleteAuditLog() throws Exception {
        await(app.query("INSERT INTO audit_log (action, outcome) VALUES ('test.event', 'success')").execute());

        assertSqlState(INSUFFICIENT_PRIVILEGE, () -> await(app.query("UPDATE audit_log SET action = 'x'").execute()));
        assertSqlState(INSUFFICIENT_PRIVILEGE, () -> await(app.query("DELETE FROM audit_log").execute()));
        assertEquals(1L, count(owner, "SELECT count(*) FROM audit_log WHERE action = 'test.event'"));
    }

    @Test
    void appRoleHasDmlButNoDdl() throws Exception {
        await(app.query("INSERT INTO holidays (holiday_on, name, external_id) VALUES ('2026-10-02', 'Gandhi Jayanti', '2026-10-02')").execute());
        await(app.query("UPDATE holidays SET name = 'Gandhi Jayanti' WHERE holiday_on = '2026-10-02'").execute());
        assertSqlState(INSUFFICIENT_PRIVILEGE, () -> await(app.query("CREATE TABLE sneaky (id int)").execute()));
        assertSqlState(INSUFFICIENT_PRIVILEGE, () -> await(app.query("DROP TABLE holidays").execute()));
    }

    @Test
    void tablesCreatedLaterByOtherTracksAreGrantedToTheAppRole() throws Exception {
        await(owner.query("CREATE TABLE track2_items (id int PRIMARY KEY)").execute());
        await(app.query("INSERT INTO track2_items VALUES (1)").execute());
        assertEquals(1L, count(app, "SELECT count(*) FROM track2_items"));
    }

    @Test
    void duplicateSourceAndExternalIdInSprintsFails() throws Exception {
        String insert = "INSERT INTO sprints (key, number, starts_on, work_ends_on, ends_on, external_id) "
                + "VALUES ('S43', 43, '2026-09-21', '2026-10-09', '2026-10-11', 'S43')";
        await(app.query(insert).execute());
        assertSqlState(UNIQUE_VIOLATION, () -> await(app.query(insert).execute()));
        // Same external id from another source is a different row.
        await(app.query(insert.replace("external_id)", "external_id, source)").replace("'S43')", "'S43', 'jira')"))
                .execute());
        assertEquals(2L, count(app, "SELECT count(*) FROM sprints"));
    }

    @Test
    void duplicateCultureSignalForPersonQuarterAndParamFails() throws Exception {
        String personId = await(app.query("INSERT INTO people (name, external_id) VALUES ('Priya Shah', 'ps') RETURNING id")
                .execute()).iterator().next().getUUID(0).toString();
        String insert = "INSERT INTO culture_signals (person_id, quarter, param_key, raw_value, display_value, "
                + "source_label, is_simulated) VALUES ($1::uuid, 'Q3', 'decisions_documented', 4, '4', 'Confluence', true)";
        await(app.preparedQuery(insert).execute(Tuple.of(personId)));
        assertSqlState(UNIQUE_VIOLATION, () -> await(app.preparedQuery(insert).execute(Tuple.of(personId))));
    }

    @Test
    void appRoleCannotRewriteMigrationHistory() throws Exception {
        assertSqlState(INSUFFICIENT_PRIVILEGE,
                () -> await(app.query("DELETE FROM " + MigrationRunner.HISTORY_TABLE).execute()));
    }

    private static long count(PgPool pool, String sql) throws Exception {
        return await(pool.query(sql).execute()).iterator().next().getLong(0);
    }

    private static void assertSqlState(String expected, ThrowingRunnable action) {
        ExecutionException e = assertThrows(ExecutionException.class, action::run);
        assertTrue(e.getCause() instanceof PgException, String.valueOf(e.getCause()));
        assertEquals(expected, ((PgException) e.getCause()).getSqlState(), e.getCause().getMessage());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
