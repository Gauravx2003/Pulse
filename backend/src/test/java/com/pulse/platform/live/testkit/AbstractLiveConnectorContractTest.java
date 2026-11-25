package com.pulse.platform.live.testkit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.pulse.platform.accounts.InMemoryExternalAccountService;
import com.pulse.platform.live.BaseUrls;
import com.pulse.platform.live.GuardedHttp;
import com.pulse.platform.live.LiveConnector;
import com.pulse.platform.live.LiveContext;
import com.pulse.platform.live.LiveHttpException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The shared test every live connector extends with its fixtures (task 24.4, design.md decision 8).
 * It runs the connector against {@link LiveMockServer} through a real {@link GuardedHttp}, with an
 * in-memory ingest store that upserts by (entity, external_id) like {@code IngestService}, and
 * {@link InMemoryExternalAccountService} for person matching. No database, no outside network.
 *
 * <p>It checks, for any connector:
 * <ul>
 *   <li>paged 200 responses ingest, as valid batches, over at least {@link #minimumPages()} pages</li>
 *   <li>a re-sync gives no duplicates</li>
 *   <li>the second run sends the cursor of the first ({@link #assertCursorUsed})</li>
 *   <li>401, 429, 500, a stall and a 302 to another host on the second page each fail the read with
 *       that code, keep the first page, send no further request, and never reach the other host</li>
 *   <li>only GET requests, only to the declared origin</li>
 *   <li>the secret appears in no log line, result, batch or error</li>
 *   <li>unmatched accounts are recorded ({@link #expectedUnmatchedAccounts()})</li>
 * </ul>
 *
 * <p>A connector's test supplies its fixtures folder, config, secret and expectations; see
 * {@code docs/live-connectors.md}, "How to add a connector".
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractLiveConnectorContractTest
{
    // ------------------------------------------------------------------ what a connector supplies

    /** A fresh connector instance. */
    protected abstract LiveConnector connector();

    /** Folder under {@code live-fixtures/} holding {@code routes.json}. */
    protected abstract String fixtures();

    /** The connector's config pointing at the mock server ({@code baseUrl} is {@code http://127.0.0.1:<port>}). */
    protected abstract JsonObject config(String baseUrl);

    /** A test secret; it must never show up in logs, results or errors. */
    protected abstract String secret();

    /**
     * Asserts that the second run used {@code cursor}: the requests are those the mock received
     * during that run (each {@code {n, method, path, query, uri, headers}}).
     */
    protected abstract void assertCursorUsed(String cursor, List<JsonObject> requests);

    /** Account ids a full sync must leave unmatched in {@code external_accounts}. */
    protected abstract Set<String> expectedUnmatchedAccounts();

    /** Registers the people the fixtures' accounts may match by email. None by default. */
    protected void people(InMemoryExternalAccountService accounts)
    {
    }

    /**
     * Every form of the secret that must never be logged or returned: the secret itself by default; a
     * Basic auth connector adds its {@code base64(username:password)}.
     */
    protected List<String> secretForms()
    {
        return List.of(secret());
    }

    /** The fewest pages a full sync of the fixtures hands to the sink; at least 2 for the failure cases. */
    protected int minimumPages()
    {
        return 2;
    }

    /** The read timeout used for the stall case, in ms (the production value is 15 s). */
    protected long readTimeoutMs()
    {
        return 500;
    }

    // ------------------------------------------------------------------ fixture

    protected Vertx vertx;

    protected LiveMockServer mock;

    /** A second origin: the target of the 302 case. It must never receive a request. */
    private HttpServer otherHost;

    private String otherBaseUrl;

    private final AtomicInteger otherHits = new AtomicInteger();

    protected InMemoryExternalAccountService accounts;

    /** entity -> external_id -> last record ingested; the upsert of {@code IngestService}. */
    protected Map<String, Map<String, JsonObject>> store;

    @BeforeAll
    void startServers() throws Exception
    {
        vertx = Vertx.vertx();

        mock = await(LiveMockServer.start(vertx, fixtures()));

        otherHost = await(vertx.createHttpServer().requestHandler(req ->
        {
            otherHits.incrementAndGet();

            req.response().end("{}");
        }).listen(0, "127.0.0.1"));

        otherBaseUrl = "http://127.0.0.1:" + otherHost.actualPort();
    }

    @AfterAll
    void stopServers() throws Exception
    {
        mock.close();

        await(otherHost.close());
        await(vertx.close());
    }

    @BeforeEach
    void resetState()
    {
        mock.reset();
        otherHits.set(0);

        accounts = new InMemoryExternalAccountService();

        people(accounts);

        store = new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------ the contract

    @Test
    void mockOriginNeedsTheLoopbackFlag()
    {
        assertFalse(BaseUrls.validate(mock.baseUrl(), false).getBoolean("valid"),
                "a loopback base URL must be refused without the test-only flag");

        assertTrue(BaseUrls.validate(mock.baseUrl(), true).getBoolean("valid"));

        JsonArray origins = connector().allowedOrigins(config(mock.baseUrl()));

        assertTrue(containsOrigin(origins, mock.origin()),
                "the connector must declare the mock server's origin for this config: " + origins);
    }

    @Test
    void testConnectionPassesOn200() throws Exception
    {
        JsonObject result = testConnection();

        assertEquals(Boolean.TRUE, result.getBoolean("ok"), "test connection on recorded 200s: " + result);
        assertEquals(200, result.getInteger("status"));

        assertNoSecret("test connection result", result.encode());
        assertOnlyGetToMock();
    }

    @Test
    void pagedIngest() throws Exception
    {
        JsonObject run = sync(null);

        assertSucceeded(run);
        assertTrue(run.getInteger("pages") >= minimumPages(),
                "fixtures must give at least " + minimumPages() + " pages, got " + run.getInteger("pages"));
        assertTrue(rows() > 0, "a full sync ingests rows");
        assertNotNull(run.getString("cursor"), "a successful sync resolves to a cursor");
    }

    @Test
    void reSyncGivesNoDuplicates() throws Exception
    {
        JsonObject first = sync(null);

        Map<String, Integer> afterFirst = counts();

        sync(null);

        assertEquals(afterFirst, counts(), "a second full sync must not add rows");

        sync(first.getString("cursor"));

        assertEquals(afterFirst, counts(), "an incremental sync over the same fixtures must not add rows");
    }

    @Test
    void secondRunUsesTheCursor() throws Exception
    {
        String cursor = sync(null).getString("cursor");

        assertNotNull(cursor);

        mock.reset();

        JsonObject second = sync(cursor);

        assertSucceeded(second);
        assertCursorUsed(cursor, mock.requests());
    }

    @ParameterizedTest(name = "{0} on page 2")
    @ValueSource(strings = {"401", "429", "500", "stall", "redirect"})
    void failureOnPageTwoKeepsPageOne(String kind) throws Exception
    {
        // a clean run tells which request produced page 2 and which rows page 1 holds
        List<Integer> requestsAtPage = new ArrayList<>();

        List<Set<String>> keysAtPage = new ArrayList<>();

        JsonObject clean = sync(null, () ->
        {
            requestsAtPage.add(mock.requests().size());
            keysAtPage.add(keys());
        });

        assertSucceeded(clean);
        assertTrue(requestsAtPage.size() >= 2, "fixtures must give at least 2 pages");

        int pageTwoRequest = requestsAtPage.get(1);

        Set<String> pageOneKeys = keysAtPage.get(0);

        // the faulty run
        resetState();

        JsonObject fault = switch (kind)
        {
            case "stall" -> LiveMockServer.stall();
            case "redirect" -> LiveMockServer.redirect(otherBaseUrl + "/elsewhere");
            default -> LiveMockServer.status(Integer.parseInt(kind));
        };

        mock.faultAt(pageTwoRequest, fault);

        JsonObject run = sync(null);

        assertTrue(run.getBoolean("failure", false), kind + " on page 2 must fail the read");

        Throwable failure = failures.get(0);

        LiveHttpException http = assertInstanceOf(LiveHttpException.class, rootCause(failure),
                "a failed page fails with LiveHttpException, got " + failure);

        String expected = switch (kind)
        {
            case "stall" -> LiveHttpException.TIMEOUT;
            case "redirect" -> LiveHttpException.REDIRECT;
            default -> kind;
        };

        assertEquals(expected, http.code());
        assertEquals(1, run.getInteger("pages"), "only page 1 reaches the sink");
        assertEquals(pageOneKeys, keys(), "page 1 rows stay, page 2 rows are not ingested");
        assertEquals(pageTwoRequest, mock.requests().size(), "no request after the failure (no retry)");
        assertEquals(0, otherHits.get(), "the redirect target must never be reached");

        assertNoSecret("failure", messages(failure));
        assertOnlyGetToMock();
    }

    @Test
    void onlyGetRequestsToTheDeclaredOrigin() throws Exception
    {
        testConnection();
        sync(null);

        assertFalse(mock.requests().isEmpty(), "the connector must reach the mock server");
        assertOnlyGetToMock();
    }

    @Test
    void noSecretInLogsOrResponses() throws Exception
    {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        Level level = root.getLevel();

        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);

        List<String> outputs = new ArrayList<>();

        try
        {
            outputs.add(testConnection().encode());

            JsonObject ok = sync(null);

            outputs.add(ok.encode());

            String path = firstPath();

            mock.reset().faultOn(path, LiveMockServer.status(401));

            outputs.add(testConnection().encode());

            resetState();

            mock.faultAt(1, LiveMockServer.status(401));

            sync(null);

            failures.forEach(f -> outputs.add(messages(f)));
        }
        finally
        {
            root.detachAppender(appender);
            root.setLevel(level);
            appender.stop();
        }

        for (ILoggingEvent event : appender.list)
        {
            StringBuilder line = new StringBuilder(event.getFormattedMessage() == null ? "" : event.getFormattedMessage());

            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause())
            {
                line.append(' ').append(t.getMessage());
            }

            assertNoSecret("log line", line.toString());
        }

        for (String out : outputs)
        {
            assertNoSecret("result", out);
        }

        for (Map<String, JsonObject> rows : store.values())
        {
            rows.values().forEach(r -> assertNoSecret("ingested row", r.encode()));
        }
    }

    @Test
    void unmatchedAccountsRecorded() throws Exception
    {
        sync(null);

        Set<String> unmatched = new HashSet<>();

        JsonArray all = accounts.all();

        for (int i = 0; i < all.size(); i++)
        {
            JsonObject row = all.getJsonObject(i);

            assertEquals(connector().tool(), row.getString("tool"), "accounts are recorded under the tile id");

            if (row.getString("personId") == null)
            {
                unmatched.add(row.getString("accountId"));
            }
        }

        assertTrue(unmatched.containsAll(expectedUnmatchedAccounts()),
                "expected unmatched " + expectedUnmatchedAccounts() + ", recorded " + unmatched);
    }

    // ------------------------------------------------------------------ running the connector

    /** Failures of the last {@link #sync} (at most one). */
    private final List<Throwable> failures = new ArrayList<>();

    /** A guarded client and context for the mock server, as the platform builds them. */
    protected LiveContext context()
    {
        JsonObject config = config(mock.baseUrl());

        LiveConnector connector = connector();

        GuardedHttp http = new GuardedHttp(vertx, connector.tool(), connector.allowedOrigins(config),
                new JsonObject().put("readTimeoutMs", readTimeoutMs()), InetAddress::getAllByName);

        return LiveContext.of(connector.tool(), config, secret(), http, accounts);
    }

    protected JsonObject testConnection() throws Exception
    {
        try (LiveContext ctx = context())
        {
            return await(connector().testConnection(ctx));
        }
    }

    /**
     * Runs {@code fetchPages(since)} into the in-memory store. Resolves to {@code {pages, cursor}} on
     * success, or {@code {pages, failure: true}} with the cause kept for the assertions.
     */
    protected JsonObject sync(String since) throws Exception
    {
        return sync(since, () ->
        {
        });
    }

    private JsonObject sync(String since, Runnable afterPage) throws Exception
    {
        failures.clear();

        LiveConnector connector = connector();

        AtomicInteger pages = new AtomicInteger();

        try (LiveContext ctx = context())
        {
            Future<JsonObject> read = connector.fetchPages(ctx, since, batch ->
            {
                try
                {
                    ingest(connector, batch);
                }
                catch (AssertionError | RuntimeException e)
                {
                    return Future.failedFuture(e);
                }

                pages.incrementAndGet();

                afterPage.run();

                return Future.succeededFuture();
            });

            try
            {
                JsonObject result = await(read);

                return new JsonObject()
                        .put("pages", pages.get())
                        .put("cursor", result == null ? null : result.getString("cursor"));
            }
            catch (ExecutionException e)
            {
                if (e.getCause() instanceof AssertionError bad)
                {
                    throw bad;
                }

                failures.add(e.getCause());

                return new JsonObject().put("pages", pages.get()).put("failure", true);
            }
        }
    }

    /** Checks a batch against the {@code ConnectorPort} format and upserts its records. */
    private void ingest(LiveConnector connector, JsonObject batch)
    {
        assertNotNull(batch, "a page must not be null");
        assertNoSecret("batch", batch.encode());

        String source = batch.getString("source");

        assertTrue(source == null || source.equals(connector.source()),
                "batch source must be the connector's source, got " + source);

        JsonArray entities = batch.getJsonArray("entities");

        assertNotNull(entities, "a batch has an entities array");

        for (int i = 0; i < entities.size(); i++)
        {
            JsonObject entity = entities.getJsonObject(i);

            String name = entity.getString("entity");

            assertTrue(name != null && !name.isBlank(), "every entity has a table name");

            JsonArray records = entity.getJsonArray("records");

            assertNotNull(records, "entity " + name + " has a records array");

            Map<String, JsonObject> rows = store.computeIfAbsent(name, k -> new LinkedHashMap<>());

            for (int r = 0; r < records.size(); r++)
            {
                JsonObject record = records.getJsonObject(r);

                String externalId = record.getString("external_id");

                assertTrue(externalId != null && !externalId.isBlank(),
                        "every " + name + " record has an external_id");

                rows.put(externalId, record.copy());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    protected int rows()
    {
        return store.values().stream().mapToInt(Map::size).sum();
    }

    private Map<String, Integer> counts()
    {
        Map<String, Integer> out = new LinkedHashMap<>();

        store.forEach((entity, rows) -> out.put(entity, rows.size()));

        return out;
    }

    private Set<String> keys()
    {
        Set<String> out = new HashSet<>();

        store.forEach((entity, rows) -> rows.keySet().forEach(id -> out.add(entity + "/" + id)));

        return out;
    }

    private String firstPath()
    {
        mock.reset();

        try
        {
            testConnection();
        }
        catch (Exception e)
        {
            fail("test connection on the fixtures failed: " + e);
        }

        List<JsonObject> requests = mock.requests();

        assertFalse(requests.isEmpty(), "test connection must send a request");

        return requests.get(0).getString("path");
    }

    protected void assertNoSecret(String where, String text)
    {
        if (text == null)
        {
            return;
        }

        for (String form : secretForms())
        {
            assertFalse(text.contains(form), "the secret appears in a " + where);
        }
    }

    private void assertOnlyGetToMock()
    {
        for (JsonObject request : mock.requests())
        {
            assertEquals("GET", request.getString("method"), "only GET requests: " + request.getString("path"));
        }

        assertEquals(0, otherHits.get(), "no request may reach an undeclared origin");
    }

    private static boolean containsOrigin(JsonArray origins, JsonObject origin)
    {
        for (int i = 0; i < origins.size(); i++)
        {
            JsonObject o = origins.getJsonObject(i);

            if (origin.getString("scheme").equals(o.getString("scheme"))
                    && origin.getString("host").equals(o.getString("host"))
                    && origin.getInteger("port").equals(o.getInteger("port")))
            {
                return true;
            }
        }

        return false;
    }

    private static Throwable rootCause(Throwable t)
    {
        Throwable cur = t;

        while (!(cur instanceof LiveHttpException) && cur.getCause() != null && cur.getCause() != cur)
        {
            cur = cur.getCause();
        }

        return cur;
    }

    private static String messages(Throwable t)
    {
        StringBuilder out = new StringBuilder();

        for (Throwable cur = t; cur != null; cur = cur.getCause() == cur ? null : cur.getCause())
        {
            out.append(cur).append(' ');
        }

        return out.toString();
    }

    private static void assertSucceeded(JsonObject run)
    {
        assertFalse(run.getBoolean("failure", false), "the sync failed: " + run);
    }

    protected static <T> T await(Future<T> future) throws Exception
    {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
