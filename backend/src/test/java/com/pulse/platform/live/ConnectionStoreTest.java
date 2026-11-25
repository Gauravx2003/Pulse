package com.pulse.platform.live;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleRegistry;
import com.pulse.platform.secrets.SecretBox;
import com.pulse.platform.secrets.SecretKeyException;
import com.pulse.testsupport.TestApi;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 24.2 (change pulse-v2-live-connectors): live secrets stored encrypted in
 * {@code integration_connections}; refused without a key; unreadable when tampered; only last4 out.
 */
class ConnectionStoreTest
{
    private static final String TOOL = StubLiveConnector.TOOL;

    private static final String PASSWORD = "Jira-P4ssw0rd-Plain-9z";

    private static TestDb db;

    private static SqlExecutor sql;

    private static String key;

    private static LiveConnectorRegistry registry;

    @BeforeAll
    static void start() throws Exception
    {
        db = TestDb.open();

        ClassLoader loader = ConnectionStoreTest.class.getClassLoader();

        TestApi.await(MigrationRunner.migrate(db.ownerPool(), ScriptCatalog.load(loader,
                ScriptCatalog.sourcesFor(ModuleRegistry.discover(loader).modules()))));

        sql = new SqlExecutor(db.ownerPool());
        key = SecretBox.generateKey();
        registry = new LiveConnectorRegistry().register(new StubLiveConnector());
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (db != null)
        {
            db.close();
        }
    }

    @BeforeEach
    void clean() throws Exception
    {
        TestApi.await(sql.execute("DELETE FROM integration_connections", Tuple.tuple()));
    }

    private static ConnectionStore store(String secretKey)
    {
        return new ConnectionStore(sql, SecretBox.from(db.vertx(),
                new JsonObject().put(SecretBox.CONFIG_KEY, secretKey)), registry);
    }

    private static JsonObject config()
    {
        return new JsonObject().put("baseUrl", "http://172.16.10.6:8080").put("username", "pulse-reader");
    }

    private static ApiException failure(Future<?> future)
    {
        ExecutionException e = assertThrows(ExecutionException.class, () -> TestApi.await(future));

        return assertInstanceOf(ApiException.class, e.getCause());
    }

    /** Every row of every table in the test schema, as text: the "database dump". */
    private static String dump() throws Exception
    {
        JsonArray tables = TestApi.await(sql.queryList("SELECT table_name AS t FROM information_schema.tables "
                + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'", Tuple.tuple()));

        StringBuilder out = new StringBuilder();

        for (Object t : tables)
        {
            String table = ((JsonObject) t).getString("t");

            JsonObject row = TestApi.await(sql.queryOne("SELECT coalesce(string_agg(x::text, E'\\n'), '') AS d FROM \""
                    + table + "\" x", Tuple.tuple()));

            out.append(row.getString("d")).append('\n');
        }

        return out.toString();
    }

    @Test
    void saveStoresOnlyCiphertextAndLast4() throws Exception
    {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level level = root.getLevel();

        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);

        JsonObject view;

        try
        {
            view = TestApi.await(store(key).save(TOOL, config(), PASSWORD, "2027-01-31"));
        }
        finally
        {
            root.detachAppender(appender);
            root.setLevel(level);
        }

        // the "API response": config and last4, nothing secret
        assertEquals("n-9z", view.getString("last4"));
        assertEquals(true, view.getBoolean("hasSecret"));
        assertEquals("connected", view.getString("state"));
        assertEquals(config(), view.getJsonObject("config"));
        assertNotNull(view.getString("rotatedAt"));

        for (String forbidden : Set.of("ciphertext", "iv", "secret", "secret_ciphertext", "secret_iv"))
        {
            assertFalse(view.containsKey(forbidden), forbidden + " in view: " + view.encode());
        }

        assertFalse(view.encode().contains(PASSWORD));

        JsonObject raw = TestApi.await(sql.queryOne("SELECT secret_ciphertext IS NOT NULL AS c, "
                + "octet_length(secret_iv) AS iv FROM integration_connections WHERE tool = $1", Tuple.of(TOOL)));

        assertEquals(true, raw.getBoolean("c"));
        assertEquals(12, raw.getInteger("iv"));

        assertFalse(dump().contains(PASSWORD), "plain password in the database dump");

        for (ILoggingEvent event : appender.list)
        {
            // checked before the redacting converter: nothing logs the secret in the first place
            assertFalse(String.valueOf(event.getFormattedMessage()).contains(PASSWORD), "plain password logged");
        }

        assertEquals(PASSWORD, TestApi.await(store(key).secret(TOOL)));
    }

    @Test
    void saveIsRefusedWithoutTheKeyAndStoresNothing() throws Exception
    {
        ApiException e = failure(store("").save(TOOL, config(), PASSWORD, null));

        assertEquals(422, e.status());
        assertEquals(SecretKeyException.MISSING, e.code());
        assertFalse(e.getMessage().contains(PASSWORD));

        assertNull(TestApi.await(store(key).view(TOOL)));
    }

    @Test
    void tamperedCiphertextBlocksWithKey() throws Exception
    {
        TestApi.await(store(key).save(TOOL, config(), PASSWORD, null));

        TestApi.await(sql.execute("UPDATE integration_connections SET secret_ciphertext = "
                + "set_byte(secret_ciphertext, 0, get_byte(secret_ciphertext, 0) # 1) WHERE tool = $1", Tuple.of(TOOL)));

        ApiException e = failure(store(key).secret(TOOL));

        assertInstanceOf(SecretKeyException.class, e);
        assertEquals(SecretKeyException.UNREADABLE, e.code());
        assertEquals("key", SecretKeyException.SYNC_ERROR);
    }

    @Test
    void changedKeyMakesTheSecretUnreadable() throws Exception
    {
        TestApi.await(store(key).save(TOOL, config(), PASSWORD, null));

        assertEquals(SecretKeyException.UNREADABLE, failure(store(SecretBox.generateKey()).secret(TOOL)).code());
        assertEquals(SecretKeyException.UNREADABLE, failure(store("").secret(TOOL)).code());
    }

    @Test
    void replacingTheSecretBumpsVersionAndClearKeepsConfig() throws Exception
    {
        JsonObject first = TestApi.await(store(key).save(TOOL, config(), PASSWORD, null));
        JsonObject second = TestApi.await(store(key).save(TOOL, config(), "Another-Secret-Value-77", null));

        assertEquals(first.getInteger("version") + 1, second.getInteger("version"));
        assertEquals("e-77", second.getString("last4"));
        assertEquals("Another-Secret-Value-77", TestApi.await(store(key).secret(TOOL)));

        assertTrue(TestApi.await(store(key).clearSecret(TOOL)));

        JsonObject cleared = TestApi.await(store(key).view(TOOL));

        assertEquals("disconnected", cleared.getString("state"));
        assertEquals(false, cleared.getBoolean("hasSecret"));
        assertNull(cleared.getString("last4"));
        assertEquals(config(), cleared.getJsonObject("config"));
        assertEquals(SecretKeyException.UNREADABLE, failure(store(key).secret(TOOL)).code());
    }

    @Test
    void seededToolsAreNotStoredHere() throws Exception
    {
        ApiException e = failure(store(key).save("slack", new JsonObject(), "xoxb-1234567890-abcdef", null));

        assertEquals(422, e.status());
        assertEquals("connector_not_live", e.code());
        assertNull(TestApi.await(store(key).view("slack")));
    }
}
