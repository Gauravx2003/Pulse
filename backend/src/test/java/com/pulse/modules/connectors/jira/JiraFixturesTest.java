package com.pulse.modules.connectors.jira;

import com.pulse.platform.live.testkit.LiveMockServer;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 26.1: the Jira Server fixtures load in the mock server, page through {@code /rest/api/2/search},
 * cover the cases the connector must handle (both sprint formats, story points and time tracking, an
 * assignee without {@code emailAddress}, an unassigned issue, a bot edit, recorded 401/429/500) and hold no
 * password, token or real email.
 */
class JiraFixturesTest
{
    private static final String FIXTURES = LiveMockServer.FIXTURES_ROOT + "jira/";

    /** The recorded 100-issue page pair, kept apart because its startAt 0 route would shadow search-page1.json. */
    private static final String RECORDED = "jira-recorded";

    /** The end-to-end flow's fixtures (task 26.3): assignees carry demo seed emails so they match people. */
    private static final String E2E = "jira-e2e";

    private static final String SANITIZED_DOMAIN = "@example.com";

    private static final String DEMO_DOMAIN = "@pulse.demo";

    private static final String SPRINT_STRING = "com.atlassian.greenhopper.service.sprint.Sprint@";

    private static final String SPRINT_FIELD = "customfield_10108";

    private static final String STORY_POINTS_FIELD = "customfield_10111";

    private static Vertx vertx;

    private static LiveMockServer mock;

    private static WebClient client;

    @BeforeAll
    static void start() throws Exception
    {
        vertx = Vertx.vertx();

        mock = LiveMockServer.start(vertx, "jira").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    @AfterAll
    static void stop() throws Exception
    {
        client.close();
        mock.close();

        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @BeforeEach
    void reset()
    {
        mock.reset();
    }

    private HttpResponse<Buffer> get(String uri) throws Exception
    {
        return client.getAbs(mock.baseUrl() + uri).send().toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private JsonArray allIssues() throws Exception
    {
        JsonArray issues = new JsonArray();

        issues.addAll(get("/rest/api/2/search?startAt=0&maxResults=100").bodyAsJsonObject().getJsonArray("issues"));

        issues.addAll(get("/rest/api/2/search?startAt=5&maxResults=100").bodyAsJsonObject().getJsonArray("issues"));

        return issues;
    }

    private static JsonObject issue(JsonArray issues, String key)
    {
        for (int i = 0; i < issues.size(); i++)
        {
            if (key.equals(issues.getJsonObject(i).getString("key")))
            {
                return issues.getJsonObject(i);
            }
        }

        throw new AssertionError("No issue " + key + " in the fixtures");
    }

    @Test
    void loadsInTheMockServer()
    {
        assertEquals("jira", LiveMockServer.load("jira").tool());
    }

    @Test
    void testConnectionAnswers() throws Exception
    {
        HttpResponse<Buffer> myself = get("/rest/api/2/myself");

        assertEquals(200, myself.statusCode());
        assertEquals("pulse.readonly", myself.bodyAsJsonObject().getString("name"));
        assertEquals(mock.baseUrl() + "/rest/api/2/user?username=pulse.readonly", myself.bodyAsJsonObject().getString("self"));

        HttpResponse<Buffer> fields = get("/rest/api/2/field");

        assertEquals(200, fields.statusCode());

        boolean storyPoints = false;

        boolean sprint = false;

        for (Object item : fields.bodyAsJsonArray())
        {
            JsonObject field = (JsonObject) item;

            JsonObject schema = field.getJsonObject("schema", new JsonObject());

            storyPoints |= STORY_POINTS_FIELD.equals(field.getString("id")) && "Story Points".equals(field.getString("name"));

            sprint |= SPRINT_FIELD.equals(field.getString("id")) && "com.pyxis.greenhopper.jira:gh-sprint".equals(schema.getString("custom"));
        }

        assertTrue(storyPoints, "field.json proposes the story points field");
        assertTrue(sprint, "field.json proposes the sprint field");
    }

    @Test
    void searchPagesUntilTotal() throws Exception
    {
        JsonObject first = get("/rest/api/2/search?jql=project%20in%20(PROJ)&startAt=0&maxResults=100&expand=changelog").bodyAsJsonObject();

        int next = first.getInteger("startAt") + first.getJsonArray("issues").size();

        JsonObject second = get("/rest/api/2/search?jql=project%20in%20(PROJ)&startAt=" + next + "&maxResults=100&expand=changelog").bodyAsJsonObject();

        assertEquals(next, second.getInteger("startAt"));
        assertEquals(first.getInteger("total"), second.getInteger("total"));
        assertEquals(second.getInteger("total"), second.getInteger("startAt") + second.getJsonArray("issues").size(),
                "the second page reaches total, so paging stops");

        List<String> keys = new ArrayList<>();

        for (Object item : allIssues())
        {
            String key = ((JsonObject) item).getString("key");

            assertFalse(keys.contains(key), "issue keys are unique across pages: " + key);

            keys.add(key);

            assertNotNull(((JsonObject) item).getJsonObject("changelog"), key + " carries its changelog");
        }
    }

    @Test
    void sprintComesInBothFormats() throws Exception
    {
        boolean string = false;

        boolean object = false;

        for (Object item : allIssues())
        {
            JsonArray sprints = ((JsonObject) item).getJsonObject("fields").getJsonArray(SPRINT_FIELD);

            if (sprints == null)
            {
                continue;
            }

            for (Object sprint : sprints)
            {
                string |= sprint instanceof String s && s.startsWith(SPRINT_STRING) && s.contains("id=147");

                object |= sprint instanceof JsonObject o && Integer.valueOf(147).equals(o.getInteger("id"));
            }
        }

        assertTrue(string, "sprint 147 appears in Jira Server's string form");
        assertTrue(object, "sprint 147 appears in the object form");
    }

    @Test
    void coversTheIssueCases() throws Exception
    {
        JsonArray issues = allIssues();

        JsonObject sized = issue(issues, "PROJ-10730").getJsonObject("fields");

        assertEquals(5.0, sized.getDouble(STORY_POINTS_FIELD));
        assertEquals(57600L, sized.getLong("timeoriginalestimate"));
        assertEquals(14400L, sized.getJsonObject("timetracking").getLong("remainingEstimateSeconds"));
        assertEquals(43200L, sized.getJsonObject("timetracking").getLong("timeSpentSeconds"));

        JsonObject hidden = issue(issues, "PROJ-10722").getJsonObject("fields").getJsonObject("assignee");

        assertEquals("dev.user7", hidden.getString("name"));
        assertFalse(hidden.containsKey("emailAddress"), "Jira hides this assignee's email");

        assertTrue(issue(issues, "PROJ-10719").getJsonObject("fields").containsKey("assignee"));
        assertEquals(null, issue(issues, "PROJ-10719").getJsonObject("fields").getValue("assignee"), "unassigned issue");

        JsonObject botIssue = issue(issues, "PROJ-10721");

        JsonArray histories = botIssue.getJsonObject("changelog").getJsonArray("histories");

        JsonObject last = histories.getJsonObject(histories.size() - 1);

        assertEquals("svc.automation", last.getJsonObject("author").getString("name"), "the newest edit is a bot's");
        assertEquals(botIssue.getJsonObject("fields").getString("updated"), last.getString("created"),
                "updated follows the bot edit; real activity is the earlier, assignee's history");
        assertEquals("dev.user3", histories.getJsonObject(histories.size() - 2).getJsonObject("author").getString("name"));
    }

    @Test
    void recordedErrorsAnswer() throws Exception
    {
        mock.faultOn("/rest/api/2/myself", LiveMockServer.status(401));

        HttpResponse<Buffer> unauthorized = get("/rest/api/2/myself");

        assertEquals(401, unauthorized.statusCode());
        assertTrue(unauthorized.getHeader("Content-Type").startsWith("application/xml"), "Jira Server answers 401 in XML");
        assertTrue(unauthorized.getHeader("WWW-Authenticate").startsWith("OAuth realm="));
        assertTrue(unauthorized.bodyAsString().contains("Client must be authenticated"));

        mock.reset().faultOn("/rest/api/2/search", LiveMockServer.status(429));

        HttpResponse<Buffer> limited = get("/rest/api/2/search?startAt=0");

        assertEquals(429, limited.statusCode());
        assertEquals("60", limited.getHeader("Retry-After"));
        assertFalse(limited.bodyAsJsonObject().getJsonArray("errorMessages").isEmpty());

        mock.reset().faultOn("/rest/api/2/search", LiveMockServer.status(500));

        HttpResponse<Buffer> failed = get("/rest/api/2/search?startAt=0");

        assertEquals(500, failed.statusCode());
        assertFalse(failed.bodyAsJsonObject().getJsonArray("errorMessages").isEmpty());
    }

    @Test
    void recordedPagesPaginateAtOneHundred() throws Exception
    {
        LiveMockServer recorded = LiveMockServer.start(vertx, RECORDED).toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        try
        {
            String search = recorded.baseUrl() + "/rest/api/2/search?jql=project%20in%20(PROJ)%20ORDER%20BY%20updated%20ASC"
                    + "&maxResults=100&expand=changelog&startAt=";

            JsonObject first = client.getAbs(search + "0").send().toCompletionStage().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).bodyAsJsonObject();

            int next = first.getInteger("startAt") + first.getJsonArray("issues").size();

            assertEquals(100, first.getInteger("maxResults"));
            assertEquals(100, next, "a full first page of 100 issues");

            JsonObject second = client.getAbs(search + next).send().toCompletionStage().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).bodyAsJsonObject();

            assertEquals(100, second.getInteger("startAt"));
            assertEquals(first.getInteger("total"), second.getInteger("total"));
            assertTrue(second.getInteger("total") > 200, "the recorded project holds more than two pages");

            List<String> keys = new ArrayList<>();

            boolean sprintString = false;

            for (JsonObject page : List.of(first, second))
            {
                for (Object item : page.getJsonArray("issues"))
                {
                    JsonObject issue = (JsonObject) item;

                    String key = issue.getString("key");

                    assertTrue(key.startsWith("PROJ-"), "project key is sanitized: " + key);
                    assertFalse(keys.contains(key), "issue keys are unique across pages: " + key);

                    keys.add(key);

                    assertNotNull(issue.getJsonObject("changelog"), key + " carries its changelog");

                    JsonArray sprints = issue.getJsonObject("fields").getJsonArray(SPRINT_FIELD);

                    sprintString |= sprints != null && sprints.getValue(0) instanceof String s && s.startsWith(SPRINT_STRING);
                }
            }

            assertEquals(200, keys.size());
            assertTrue(sprintString, "the recorded sprints come in Jira Server's string form");

            JsonObject myself = client.getAbs(recorded.baseUrl() + "/rest/api/2/myself").send().toCompletionStage()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).bodyAsJsonObject();

            assertEquals("pulse.readonly", myself.getString("name"));
        }
        finally
        {
            recorded.close();
        }
    }

    @Test
    void holdsNoSecretOrRealIdentity() throws Exception
    {
        List<String> files = scan(FIXTURES, List.of(SANITIZED_DOMAIN));

        assertTrue(files.containsAll(List.of("routes.json", "myself.json", "field.json", "search-page1.json",
                "search-page2.json", "error-401.xml", "error-429.json", "error-500.json")), "scanned " + files);

        List<String> recorded = scan(LiveMockServer.FIXTURES_ROOT + RECORDED + "/", List.of(SANITIZED_DOMAIN));

        assertTrue(recorded.containsAll(List.of("routes.json", "myself.json", "field.json", "search-100-page1.json",
                "search-100-page2.json")), "scanned " + recorded);
    }

    @Test
    void endToEndFixturesLoadAndHoldNoSecret() throws Exception
    {
        assertEquals(E2E, LiveMockServer.load(E2E).tool());

        List<String> files = scan(LiveMockServer.FIXTURES_ROOT + E2E + "/", List.of(SANITIZED_DOMAIN, DEMO_DOMAIN));

        assertTrue(files.containsAll(List.of("routes.json", "myself.json", "field.json", "search.json", "error-401.xml",
                "error-429.json", "error-500.json")), "scanned " + files);
    }

    /**
     * Fails on an email outside {@code domains} (compared in lower case), a credential, the internal host or the
     * company name in any file of the folder.
     */
    private static List<String> scan(String folder, List<String> domains) throws Exception
    {
        URL routes = Thread.currentThread().getContextClassLoader().getResource(folder + "routes.json");

        assertNotNull(routes);

        Pattern email = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

        Pattern secret = Pattern.compile("(?i)\"(password|token|secret|authorization)\"|Basic [A-Za-z0-9+/=]{8,}|Bearer [A-Za-z0-9._-]{8,}");

        List<String> files = new ArrayList<>();

        try (Stream<Path> listing = Files.list(Path.of(routes.toURI()).getParent()))
        {
            for (Path file : listing.toList())
            {
                String text = Files.readString(file, StandardCharsets.UTF_8);

                files.add(file.getFileName().toString());

                Matcher found = email.matcher(text);

                while (found.find())
                {
                    String address = found.group().toLowerCase(Locale.ROOT);

                    assertTrue(domains.stream().anyMatch(address::endsWith), file.getFileName() + " holds a real email: " + found.group());
                }

                assertFalse(secret.matcher(text).find(), file.getFileName() + " holds a credential");
                assertFalse(text.contains("172.16."), file.getFileName() + " holds the internal host");
                assertFalse(text.toLowerCase().contains("motadata"), file.getFileName() + " holds the company name");
            }
        }

        return files;
    }
}
