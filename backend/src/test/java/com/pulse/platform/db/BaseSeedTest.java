package com.pulse.platform.db;

import io.vertx.core.json.JsonArray;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 2.3: V100 plus the generated base and demo seed, loaded into an empty schema. */
class BaseSeedTest {

    /** Demo accounts and roles from reference/HANDOFF.md. */
    private static final Map<String, String> HANDOFF = Map.ofEntries(
            Map.entry("hr@pulse.demo", "Admin"),
            Map.entry("rahul@pulse.demo", "Manager"),
            Map.entry("priya@pulse.demo", "Employee"),
            Map.entry("arjun@pulse.demo", "Employee"),
            Map.entry("neha@pulse.demo", "Employee"),
            Map.entry("kiran@pulse.demo", "Employee"),
            Map.entry("dev@pulse.demo", "Employee"),
            Map.entry("sara@pulse.demo", "Employee"),
            Map.entry("rohit@pulse.demo", "Employee"),
            Map.entry("anjali@pulse.demo", "Employee"),
            Map.entry("sana@pulse.demo", "Employee"),
            Map.entry("meera@pulse.demo", "Employee"));

    private static TestDb db;
    private static PgPool owner;

    @BeforeAll
    static void loadSeed() throws Exception {
        db = TestDb.open();
        owner = db.ownerPool();
        ScriptCatalog platform = ScriptCatalog.load(BaseSeedTest.class.getClassLoader(), List.of(ScriptCatalog.PLATFORM));
        await(MigrationRunner.migrate(owner, platform));
        List<SqlScript> seeded = await(MigrationRunner.seed(owner, platform, true));
        assertEquals(List.of(100, 110, 120, 130, 150), seeded.stream().map(SqlScript::version).toList());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void everyDemoAccountExistsWithItsHandoffRole() throws Exception {
        Map<String, String> roles = new HashMap<>();
        for (Row r : await(owner.query("SELECT u.email, r.name FROM users u JOIN roles r ON r.id = u.role_id").execute())) {
            roles.put(r.getString(0), r.getString(1));
        }
        HANDOFF.forEach((email, role) -> assertEquals(role, roles.get(email), email));
    }

    @Test
    void demoAccountsSignInWithDemo123AsBcryptCost12() throws Exception {
        for (Row r : await(owner.query("SELECT email, password_hash, "
                + "public.crypt('demo123', password_hash) = password_hash AS ok FROM users").execute())) {
            assertTrue(r.getString("password_hash").startsWith("$2a$12$"), r.getString("email"));
            assertTrue(r.getBoolean("ok"), r.getString("email"));
        }
    }

    @Test
    void peopleCountEqualsThePrototype() throws Exception {
        JsonArray prototypePeople = new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "people.json")));
        assertEquals((long) prototypePeople.size(), count("SELECT count(*) FROM people"));
        assertEquals(0L, count("SELECT count(*) FROM people WHERE user_id IS NULL OR level IS NULL "
                + "OR department IS NULL OR joined_on IS NULL OR ctc IS NULL"));
    }

    @Test
    void peopleHaveTheirManagerAsTheCurrentReportingLine() throws Exception {
        Row priya = await(owner.preparedQuery("SELECT m.email FROM reporting_lines l "
                        + "JOIN users u ON u.id = l.user_id JOIN users m ON m.id = l.manager_id "
                        + "WHERE u.email = $1 AND l.valid_to IS NULL")
                .execute(Tuple.of("priya@pulse.demo"))).iterator().next();
        assertEquals("rahul@pulse.demo", priya.getString(0));
        assertEquals(0L, count("SELECT count(*) FROM users u WHERE u.email <> 'hr@pulse.demo' AND NOT EXISTS "
                + "(SELECT 1 FROM reporting_lines l WHERE l.user_id = u.id AND l.valid_to IS NULL)"));
    }

    @Test
    void rolesCarryTheReferencePermissionMatrix() throws Exception {
        assertEquals(19L, permissions("Admin"));
        assertEquals(12L, permissions("Manager"));
        assertEquals(4L, permissions("Employee"));
        assertEquals(3L, count("SELECT count(*) FROM roles WHERE built_in"));
    }

    @Test
    void calendarAndRuleDefaultsAreSeeded() throws Exception {
        assertEquals(13L, count("SELECT count(*) FROM sprints WHERE source = 'seed'"));
        assertEquals(4L, count("SELECT count(*) FROM releases WHERE source = 'seed'"));
        assertEquals(1L, count("SELECT count(*) FROM holidays WHERE holiday_on = '2026-10-02'"));
        assertEquals("2026-10-09", await(owner.query("SELECT work_ends_on::text FROM sprints WHERE external_id = 'S43'")
                .execute()).iterator().next().getString(0));
        Row weights = await(owner.query("SELECT value FROM rules WHERE key = 'weights'").execute()).iterator().next();
        assertNotNull(weights.getJsonObject(0));
        assertEquals(35, weights.getJsonObject(0).getInteger("delivery"));
        assertEquals(1L, count("SELECT count(*) FROM rule_versions WHERE version = 1"));
        assertEquals(0L, count("SELECT count(*) FROM rules r WHERE NOT (SELECT rule_set FROM rule_versions "
                + "WHERE version = 1) ? r.key"));
    }

    /** Tasks 4.3 and 4.4: V110 and S120 carry the prototype's pillars, 360° ratings and culture signals. */
    @Test
    void performanceInputsAreSeeded() throws Exception {
        JsonArray prototypePeople = new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "people.json")));
        int n = prototypePeople.size();
        assertEquals((long) n, count("SELECT count(*) FROM pillars WHERE quarter = '2026-Q3'"));
        assertEquals((long) n * 12, count("SELECT count(*) FROM culture_signals WHERE is_simulated AND quarter = '2026-Q3'"));
        assertEquals((long) n * 11, count("SELECT count(*) FROM culture_ratings WHERE rater_group = 'Combined'"));
        for (Object o : prototypePeople) {
            io.vertx.core.json.JsonObject p = (io.vertx.core.json.JsonObject) o;
            Row r = await(owner.preparedQuery("SELECT x.delivery::float8, x.quality::float8, x.okr::float8, x.growth::float8 "
                    + "FROM pillars x JOIN people pe ON pe.id = x.person_id WHERE pe.external_id = $1")
                    .execute(Tuple.of(p.getString("id")))).iterator().next();
            io.vertx.core.json.JsonObject pillars = p.getJsonObject("pillars");
            assertEquals(pillars.getDouble("delivery"), r.getDouble(0), p.getString("id"));
            assertEquals(pillars.getDouble("quality"), r.getDouble(1), p.getString("id"));
            assertEquals(pillars.getDouble("okr"), r.getDouble(2), p.getString("id"));
            assertEquals(pillars.getDouble("growth"), r.getDouble(3), p.getString("id"));
        }
        assertEquals(4.8, await(owner.query("SELECT c.rating::float8 FROM culture_ratings c JOIN people pe ON pe.id = c.person_id "
                + "WHERE pe.external_id = 'ps' AND c.value_name = 'Moral & Ethics' AND c.rater_group = 'Combined'")
                .execute()).iterator().next().getDouble(0));
    }

    private long permissions(String role) throws Exception {
        return await(owner.preparedQuery("SELECT count(*) FROM role_permissions p JOIN roles r ON r.id = p.role_id "
                + "WHERE r.name = $1").execute(Tuple.of(role))).iterator().next().getLong(0);
    }

    private long count(String sql) throws Exception {
        return await(owner.query(sql).execute()).iterator().next().getLong(0);
    }
}
