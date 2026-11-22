package com.pulse.platform.ingest;

import com.pulse.calc.core.CalcService;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.spi.metrics.HttpClientMetrics;
import io.vertx.core.spi.metrics.TCPMetrics;
import io.vertx.core.spi.metrics.VertxMetrics;
import io.vertx.pgclient.PgPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connector seam of task 5.4 against PostgreSQL: the seed connector loads idempotently through
 * {@link IngestService}, a second connector writes through the same service, nothing outside the
 * ingest contract is accepted, and a seeded sync opens no network connection.
 */
class IngestServiceTest
{
    private static TestDb db;

    private static SqlExecutor owner;

    private static SqlExecutor app;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        PgPool pool = db.ownerPool();
        ScriptCatalog catalog = ScriptCatalog.load(IngestServiceTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of()));

        await(MigrationRunner.migrate(pool, catalog));
        await(MigrationRunner.seed(pool, catalog, true));

        owner = new SqlExecutor(pool);
        app = new SqlExecutor(db.pool("pulse_app"));
    }

    @AfterAll
    static void tearDown() throws Exception
    {
        if (db != null)
        {
            db.close();
        }
    }

    private static SeedConnector seed(Vertx vertx)
    {
        return new SeedConnector(vertx, new SeedFiles(IngestServiceTest.class.getClassLoader()),
                PlatformSeedMappers.all());
    }

    private static long count(String sql) throws Exception
    {
        return await(owner.queryOne(sql)).getLong("n");
    }

    // ------------------------------------------------------------------ seed connector

    @Test
    void seedSyncTwiceCreatesNoDuplicatesAndAdvancesSyncedAt() throws Exception
    {
        SyncService sync = new SyncService(db.vertx(), app);
        SeedConnector jira = seed(db.vertx()).forTool("jira");

        long sprints = count("SELECT count(*) AS n FROM sprints");
        long seedSprints = count("SELECT count(*) AS n FROM sprints WHERE source = 'seed'");
        long releases = count("SELECT count(*) AS n FROM releases");
        long runs = count("SELECT count(*) AS n FROM sync_log WHERE tool = 'jira' AND outcome = 'success'");

        JsonObject first = await(sync.run(jira, "jira", null, null));
        String syncedFirst = await(owner.queryOne("SELECT max(synced_at)::text AS at FROM sprints WHERE source = 'seed'"))
                .getString("at");

        JsonObject second = await(sync.run(jira, "jira", null, null));
        String syncedSecond = await(owner.queryOne("SELECT max(synced_at)::text AS at FROM sprints WHERE source = 'seed'"))
                .getString("at");

        assertEquals("success", first.getString("outcome"));
        assertEquals("success", second.getString("outcome"));
        assertEquals(sprints, count("SELECT count(*) AS n FROM sprints"), "no duplicate sprints");
        assertEquals(releases, count("SELECT count(*) AS n FROM releases"), "no duplicate releases");
        assertEquals(0, count("SELECT count(*) AS n FROM sprints WHERE source = 'seed' AND synced_at IS NULL"));
        assertTrue(syncedSecond.compareTo(syncedFirst) > 0, "synced_at moves on each sync");

        JsonObject counts = second.getJsonObject("counts").getJsonObject("entities").getJsonObject("sprints");

        assertEquals(0, counts.getInteger("inserted"));
        assertEquals(0, counts.getInteger("updated"));
        assertEquals(seedSprints, counts.getLong("unchanged").longValue());
        assertTrue(second.getBoolean("simulated"));
        assertEquals(runs + 2, count("SELECT count(*) AS n FROM sync_log WHERE tool = 'jira' AND outcome = 'success'"));
    }

    @Test
    void seedMappersOfOneToolOnly() throws Exception
    {
        JsonObject batch = await(seed(db.vertx()).forTool("hr").fetchChanges(null));

        assertEquals("seed", batch.getString("source"));
        assertEquals(1, batch.getJsonArray("entities").size());
        assertEquals("holidays", batch.getJsonArray("entities").getJsonObject(0).getString("entity"));
    }

    @Test
    void unchangedRowsKeepTheirVersionAndChangedRowsMove() throws Exception
    {
        await(owner.execute("UPDATE releases SET name = 'Edited' WHERE source = 'seed' AND external_id = '8.3'",
                io.vertx.sqlclient.Tuple.tuple()));

        JsonObject before = await(owner.queryOne("SELECT version FROM releases WHERE source = 'seed' AND external_id = '8.2'"));
        JsonObject entry = await(new SyncService(db.vertx(), app).run(seed(db.vertx()).forTool("jira"), "jira", null, null));

        JsonObject releases = entry.getJsonObject("counts").getJsonObject("entities").getJsonObject("releases");

        assertEquals(1, releases.getInteger("updated"), "the edited release is restored from the source");
        assertEquals("NMS 8.3", await(owner.queryOne("SELECT name FROM releases WHERE source = 'seed' AND external_id = '8.3'"))
                .getString("name"));
        assertEquals(before.getInteger("version"),
                await(owner.queryOne("SELECT version FROM releases WHERE source = 'seed' AND external_id = '8.2'"))
                        .getInteger("version"));
    }

    // ------------------------------------------------------------------ a second connector

    /** A stub connector for a test tool: one sprint and one pillar row referencing a seeded person. */
    private static final class StubConnector implements ConnectorPort
    {
        private final String sprintNumber;

        StubConnector(String sprintNumber)
        {
            this.sprintNumber = sprintNumber;
        }

        @Override
        public String tool()
        {
            return "testtool";
        }

        @Override
        public String source()
        {
            return "testtool";
        }

        @Override
        public boolean live()
        {
            return true;
        }

        @Override
        public Future<JsonObject> fetchChanges(String since)
        {
            return Future.succeededFuture(new JsonObject()
                    .put("source", source())
                    .put("cursor", "2026-09-28T10:00:00Z")
                    .put("entities", new JsonArray()
                            .add(new JsonObject().put("entity", "sprints").put("records", new JsonArray()
                                    .add(new JsonObject()
                                            .put("external_id", "T-1")
                                            .put("key", "T1")
                                            .put("number", Integer.parseInt(sprintNumber))
                                            .put("starts_on", "2027-01-04")
                                            .put("work_ends_on", "2027-01-15")
                                            .put("ends_on", "2027-01-17"))))
                            .add(new JsonObject().put("entity", "pillars").put("records", new JsonArray()
                                    .add(new JsonObject()
                                            .put("external_id", "P-ps-2024Q1")
                                            .put("person_id", new JsonObject().put(IngestService.REF, "people")
                                                    .put("source", "seed").put("external_id", "ps"))
                                            .put("quarter", "2024-Q1")
                                            .put("delivery", 4.25)
                                            .put("quality", "3.5")
                                            .put("okr", 3)
                                            .put("growth", 4))))));
        }
    }

    @Test
    void aSecondConnectorWritesThroughTheSameIngestService() throws Exception
    {
        ConnectorRegistry registry = new ConnectorRegistry(seed(db.vertx())).register(new StubConnector("901"));

        assertTrue(registry.registered("testtool"));
        assertFalse(registry.registered("jira"));

        long seedSprints = count("SELECT count(*) AS n FROM sprints WHERE source = 'seed'");
        SyncService sync = new SyncService(db.vertx(), app);

        JsonObject first = await(sync.run(registry.forTool("testtool"), "testtool", null, null));

        assertEquals("success", first.getString("outcome"));
        assertFalse(first.getBoolean("simulated"));
        assertEquals("2026-09-28T10:00:00Z", first.getJsonObject("counts").getString("cursor"));

        JsonObject sprint = await(owner.queryOne("SELECT number, starts_on::text AS starts_on, synced_at "
                + "FROM sprints WHERE source = 'testtool' AND external_id = 'T-1'"));

        assertEquals(901, sprint.getInteger("number"));
        assertEquals("2027-01-04", sprint.getString("starts_on"));
        assertNotNull(sprint.getString("synced_at"));

        JsonObject pillar = await(owner.queryOne("SELECT pi.delivery::text AS delivery, pi.quality::text AS quality, "
                + "pe.external_id AS person FROM pillars pi JOIN people pe ON pe.id = pi.person_id "
                + "WHERE pi.source = 'testtool' AND pi.external_id = 'P-ps-2024Q1'"));

        assertEquals("4.25", pillar.getString("delivery"));
        assertEquals("3.50", pillar.getString("quality"));
        assertEquals("ps", pillar.getString("person"), "the $ref resolved to the seeded person");

        // an update arrives: same row, new value; a repeat run adds nothing
        registry.register(new StubConnector("902"));

        JsonObject second = await(sync.run(registry.forTool("testtool"), "testtool", null, null));

        assertEquals(1, second.getJsonObject("counts").getJsonObject("entities").getJsonObject("sprints")
                .getInteger("updated"));
        assertEquals(1, count("SELECT count(*) AS n FROM sprints WHERE source = 'testtool'"));
        assertEquals(902, await(owner.queryOne("SELECT number FROM sprints WHERE source = 'testtool'")).getInteger("number"));
        assertEquals(seedSprints, count("SELECT count(*) AS n FROM sprints WHERE source = 'seed'"),
                "a live source never touches seed rows");
    }

    @Test
    void syncPublishesTheCalcInvalidation() throws Exception
    {
        List<JsonObject> events = new CopyOnWriteArrayList<>();
        var consumer = db.vertx().eventBus().<JsonObject>consumer(CalcService.INVALIDATE_ADDRESS, m -> events.add(m.body()));

        // local consumer: registered synchronously
        await(new SyncService(db.vertx(), app).run(seed(db.vertx()).forTool("hr"), "hr", null, null));

        long until = System.currentTimeMillis() + 5_000;

        while (events.isEmpty() && System.currentTimeMillis() < until)
        {
            TimeUnit.MILLISECONDS.sleep(20);
        }

        await(consumer.unregister());

        assertEquals(1, events.size());

        JsonArray topics = events.get(0).getJsonArray("topics");

        assertTrue(topics.contains(CalcService.ALL));
        assertTrue(topics.contains("holidays"));
    }

    // ------------------------------------------------------------------ contract checks

    private static String failure(JsonObject batch) throws Exception
    {
        try
        {
            await(new IngestService(app).ingest(batch));

            return null;
        }
        catch (Exception e)
        {
            return e.getMessage();
        }
    }

    private static JsonObject batch(String entity, JsonObject record)
    {
        return new JsonObject().put("source", "testtool").put("entities", new JsonArray()
                .add(new JsonObject().put("entity", entity).put("records", new JsonArray().add(record))));
    }

    @Test
    void rejectsWhatIsOutsideTheIngestContract() throws Exception
    {
        JsonObject holiday = new JsonObject().put("external_id", "H-1").put("holiday_on", "2027-01-26").put("name", "X");

        assertTrue(failure(batch("users", holiday.copy())).contains("not an ingest table"));
        assertTrue(failure(batch("holidays", holiday.copy().put("nope", 1))).contains("Unknown column"));
        assertTrue(failure(batch("holidays", holiday.copy().put("synced_at", "2020-01-01"))).contains("set by IngestService"));
        assertTrue(failure(batch("holidays", holiday.copy().put("name\"; DROP TABLE holidays; --", "x")))
                .contains("Unknown column"));
        assertTrue(failure(batch("holidays", holiday.copy().put("location",
                new JsonObject().put(IngestService.REF, "users").put("external_id", "x")))).contains("not an ingest table"));
        assertTrue(failure(batch("holidays", new JsonObject().put("holiday_on", "2027-01-26").put("name", "X")))
                .contains("external_id"));
        assertTrue(failure(new JsonObject().put("entities", new JsonArray())).contains("source"));
    }

    @Test
    void aFailedBatchWritesNothing() throws Exception
    {
        JsonObject batch = new JsonObject().put("source", "testtool").put("entities", new JsonArray()
                .add(new JsonObject().put("entity", "holidays").put("records", new JsonArray()
                        .add(new JsonObject().put("external_id", "H-ok").put("holiday_on", "2027-03-01").put("name", "Ok"))
                        .add(new JsonObject().put("external_id", "H-bad").put("holiday_on", "not a date").put("name", "Bad")))));

        assertNotNull(failure(batch));
        assertEquals(0, count("SELECT count(*) AS n FROM holidays WHERE source = 'testtool' AND external_id = 'H-ok'"));
    }

    @Test
    void failingConnectorIsLoggedAndKeepsRows() throws Exception
    {
        long holidays = count("SELECT count(*) AS n FROM holidays");
        ConnectorPort broken = new ConnectorPort()
        {
            @Override
            public String tool()
            {
                return "testtool";
            }

            @Override
            public String source()
            {
                return "testtool";
            }

            @Override
            public boolean live()
            {
                return true;
            }

            @Override
            public Future<JsonObject> fetchChanges(String since)
            {
                return Future.failedFuture(new ConnectorException(401, "Unauthorized"));
            }
        };

        JsonObject entry = await(new SyncService(db.vertx(), app).run(broken, "broken", null, null));

        assertEquals("failed", entry.getString("outcome"));
        assertEquals("401 Unauthorized", entry.getString("message"));
        assertNotNull(entry.getString("finished_at"));
        assertEquals(holidays, count("SELECT count(*) AS n FROM holidays"));
    }

    @Test
    void valueText()
    {
        assertNull(IngestService.asText("date", null));
        assertEquals("{\"a\",\"b \\\"q\\\"\"}", IngestService.asText("text[]", new JsonArray().add("a").add("b \"q\"")));
        assertEquals("{\"k\":1}", IngestService.asText("jsonb", new JsonObject().put("k", 1)));
        assertEquals("\"x\"", IngestService.asText("jsonb", "x"));
        assertEquals("4.5", IngestService.asText("numeric(4,2)", 4.5));
    }

    // ------------------------------------------------------------------ no network

    /** Records every client connection a Vert.x instance opens (TCP and HTTP). */
    private static final class ConnectionRecorder implements VertxMetrics
    {
        final List<String> connections = new CopyOnWriteArrayList<>();

        private TCPMetrics<Object> tcp()
        {
            return new TCPMetrics<>()
            {
                @Override
                public Object connected(SocketAddress remoteAddress, String remoteName)
                {
                    connections.add(remoteName + " " + remoteAddress);

                    return null;
                }
            };
        }

        @Override
        public TCPMetrics<?> createNetClientMetrics(NetClientOptions options)
        {
            return tcp();
        }

        @Override
        public HttpClientMetrics<?, ?, ?, ?> createHttpClientMetrics(HttpClientOptions options)
        {
            return new HttpClientMetrics<Object, Object, Object, Object>()
            {
                @Override
                public Object connected(SocketAddress remoteAddress, String remoteName)
                {
                    connections.add(remoteName + " " + remoteAddress);

                    return null;
                }
            };
        }
    }

    @Test
    void seededSyncMakesNoOutboundCall() throws Exception
    {
        ConnectionRecorder recorder = new ConnectionRecorder();
        Vertx net = Vertx.vertx(new VertxOptions().setMetricsOptions(
                new MetricsOptions().setEnabled(true).setFactory(options -> recorder)));
        List<URI> javaNet = new CopyOnWriteArrayList<>();
        ProxySelector previous = ProxySelector.getDefault();

        // java.net clients consult the proxy selector before connecting: record and refuse
        ProxySelector.setDefault(new ProxySelector()
        {
            @Override
            public List<Proxy> select(URI uri)
            {
                javaNet.add(uri);

                throw new IllegalStateException("network blocked in this test: " + uri);
            }

            @Override
            public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe)
            {
            }
        });

        try
        {
            // the recorder sees a real connection (so an empty list below means something)
            var server = await(net.createHttpServer().requestHandler(r -> r.response().end("ok")).listen(0));

            await(net.createHttpClient().request(HttpMethod.GET, server.actualPort(), "127.0.0.1", "/")
                    .compose(req -> req.send().compose(res -> res.body())));

            assertEquals(1, recorder.connections.size(), "recorder works: " + recorder.connections);

            recorder.connections.clear();

            // every seeded tool, and the start-up load over all of them, with the connector on the guarded instance
            SeedConnector seed = seed(net);
            SyncService sync = new SyncService(net, app);

            for (String tool : List.of("jira", "hr", "sl", "gh", "tfs"))
            {
                assertEquals("success", await(sync.run(seed.forTool(tool), tool, null, null)).getString("outcome"));
            }

            assertEquals("success", await(sync.run(seed, SeedConnector.ALL_TOOLS, null, null)).getString("outcome"));

            assertEquals(List.of(), recorder.connections, "no outbound connection from a seeded sync");
            assertEquals(List.of(), javaNet, "no java.net connection from a seeded sync");
        }
        finally
        {
            ProxySelector.setDefault(previous);
            await(net.close());
        }
    }
}
