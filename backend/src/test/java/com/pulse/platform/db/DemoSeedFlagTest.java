package com.pulse.platform.db;

import com.pulse.platform.config.RuntimeProfile;
import io.vertx.pgclient.PgPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 2.4: demo seed files load only with PULSE_DEMO_SEED on; production refuses demo accounts. */
class DemoSeedFlagTest {

    private TestDb db;
    private PgPool owner;
    private ScriptCatalog platform;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDb.open();
        owner = db.ownerPool();
        platform = ScriptCatalog.load(getClass().getClassLoader(), List.of(ScriptCatalog.PLATFORM));
        await(MigrationRunner.migrate(owner, platform));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void demoSeedIsSkippedWhenTheFlagIsOffAndAppliesOnceTurnedOn() throws Exception {
        List<SqlScript> off = await(MigrationRunner.seed(owner, platform, false));
        assertEquals(List.of("S100"), off.stream().map(SqlScript::label).toList());
        assertEquals(0L, count("SELECT count(*) FROM users"));
        assertEquals(3L, count("SELECT count(*) FROM roles"), "base seed still loads");
        assertEquals(0L, count("SELECT count(*) FROM pulse_schema_history WHERE kind = 'SEED' AND version IN (110, 120, 130)"));
        assertEquals(0L, count("SELECT count(*) FROM pillars"));

        List<SqlScript> on = await(MigrationRunner.seed(owner, platform, true));
        assertEquals(List.of("S110", "S120", "S130", "S150"), on.stream().map(SqlScript::label).toList());
        assertTrue(count("SELECT count(*) FROM users WHERE email = 'hr@pulse.demo'") == 1L);
    }

    @Test
    void demoSeedsAreRecognisedByName() {
        assertTrue(platform.seeds().stream().filter(SqlScript::demo).map(SqlScript::label).toList().containsAll(List.of("S110", "S120", "S130", "S150")));
        assertTrue(platform.seeds().stream().filter(s -> !s.demo()).map(SqlScript::label).toList().contains("S100"));
    }

    @Test
    void productionRefusesToStartWhileADemoAccountExists() throws Exception {
        RuntimeProfile production = new RuntimeProfile(RuntimeProfile.PRODUCTION, false);
        await(MigrationRunner.seed(owner, platform, false));
        await(DatabaseBootstrap.refuseDemoAccountsInProduction(owner, production)); // no demo accounts: fine

        await(MigrationRunner.seed(owner, platform, true));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> await(DatabaseBootstrap.refuseDemoAccountsInProduction(owner, production)));
        assertTrue(e.getCause().getMessage().contains("demo account"), e.getCause().getMessage());

        // Development does not care.
        await(DatabaseBootstrap.refuseDemoAccountsInProduction(owner, new RuntimeProfile(RuntimeProfile.DEVELOPMENT, true)));
    }

    private long count(String sql) throws Exception {
        return await(owner.query(sql).execute()).iterator().next().getLong(0);
    }
}
