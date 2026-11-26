package com.pulse.modules.connectors.jira;

import com.pulse.platform.accounts.InMemoryExternalAccountService;
import com.pulse.platform.live.GuardedHttp;
import com.pulse.platform.live.LiveContext;
import com.pulse.platform.live.testkit.LiveMockServer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.pulse.modules.connectors.jira.JiraConnectorContractTest.SECRET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Change jira-derived-hours, tasks 4.1 to 4.3: field discovery for the Dev and QA Start/End fields, Jira
 * worklogs, and work intervals with their precedence, ends and credited account, against
 * {@code live-fixtures/jira/} (scenarios of {@code specs/admin/integrations-connectors}).
 */
class JiraDerivedHoursTest
{
    private static Vertx vertx;

    private static LiveMockServer mock;

    private InMemoryExternalAccountService accounts;

    @BeforeAll
    static void start() throws Exception
    {
        vertx = Vertx.vertx();

        mock = await(LiveMockServer.start(vertx, "jira"));
    }

    @AfterAll
    static void stop() throws Exception
    {
        mock.close();

        await(vertx.close());
    }

    @BeforeEach
    void reset()
    {
        mock.reset();

        accounts = new InMemoryExternalAccountService();
    }

    private static <T> T await(Future<T> future) throws Exception
    {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static JsonObject config()
    {
        return JiraConnectorContractTest.config(mock.baseUrl(), JiraConnector.BASIC);
    }

    private LiveContext context(JsonObject config)
    {
        JiraConnector connector = new JiraConnector();

        GuardedHttp http = new GuardedHttp(vertx, connector.tool(), connector.allowedOrigins(config), new JsonObject(),
                InetAddress::getAllByName);

        return LiveContext.of(connector.tool(), config, SECRET, http, accounts);
    }

    /** A full read into entity -> external_id -> record, upserting like IngestService; plus the read result. */
    private Map<String, Map<String, JsonObject>> sync(JsonObject config, JsonObject result) throws Exception
    {
        Map<String, Map<String, JsonObject>> store = new LinkedHashMap<>();

        try (LiveContext ctx = context(config))
        {
            JsonObject done = await(new JiraConnector().fetchPages(ctx, null, batch ->
            {
                for (Object e : batch.getJsonArray("entities"))
                {
                    JsonObject entity = (JsonObject) e;

                    Map<String, JsonObject> rows = store.computeIfAbsent(entity.getString("entity"), k -> new LinkedHashMap<>());

                    entity.getJsonArray("records").forEach(r -> rows.put(((JsonObject) r).getString("external_id"), (JsonObject) r));
                }

                return Future.succeededFuture();
            }));

            result.mergeIn(done);
        }

        return store;
    }

    private Map<String, Map<String, JsonObject>> sync(JsonObject config) throws Exception
    {
        return sync(config, new JsonObject());
    }

    /** One issue of the fixtures, as the search answers it. */
    private static JsonObject hit(String key) throws Exception
    {
        for (String page : List.of("0", "5"))
        {
            WebSearch search = new WebSearch();

            for (Object item : search.page(page))
            {
                if (key.equals(((JsonObject) item).getString("key")))
                {
                    return ((JsonObject) item).copy();
                }
            }
        }

        throw new AssertionError("No " + key + " in the fixtures");
    }

    /** Reads a fixture page through the mock server. */
    private static final class WebSearch
    {
        JsonArray page(String startAt) throws Exception
        {
            io.vertx.ext.web.client.WebClient client = io.vertx.ext.web.client.WebClient.create(vertx);

            try
            {
                return await(client.getAbs(mock.baseUrl() + "/rest/api/2/search?startAt=" + startAt).send())
                        .bodyAsJsonObject().getJsonArray("issues");
            }
            finally
            {
                client.close();
            }
        }
    }

    private static OffsetDateTime at(String iso)
    {
        return iso == null ? null : OffsetDateTime.parse(iso);
    }

    // ------------------------------------------------------------------ 4.1 config and discovery

    @Test
    void testConnectionProposesTheStartAndEndFields() throws Exception
    {
        JsonObject result;

        try (LiveContext ctx = context(config()))
        {
            result = await(new JiraConnector().testConnection(ctx));
        }

        JsonObject fields = result.getJsonObject("fields");

        assertEquals("customfield_10200", fields.getJsonArray("devStartField").getJsonObject(0).getString("id"));
        assertEquals("customfield_10201", fields.getJsonArray("devEndField").getJsonObject(0).getString("id"));
        assertEquals("customfield_11000", fields.getJsonArray("qaStartField").getJsonObject(0).getString("id"));
        assertEquals("customfield_10701", fields.getJsonArray("qaEndField").getJsonObject(0).getString("id"));
        assertEquals(1, fields.getJsonArray("devStartField").size(), "Start Time (Design) is not proposed");
    }

    @Test
    void configSchemaHasTheFourOptionalFieldIds()
    {
        JsonArray schema = new JiraConnector().configSchema().getJsonArray("fields");

        for (String name : List.of("devStartField", "devEndField", "qaStartField", "qaEndField"))
        {
            JsonObject field = schema.stream().map(o -> (JsonObject) o).filter(f -> name.equals(f.getString("name")))
                    .findFirst().orElseThrow(() -> new AssertionError(name));

            assertFalse(field.getBoolean("required"));
            assertEquals("customfield_[0-9]+", field.getString("pattern"));
        }
    }

    @Test
    void searchAsksForWorklogsAndTheConfiguredFields() throws Exception
    {
        sync(config());

        String fields = mock.requests().get(0).getJsonObject("query").getString("fields");

        for (String id : List.of("worklog", "customfield_10200", "customfield_10201", "customfield_11000", "customfield_10701"))
        {
            assertTrue(List.of(fields.split(",")).contains(id), id + " in fields=" + fields);
        }
    }

    @Test
    void fieldsNotConfiguredStoreNoInterval() throws Exception
    {
        JsonObject none = config();

        List.of("devStartField", "devEndField", "qaStartField", "qaEndField").forEach(none::remove);

        Map<String, Map<String, JsonObject>> store = sync(none);

        assertNull(store.get("issue_work_intervals"), "no work interval when the field ids are empty");
        assertFalse(store.get("worklogs").isEmpty(), "worklogs are still read");

        JsonObject devOnly = config().put("qaEndField", "");

        Map<String, Map<String, JsonObject>> dev = sync(devOnly);

        assertTrue(dev.get("issue_work_intervals").keySet().stream().allMatch(k -> k.endsWith("|dev")),
                "an empty id turns its pair off");
    }

    // ------------------------------------------------------------------ 4.2 worklogs

    @Test
    void worklogsAreCreditedToTheAuthorOnTheirStartDate() throws Exception
    {
        JsonObject result = new JsonObject();

        Map<String, Map<String, JsonObject>> store = sync(config(), result);

        JsonObject first = store.get("worklogs").get("100501");

        assertEquals("PROJ-10730", first.getJsonObject("issue_id").getString("external_id"));
        assertEquals("dev.user1", first.getString("account_id"));
        assertNull(first.getValue("person_id"), "the person is resolved at read time");
        assertEquals("2026-11-24", first.getString("work_date"));
        assertEquals(3.0, first.getDouble("hours"));

        JsonObject second = store.get("worklogs").get("100502");

        assertEquals("2026-11-25", second.getString("work_date"), "the start date in Asia/Kolkata");
        assertEquals(9.0, second.getDouble("hours"));

        assertEquals(1, result.getInteger("truncatedWorklogs"), "PROJ-10723 embeds 1 of its 21 worklogs");

        assertTrue(accounts.all().stream().map(o -> (JsonObject) o).anyMatch(a -> "dev.user1".equals(a.getString("accountId"))),
                "worklog authors are resolved");
    }

    @Test
    void reSyncWithChangedHoursUpdatesInPlace() throws Exception
    {
        JsonObject hit = hit("PROJ-10730");

        JsonArray before = JiraMapper.worklogs(hit);

        hit.getJsonObject("fields").getJsonObject("worklog").getJsonArray("worklogs").getJsonObject(0).put("timeSpentSeconds", 14400);

        JsonArray after = JiraMapper.worklogs(hit);

        assertEquals(before.getJsonObject(0).getString("external_id"), after.getJsonObject(0).getString("external_id"),
                "the same worklog id, so the ingest upsert replaces 3 h with 4 h");
        assertEquals(4.0, after.getJsonObject(0).getDouble("hours"));
        assertEquals(before.size(), after.size());
    }

    // ------------------------------------------------------------------ 4.3 intervals

    @Test
    void devIntervalRecorded() throws Exception
    {
        JsonObject dev = sync(config()).get("issue_work_intervals").get("PROJ-10725|dev");

        assertEquals("dev", dev.getString("kind"));
        assertEquals(at("2026-11-25T14:54:00+05:30"), at(dev.getString("starts_at")));
        assertEquals(at("2026-11-25T18:54:00+05:30"), at(dev.getString("ends_at")));
        assertEquals("field", dev.getString("end_source"));
        assertEquals("dev.user3", dev.getString("account_id"), "the assignee at the start (never reassigned)");
        assertEquals("PROJ-10725", dev.getJsonObject("issue_id").getString("external_id"));
    }

    @Test
    void handOverCreditsDevAndQaToWhoeverWasAssignedThen() throws Exception
    {
        Map<String, JsonObject> intervals = sync(config()).get("issue_work_intervals");

        JsonObject dev = intervals.get("PROJ-10720|dev");

        JsonObject qa = intervals.get("PROJ-10720|qa");

        assertEquals("dev.user4", dev.getString("account_id"), "the developer, though dev.user1 is the assignee now");
        assertEquals("dev.user1", qa.getString("account_id"));
        assertEquals(at("2026-11-26T10:00:00+05:30"), at(qa.getString("starts_at")));
        assertEquals(at("2026-11-26T14:00:00+05:30"), at(qa.getString("ends_at")));

        assertTrue(accounts.all().stream().map(o -> (JsonObject) o).anyMatch(a -> "dev.user4".equals(a.getString("accountId"))),
                "an account an interval is credited to is recorded, so the Admin can link it");
    }

    @Test
    void openIntervalOfAResolvedIssueEndsAtTheResolution() throws Exception
    {
        JsonObject dev = sync(config()).get("issue_work_intervals").get("PROJ-10719|dev");

        assertEquals(at("2026-11-25T17:30:00+05:30"), at(dev.getString("ends_at")));
        assertEquals("resolution", dev.getString("end_source"));
        assertEquals("dev.user5", dev.getString("account_id"), "nobody was assigned: the author who set the start");
    }

    @Test
    void openIntervalOfAnUnresolvedIssueHasNoEnd() throws Exception
    {
        JsonObject hit = hit("PROJ-10725");

        hit.getJsonObject("fields").putNull("customfield_10201");

        JsonObject dev = JiraMapper.intervals(hit, config()).getJsonObject(0);

        assertNull(dev.getValue("ends_at"));
        assertEquals("open", dev.getString("end_source"));
    }

    @Test
    void timeTrackingWins() throws Exception
    {
        Map<String, Map<String, JsonObject>> store = sync(config());

        JsonObject dev = store.get("issue_work_intervals").get("PROJ-10730|dev");

        assertNull(dev.getValue("starts_at"), "an issue with worklogs keeps no interval, though it has Start/End (Dev)");
        assertNull(dev.getValue("account_id"));
        assertEquals(2, store.get("worklogs").values().stream()
                .filter(w -> "PROJ-10730".equals(w.getJsonObject("issue_id").getString("external_id"))).count());
    }

    @Test
    void timeSpentAloneAlsoWins() throws Exception
    {
        JsonObject hit = hit("PROJ-10725");

        hit.getJsonObject("fields").put("timespent", 3600);

        assertNull(JiraMapper.intervals(hit, config()).getJsonObject(0).getValue("starts_at"));
    }

    @Test
    void intervalMovedReplacesTheRow() throws Exception
    {
        JsonObject hit = hit("PROJ-10725");

        JsonObject before = JiraMapper.intervals(hit, config()).getJsonObject(0);

        hit.getJsonObject("fields").put("customfield_10201", "2026-11-25T16:00:00.000+0530");

        JsonObject after = JiraMapper.intervals(hit, config()).getJsonObject(0);

        assertEquals(before.getString("external_id"), after.getString("external_id"), "one row per issue and kind, upserted");
        assertEquals(at("2026-11-25T16:00:00+05:30"), at(after.getString("ends_at")));
    }

    @Test
    void clearedStartRetiresTheInterval() throws Exception
    {
        Map<String, JsonObject> intervals = sync(config()).get("issue_work_intervals");

        JsonObject never = intervals.get("PROJ-10722|dev");

        assertNull(never.getValue("starts_at"), "no start: the row is written with starts_at null");

        JsonObject hit = hit("PROJ-10725");

        hit.getJsonObject("fields").putNull("customfield_10200");

        assertNull(JiraMapper.intervals(hit, config()).getJsonObject(0).getValue("starts_at"));
    }
}
