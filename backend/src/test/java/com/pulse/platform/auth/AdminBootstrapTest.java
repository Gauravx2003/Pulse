package com.pulse.platform.auth;

import com.pulse.platform.config.RuntimeProfile;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.TestDb;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 24.9 (change pulse-v2-live-connectors): the first Admin of a database without the demo seed comes
 * from PULSE_ADMIN_EMAIL / PULSE_ADMIN_PASSWORD, once, and never changes an existing account.
 */
class AdminBootstrapTest
{
    private static final RuntimeProfile DEV = new RuntimeProfile(RuntimeProfile.DEVELOPMENT, false);

    private static final RuntimeProfile PROD = new RuntimeProfile(RuntimeProfile.PRODUCTION, false);

    private static final String PASSWORD = "Live-admin pass 9";

    private TestDb db;

    private PgPool owner;

    private ScriptCatalog platform;

    @BeforeEach
    void setUp() throws Exception
    {
        db = TestDb.open();
        owner = db.ownerPool();
        platform = ScriptCatalog.load(getClass().getClassLoader(), List.of(ScriptCatalog.PLATFORM));
        await(MigrationRunner.migrate(owner, platform));
        await(MigrationRunner.seed(owner, platform, false));
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
    void createsAnActiveAdminOnceWithTheHashedPassword() throws Exception
    {
        String id = await(AdminBootstrap.run(db.vertx(), owner, settings("Lead@Office.Example", PASSWORD).put("adminName", "Office Lead"), DEV));

        assertNotNull(id);

        Row user = one("SELECT u.email, u.name, u.status, u.password_hash, r.name AS role FROM users u "
                + "JOIN roles r ON r.id = u.role_id WHERE u.id = '" + id + "'");

        assertEquals("lead@office.example", user.getString("email"));
        assertEquals("Office Lead", user.getString("name"));
        assertEquals("Active", user.getString("status"));
        assertEquals("Admin", user.getString("role"));
        assertFalse(user.getString("password_hash").contains(PASSWORD), "stored as a hash");
        assertTrue(await(PasswordHasher.verify(db.vertx(), PASSWORD, user.getString("password_hash"))));

        Row audit = one("SELECT action, actor_id, summary::text AS summary FROM audit_log WHERE target_id = '" + id + "'");

        assertEquals("user.created", audit.getString("action"));
        assertNull(audit.getValue("actor_id"));
        assertFalse(audit.getString("summary").contains(PASSWORD));

        // a restart with the same or other settings changes nothing: an Admin exists
        assertNull(await(AdminBootstrap.run(db.vertx(), owner, settings("lead@office.example", "Another pass 77"), DEV)));
        assertNull(await(AdminBootstrap.run(db.vertx(), owner, settings("second@office.example", PASSWORD), DEV)));
        assertEquals(1L, count("SELECT count(*) FROM users"));
        assertTrue(await(PasswordHasher.verify(db.vertx(), PASSWORD, one("SELECT password_hash FROM users").getString(0))));
    }

    @Test
    void doesNothingWithoutTheEmail() throws Exception
    {
        assertNull(await(AdminBootstrap.run(db.vertx(), owner, new JsonObject().put("adminEmail", ""), DEV)));
        assertEquals(0L, count("SELECT count(*) FROM users"));
    }

    @Test
    void refusesAMissingOrWeakPasswordAndABadEmail() throws Exception
    {
        assertRefused(settings("lead@office.example", null), "PULSE_ADMIN_PASSWORD is required");
        assertRefused(settings("lead@office.example", "short"), "at least 8");
        assertRefused(settings("lead@office.example", "lead@office.example"), "email or name");
        assertRefused(settings("not-an-email", PASSWORD), "not an email");
        assertEquals(0L, count("SELECT count(*) FROM users"));
    }

    @Test
    void neverPromotesAnExistingAccount() throws Exception
    {
        exec("INSERT INTO users (email, name, role_id, status) SELECT 'dev@office.example', 'Dev', id, 'Active' "
                + "FROM roles WHERE name = 'Employee'");

        assertRefused(settings("DEV@office.example", PASSWORD), "already exists");
        assertEquals("Employee", one("SELECT r.name FROM users u JOIN roles r ON r.id = u.role_id").getString(0));
    }

    @Test
    void productionRefusesADemoAddress() throws Exception
    {
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> await(AdminBootstrap.run(db.vertx(), owner, settings("admin@pulse.demo", PASSWORD), PROD)));

        assertTrue(e.getCause().getMessage().contains("demo address"), e.getCause().getMessage());
        assertNotNull(await(AdminBootstrap.run(db.vertx(), owner, settings("admin@office.example", PASSWORD), PROD)));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject settings(String email, String password)
    {
        JsonObject config = new JsonObject().put("adminEmail", email);

        return password == null ? config.put("adminPassword", "") : config.put("adminPassword", password);
    }

    private void assertRefused(JsonObject settings, String message)
    {
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> await(AdminBootstrap.run(db.vertx(), owner, settings, DEV)));

        assertTrue(e.getCause().getMessage().contains(message), e.getCause().getMessage());
        assertFalse(e.getCause().getMessage().contains(PASSWORD), "the password is never echoed");
    }

    private Row one(String sql) throws Exception
    {
        return await(owner.query(sql).execute()).iterator().next();
    }

    private long count(String sql) throws Exception
    {
        return one(sql).getLong(0);
    }

    private void exec(String sql) throws Exception
    {
        await(owner.query(sql).execute());
    }
}
