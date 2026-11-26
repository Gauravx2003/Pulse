package com.pulse.modules.connectors.jira;

import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.platform.accounts.InMemoryExternalAccountService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.live.BaseUrls;
import com.pulse.platform.live.GuardedHttp;
import com.pulse.platform.live.LiveConfig;
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
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.pulse.modules.connectors.jira.JiraConnectorContractTest.SECRET;
import static com.pulse.modules.connectors.jira.JiraConnectorContractTest.USERNAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 26.2: the Jira-specific Verify points beyond the shared contract test (auth headers, incremental
 * JQL, hidden-email assignee, bot edits, unknown story points field, refused base URLs) and the mapping
 * onto Pulse's rows, against {@code live-fixtures/jira/}.
 */
class JiraConnectorTest
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

        accounts.person(UUID.randomUUID(), "Dev User 1", "dev.user1@example.com");
        accounts.person(UUID.randomUUID(), "Dev User 3", "dev.user3@example.com");
    }

    private static <T> T await(Future<T> future) throws Exception
    {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private LiveContext context(JsonObject config, String secret)
    {
        JiraConnector connector = new JiraConnector();

        GuardedHttp http = new GuardedHttp(vertx, connector.tool(), connector.allowedOrigins(config), new JsonObject(),
                InetAddress::getAllByName);

        return LiveContext.of(connector.tool(), config, secret, http, accounts);
    }

    private static JsonObject config()
    {
        return JiraConnectorContractTest.config(mock.baseUrl(), JiraConnector.BASIC);
    }

    /** A full read into entity -> external_id -> record, like IngestService's upsert. */
    private Map<String, Map<String, JsonObject>> sync(JsonObject config, String since) throws Exception
    {
        Map<String, Map<String, JsonObject>> store = new LinkedHashMap<>();

        try (LiveContext ctx = context(config, SECRET))
        {
            await(new JiraConnector().fetchPages(ctx, since, batch ->
            {
                for (Object e : batch.getJsonArray("entities"))
                {
                    JsonObject entity = (JsonObject) e;

                    Map<String, JsonObject> rows = store.computeIfAbsent(entity.getString("entity"), k -> new LinkedHashMap<>());

                    entity.getJsonArray("records").forEach(r -> rows.put(((JsonObject) r).getString("external_id"), (JsonObject) r));
                }

                return Future.succeededFuture();
            }));
        }

        return store;
    }

    // ------------------------------------------------------------------ auth

    @Test
    void basicSendsBase64OfUsernameAndPassword() throws Exception
    {
        try (LiveContext ctx = context(config(), SECRET))
        {
            await(new JiraConnector().testConnection(ctx));
        }

        String expected = "Basic " + Base64.getEncoder().encodeToString((USERNAME + ":" + SECRET).getBytes(StandardCharsets.UTF_8));

        assertFalse(mock.requests().isEmpty());

        mock.requests().forEach(r -> assertEquals(expected, r.getJsonObject("headers").getString("authorization")));
    }

    @Test
    void bearerSendsThePersonalAccessToken() throws Exception
    {
        JsonObject config = JiraConnectorContractTest.config(mock.baseUrl(), JiraConnector.BEARER).put("username", null);

        try (LiveContext ctx = context(config, "pat-token-55aa"))
        {
            await(new JiraConnector().testConnection(ctx));
        }

        assertFalse(mock.requests().isEmpty());

        mock.requests().forEach(r -> assertEquals("Bearer pat-token-55aa", r.getJsonObject("headers").getString("authorization")));
    }

    // ------------------------------------------------------------------ test connection

    @Test
    void testConnectionShowsTheUserAndProposesFields() throws Exception
    {
        JsonObject result;

        try (LiveContext ctx = context(config().put("expiresOn", "2027-03-31"), SECRET))
        {
            result = await(new JiraConnector().testConnection(ctx));
        }

        assertTrue(result.getBoolean("ok"));
        assertEquals("pulse.readonly", result.getJsonObject("account").getString("username"));
        assertEquals("Pulse Read-only", result.getJsonObject("account").getString("displayName"));
        assertEquals("2027-03-31", result.getString("expiresAt"));

        JsonObject fields = result.getJsonObject("fields");

        assertEquals("customfield_10111", fields.getJsonArray("storyPointsField").getJsonObject(0).getString("id"));
        assertEquals(1, fields.getJsonArray("storyPointsField").size(), "\"Original story points\" is not proposed");
        assertEquals("customfield_10108", fields.getJsonArray("sprintField").getJsonObject(0).getString("id"));

        assertEquals(List.of("/rest/api/2/myself", "/rest/api/2/field"), mock.requests().stream().map(r -> r.getString("path")).toList());
    }

    @Test
    void testConnectionFailsOn401WithTheHint() throws Exception
    {
        mock.faultOn("/rest/api/2/myself", LiveMockServer.status(401));

        JsonObject result;

        try (LiveContext ctx = context(config(), SECRET))
        {
            result = await(new JiraConnector().testConnection(ctx));
        }

        assertFalse(result.getBoolean("ok"));
        assertEquals(401, result.getInteger("status"));
        assertEquals(JiraConnector.UNAUTHORIZED_HINT, result.getString("hint"));
        assertEquals(1, mock.requests().size(), "no field discovery after a failed sign-in");
    }

    // ------------------------------------------------------------------ fetch

    @Test
    void incrementalRunAsksFromOneMinuteBeforeTheCursor() throws Exception
    {
        Map<String, Map<String, JsonObject>> full = sync(config(), null);

        JsonObject firstSearch = mock.requests().get(0).getJsonObject("query");

        assertEquals("project in (\"PROJ\") ORDER BY updated ASC", firstSearch.getString("jql"), "a full read has no time bound");
        assertEquals("changelog", firstSearch.getString("expand"));
        assertEquals("100", firstSearch.getString("maxResults"));
        assertTrue(firstSearch.getString("fields").contains("customfield_10111"));

        mock.reset();

        Map<String, Map<String, JsonObject>> again = sync(config(), "2026-11-25T23:45:12+05:30");

        for (JsonObject request : mock.requests())
        {
            assertEquals("project in (\"PROJ\") AND updated >= \"2026-11-25 23:44\" ORDER BY updated ASC",
                    request.getJsonObject("query").getString("jql"));
        }

        assertEquals(full.get("issues").keySet(), again.get("issues").keySet(), "repeated issues upsert, never duplicate");
        assertEquals(10, full.get("issues").size());
    }

    @Test
    void cursorIsTheLatestUpdated() throws Exception
    {
        JsonObject result;

        try (LiveContext ctx = context(config(), SECRET))
        {
            result = await(new JiraConnector().fetchPages(ctx, null, b -> Future.succeededFuture()));
        }

        assertEquals(OffsetDateTime.parse("2026-11-25T23:45:12+05:30"), OffsetDateTime.parse(result.getString("cursor")));
    }

    @Test
    void assigneeWithoutEmailIsUnmatchedUnderTheUsername() throws Exception
    {
        sync(config(), null);

        JsonObject hidden = null;

        for (Object o : accounts.all())
        {
            JsonObject row = (JsonObject) o;

            if ("dev.user7".equals(row.getString("accountId")))
            {
                hidden = row;
            }
        }

        assertTrue(hidden != null, "dev.user7 is recorded");
        assertEquals("jira", hidden.getString("tool"));
        assertEquals("dev.user7", hidden.getString("login"));
        assertNull(hidden.getString("email"));
        assertNull(hidden.getString("personId"));

        JsonObject linked = accounts.all().stream().map(o -> (JsonObject) o)
                .filter(r -> "dev.user1".equals(r.getString("accountId"))).findFirst().orElseThrow();

        assertTrue(linked.getString("personId") != null, "an email match links the account");
    }

    @Test
    void botOnlyEditLeavesLastActivityUnchanged() throws Exception
    {
        Map<String, Map<String, JsonObject>> store = sync(config(), null);

        JsonObject issue = store.get("issues").get("PROJ-10721");

        String withBot = lastActivity(issue, store.get("issue_events").values());

        List<JsonObject> withoutBot = new ArrayList<>();

        for (JsonObject event : store.get("issue_events").values())
        {
            if (!"svc.automation".equals(event.getString("actor_account_id")))
            {
                withoutBot.add(event);
            }
        }

        assertEquals("2026-11-25T15:41:05+05:30", withBot, "the assignee's own last change, not the bot's");
        assertEquals(withBot, lastActivity(issue, withoutBot), "the bot edit does not move last activity");

        Map<String, Map<String, JsonObject>> switchedOff = sync(config().put("ignoreBotEdits", false), null);

        assertEquals("2026-11-25T23:45:12+05:30",
                lastActivity(switchedOff.get("issues").get("PROJ-10721"), switchedOff.get("issue_events").values()),
                "with the switch off Jira's updated time counts");
    }

    /** Last activity as calc/work reads it: the latest counted event by the issue's assignee. */
    private static String lastActivity(JsonObject issue, Iterable<JsonObject> events)
    {
        String latest = null;

        for (JsonObject e : events)
        {
            boolean mine = e.getJsonObject("issue_id").getString("external_id").equals(issue.getString("external_id"));

            if (mine && WorkloadFormulas.COUNTED_ACTIVITY.contains(e.getString("kind"))
                    && issue.getString("assignee_account_id").equals(e.getString("actor_account_id"))
                    && (latest == null || OffsetDateTime.parse(e.getString("occurred_at")).isAfter(OffsetDateTime.parse(latest))))
            {
                latest = e.getString("occurred_at");
            }
        }

        return latest;
    }

    @Test
    void unknownStoryPointsFieldIsStoredAsNull() throws Exception
    {
        Map<String, Map<String, JsonObject>> known = sync(config(), null);

        assertEquals(5.0, known.get("issues").get("PROJ-10730").getDouble("story_points"));

        Map<String, Map<String, JsonObject>> unknown = sync(config().put("storyPointsField", "customfield_99999"), null);

        unknown.get("issues").values().forEach(i -> assertNull(i.getValue("story_points"), i.getString("key")));
    }

    @Test
    void refusesUserInfoAndMetadataBaseUrls()
    {
        assertFalse(BaseUrls.validate("http://admin:secret@jira.example.local:8080", false).getBoolean("valid"));
        assertFalse(BaseUrls.validate("http://169.254.169.254", false).getBoolean("valid"));
        assertTrue(BaseUrls.validate("http://172.16.10.6:8080", false).getBoolean("valid"), "a LAN base URL is allowed");

        assertThrows(IllegalArgumentException.class,
                () -> new JiraConnector().allowedOrigins(new JsonObject().put("baseUrl", "not a url")));
    }

    /**
     * On save: the Test connection and Save routes (24.5) check the submitted values with
     * {@link LiveConfig#validate} against this connector's own schema, before any request leaves.
     */
    @Test
    void saveRefusesUserInfoAndMetadataBaseUrlsWithJirasSchema()
    {
        JiraConnector connector = new JiraConnector();

        for (String bad : List.of("http://admin:secret@jira.example.local:8080", "http://169.254.169.254"))
        {
            JsonObject config = JiraConnectorContractTest.config(bad, JiraConnector.BASIC);

            ApiException refused = assertThrows(ApiException.class,
                    () -> LiveConfig.validate(connector, config, SECRET, false), bad);

            assertEquals("config.baseUrl", refused.details().getString("field"), bad);
            assertFalse(refused.getMessage().contains("secret"), "the refusal does not echo the user info");
        }

        JsonObject lan = LiveConfig.validate(connector, JiraConnectorContractTest.config("http://172.16.10.6:8080/",
                JiraConnector.BASIC), SECRET, false);

        assertEquals("http://172.16.10.6:8080", lan.getJsonObject("config").getString("baseUrl"));
        assertFalse(lan.getJsonArray("warnings").isEmpty(), "http shows the unencrypted warning");
    }

    @Test
    void refusesProjectKeysThatCouldInjectJql()
    {
        JsonObject bad = new JsonObject().put("projectKeys", new JsonArray().add("PROJ) OR (1=1"));

        assertThrows(IllegalArgumentException.class, () -> JiraConnector.jql(bad, null));
    }

    // ------------------------------------------------------------------ mapping

    @Test
    void mapsIssuesOntoPulseRows() throws Exception
    {
        Map<String, Map<String, JsonObject>> store = sync(config(), null);

        JsonObject sized = store.get("issues").get("PROJ-10730");

        assertEquals("In Progress", sized.getString("status"), "\"In QA\" is in progress; its name has no review");
        assertEquals(16.0, sized.getDouble("estimate_hours"));
        assertEquals(4.0, sized.getDouble("remaining_hours"));
        assertEquals(12.0, sized.getDouble("spent_hours"));
        assertEquals("dev.user1", sized.getString("assignee_account_id"));
        assertNull(sized.getValue("assignee_id"), "live rows resolve their person through the view");
        assertEquals(mock.baseUrl() + "/browse/PROJ-10730", sized.getString("html_url"));
        assertEquals("147", sized.getJsonObject("sprint_id").getString("external_id"));
        assertTrue(sized.getString("description").startsWith("h3. Problem\nSample problem description for issue 1"),
                "wiki markup stored as is, with no ADF parsing");

        JsonObject sprint = store.get("sprints").get("147");

        assertEquals("J147", sprint.getString("key"), "Jira sprints are keyed apart from the seed (25.4 review)");
        assertEquals("2026-11-23", sprint.getString("starts_on"));
        assertEquals("2026-12-04", sprint.getString("work_ends_on"));
        assertEquals("2026-12-06", sprint.getString("ends_on"), "the Sunday after the last working day");
        assertEquals(1, store.get("sprints").size(), "the string and object forms of sprint 147 give one row; 148 has no dates");

        JsonObject objectForm = store.get("issues").get("PROJ-10723");

        assertEquals("147", objectForm.getJsonObject("sprint_id").getString("external_id"), "object form parsed");
        assertEquals(8.0, objectForm.getDouble("story_points"));

        JsonObject carried = store.get("issues").get("PROJ-10729");

        assertTrue(carried.getBoolean("carried_over"));
        assertNull(carried.getValue("sprint_id"), "its current sprint (148) has not started");

        assertNull(store.get("issues").get("PROJ-10719").getValue("assignee_account_id"), "unassigned");

        JsonObject botEvent = store.get("issue_events").get("PROJ-10721|h310103.0");

        assertEquals("svc.automation", botEvent.getString("actor_account_id"));
        assertEquals("field_change", botEvent.getString("kind"));
        assertEquals("PROJ-10721", botEvent.getJsonObject("issue_id").getString("external_id"));
    }

    @Test
    void statusCategoriesMapOntoSeedStatuses()
    {
        assertEquals("To Do", JiraMapper.status(status("Open", "new")));
        assertEquals("In Progress", JiraMapper.status(status("In Progress", "indeterminate")));
        assertEquals("In Review", JiraMapper.status(status("Code Review", "indeterminate")));
        assertEquals("Done", JiraMapper.status(status("Closed", "done")));
    }

    private static JsonObject status(String name, String category)
    {
        return new JsonObject().put("name", name).put("statusCategory", new JsonObject().put("key", category));
    }

    @Test
    void parsesJiraServerSprintStrings()
    {
        JsonObject sprint = JiraMapper.parseSprint("com.atlassian.greenhopper.service.sprint.Sprint@500eaaba[id=147,rapidViewId=1,"
                + "state=ACTIVE,name=Sprint, with comma,startDate=2026-11-23T10:00:00.000+05:30,endDate=<null>,goal=]");

        assertEquals("147", sprint.getString("id"));
        assertEquals("Sprint, with comma", sprint.getString("name"));
        assertEquals("2026-11-23T10:00:00.000+05:30", sprint.getString("startDate"));
        assertNull(sprint.getString("endDate"));
        assertNotEquals(null, sprint.getString("goal"));
        assertNull(JiraMapper.parseSprint("not a sprint"));
    }
}
