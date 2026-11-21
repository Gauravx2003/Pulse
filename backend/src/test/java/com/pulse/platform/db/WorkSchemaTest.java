package com.pulse.platform.db;

import com.pulse.modules.work.WorkModule;
import io.vertx.pgclient.PgException;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 7.1: the Track 2 V200 data layer applied after V100, checked as the app role.
 */
class WorkSchemaTest
{

    private static final String UNIQUE_VIOLATION = "23505";

    /**
     * Each Track 2 table and the column its policy-scoped repository filters on (task 7.3).
     */
    private static final Map<String, String> SCOPE_COLUMNS = new LinkedHashMap<>();

    static
    {
        SCOPE_COLUMNS.put("issues", "assignee_id");
        SCOPE_COLUMNS.put("issue_events", "issue_id");
        SCOPE_COLUMNS.put("worklogs", "person_id");
        SCOPE_COLUMNS.put("commit_days", "person_id");
        SCOPE_COLUMNS.put("pull_requests", "author_id");
        SCOPE_COLUMNS.put("pr_reviews", "reviewer_id");
        SCOPE_COLUMNS.put("pr_comments", "author_id");
        SCOPE_COLUMNS.put("bugs", "origin_person_id");
        SCOPE_COLUMNS.put("support_tickets", "person_id");
        SCOPE_COLUMNS.put("extra_items", "person_id");
        SCOPE_COLUMNS.put("weekly_pulse", "person_id");
    }

    private static final List<String> INGEST_TABLES = List.of("issues", "issue_events", "worklogs", "commit_days",
            "pull_requests", "pr_reviews", "pr_comments", "bugs", "support_tickets");

    private TestDb db;

    private PgPool owner;

    private PgPool app;

    private List<SqlScript> applied;

    @BeforeEach
    void setUp() throws Exception
    {
        db = TestDb.open();

        owner = db.ownerPool();

        ScriptCatalog catalog = ScriptCatalog.load(getClass().getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule())));

        applied = await(MigrationRunner.migrate(owner, catalog));

        app = db.pool(Db.DEFAULT_APP_ROLE);
    }

    @AfterEach
    void tearDown() throws Exception
    {
        if (db != null)
        {
            db.close();
        }
    }

    @Test
    void v200AppliesAfterV100()
    {
        List<Integer> versions = applied.stream().map(SqlScript::version).toList();

        assertTrue(versions.contains(100) && versions.contains(200), versions.toString());

        assertTrue(versions.indexOf(100) < versions.indexOf(200), "V100 must run before V200: " + versions);
    }

    @Test
    void createsEveryTrack2Table() throws Exception
    {
        Set<String> tables = new HashSet<>();

        for (Row r : await(owner.preparedQuery("SELECT tablename FROM pg_tables WHERE schemaname = $1")
                .execute(Tuple.of(db.schema))))
        {
            tables.add(r.getString(0));
        }

        assertTrue(tables.containsAll(SCOPE_COLUMNS.keySet()), tables.toString());
    }

    @Test
    void everyTableHasAnIndexLedByItsPolicyScopeColumn() throws Exception
    {
        for (Map.Entry<String, String> e : SCOPE_COLUMNS.entrySet())
        {
            Set<String> leading = new HashSet<>();

            for (Row r : await(owner.preparedQuery("""
                            SELECT a.attname
                            FROM pg_index i
                            JOIN pg_class t ON t.oid = i.indrelid
                            JOIN pg_namespace n ON n.oid = t.relnamespace
                            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = i.indkey[0]
                            WHERE n.nspname = $1 AND t.relname = $2""")
                    .execute(Tuple.of(db.schema, e.getKey()))))
            {
                leading.add(r.getString(0));
            }

            assertTrue(leading.contains(e.getValue()),
                    e.getKey() + " needs an index led by " + e.getValue() + "; indexes lead with " + leading);
        }
    }

    @Test
    void ingestTablesHaveAUniqueSourceAndExternalIdIndex() throws Exception
    {
        for (String table : INGEST_TABLES)
        {
            long n = await(owner.preparedQuery("""
                            SELECT count(*) FROM pg_indexes
                            WHERE schemaname = $1 AND tablename = $2
                              AND indexdef LIKE 'CREATE UNIQUE INDEX%(source, external_id)'""")
                    .execute(Tuple.of(db.schema, table))).iterator().next().getLong(0);

            assertEquals(1L, n, table + " needs a unique index on (source, external_id)");
        }
    }

    @Test
    void duplicateSourceAndExternalIdInIssuesFails() throws Exception
    {
        String insert = "INSERT INTO issues (key, summary, status, external_id) "
                + "VALUES ('PLAT-2231', 'Rate-limit ingestion API per tenant', 'In Progress', 'PLAT-2231')";

        await(app.query(insert).execute());

        assertSqlState(UNIQUE_VIOLATION, () -> await(app.query(insert).execute()));

        // The same key from a live connector is a different row (design.md decision 6).
        await(app.query(insert.replace("external_id)", "external_id, source)")
                .replace("'PLAT-2231')", "'PLAT-2231', 'jira')")).execute());

        assertEquals(2L, await(app.query("SELECT count(*) FROM issues").execute()).iterator().next().getLong(0));
    }

    @Test
    void weeklyPulseAllowsOneAnswerPerPersonPerWeek() throws Exception
    {
        String personId = await(app.query("INSERT INTO people (name, external_id) VALUES ('Priya Shah', 'ps') RETURNING id")
                .execute()).iterator().next().getUUID(0).toString();

        String insert = "INSERT INTO weekly_pulse (person_id, week_start, mood, workload) VALUES ($1::uuid, '2026-09-21', 3, 4)";

        await(app.preparedQuery(insert).execute(Tuple.of(personId)));

        assertSqlState(UNIQUE_VIOLATION, () -> await(app.preparedQuery(insert).execute(Tuple.of(personId))));
    }

    private static void assertSqlState(String expected, ThrowingRunnable action)
    {
        ExecutionException e = assertThrows(ExecutionException.class, action::run);

        assertTrue(e.getCause() instanceof PgException, String.valueOf(e.getCause()));

        assertEquals(expected, ((PgException) e.getCause()).getSqlState(), e.getCause().getMessage());
    }

    @FunctionalInterface
    private interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
