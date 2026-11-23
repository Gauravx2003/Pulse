package com.pulse.modules.progress;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.modules.work.WorkModule;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import com.pulse.platform.module.JobSpec;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 13.3: the Friday weekly-pulse prompt (spec platform/jobs-scheduler "Friday pulse"), registered as
 * a job for the task 5.5 scheduler and run here directly: every active Employee without an answer for the
 * week gets one notice, and a second run in the same week after they answered sends none to them.
 */
class PulsePromptJobTest
{

    private static TestDb db;

    private static SqlExecutor owner;

    private static ModuleContext ctx;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(PulsePromptJobTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule(), new ProgressModule())));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));

        await(MigrationRunner.seed(db.ownerPool(), catalog, true));

        owner = new SqlExecutor(db.ownerPool());

        PgPool app = db.pool(Db.DEFAULT_APP_ROLE);

        CalcService calc = new CalcService(new SqlExecutor(app), PulseClock.of(Clock.systemUTC(), "2026-10-02", false), FormulaRegistry.discover());

        PolicyService policy = new PolicyService(new SqlExecutor(app), Set.of(), List.of(), false);

        ctx = new ModuleContext(db.vertx(), new JsonObject(), app, policy, calc, null);
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
    void everyEmployeeWithoutAnAnswerGetsOnePromptOnFriday() throws Exception
    {
        JobSpec job = new ProgressModule().jobs().stream().filter(j -> ProgressModule.PROMPT_JOB.equals(j.name())).findFirst().orElseThrow();

        assertEquals("0 16 * * 5", job.schedule());

        long expected = count("""
                SELECT count(*) FROM users u JOIN roles r ON r.id = u.role_id JOIN people p ON p.user_id = u.id
                WHERE u.status = 'Active' AND r.name = 'Employee'""");

        assertTrue(expected > 0);

        assertEquals(expected, (long) await(job.task().apply(ctx)).getInteger("prompted"));

        assertEquals(expected, count("SELECT count(*) FROM notifications WHERE kind = 'pulse.prompt'"));
        assertEquals(expected, count("""
                SELECT count(*) FROM notifications WHERE kind = 'pulse.prompt'
                AND text = 'Weekly pulse · week of 28 Sep — two questions, 20 seconds' AND link = '/progress?pulse=open'"""));

        // Priya answers; the next run that week prompts only the others
        await(owner.execute("""
                INSERT INTO weekly_pulse (person_id, week_start, mood, workload)
                VALUES ((SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'), '2026-09-28', 4, 3)""",
                io.vertx.sqlclient.Tuple.tuple()));

        int sent = await(new ProgressService(ctx).prompt());

        assertEquals(expected - 1, sent);
    }

    private static long count(String sql) throws Exception
    {
        return ((Number) await(owner.queryOne(sql, io.vertx.sqlclient.Tuple.tuple())).getValue("count")).longValue();
    }
}
