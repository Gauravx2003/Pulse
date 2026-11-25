package com.pulse.modules.connectors.jira;

import com.pulse.platform.accounts.InMemoryExternalAccountService;
import com.pulse.platform.live.LiveConnector;
import com.pulse.platform.live.testkit.AbstractLiveConnectorContractTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 26.2: the Jira Server connector passes every case of the shared contract test against
 * {@code live-fixtures/jira/} (two search pages, recorded 401, 429 and 500).
 */
class JiraConnectorContractTest extends AbstractLiveConnectorContractTest
{
    static final String USERNAME = "pulse.readonly";

    static final String SECRET = "jira-test-pass-8d2f41";

    @Override
    protected LiveConnector connector()
    {
        return new JiraConnector();
    }

    @Override
    protected String fixtures()
    {
        return "jira";
    }

    @Override
    protected JsonObject config(String baseUrl)
    {
        return config(baseUrl, JiraConnector.BASIC);
    }

    static JsonObject config(String baseUrl, String authMode)
    {
        return new JsonObject()
                .put("baseUrl", baseUrl)
                .put("authMode", authMode)
                .put("username", USERNAME)
                .put("projectKeys", new JsonArray().add("PROJ"))
                .put("storyPointsField", "customfield_10111")
                .put("sprintField", "customfield_10108")
                .put("ignoreBotEdits", true);
    }

    @Override
    protected String secret()
    {
        return SECRET;
    }

    @Override
    protected List<String> secretForms()
    {
        return List.of(SECRET, Base64.getEncoder().encodeToString((USERNAME + ":" + SECRET).getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    protected void people(InMemoryExternalAccountService accounts)
    {
        for (int i = 1; i <= 6; i++)
        {
            accounts.person(UUID.randomUUID(), "Dev User " + i, "Dev.User" + i + "@example.com");
        }
    }

    @Override
    protected Set<String> expectedUnmatchedAccounts()
    {
        // dev.user7: Jira hides the email; svc.automation: no person has its email
        return Set.of("dev.user7", "svc.automation");
    }

    @Override
    protected void assertCursorUsed(String cursor, List<JsonObject> requests)
    {
        String bound = "updated >= \"" + JiraMapper.jqlSince(cursor) + "\"";

        assertTrue(requests.stream()
                        .filter(r -> "/rest/api/2/search".equals(r.getString("path")))
                        .allMatch(r -> r.getJsonObject("query").getString("jql", "").contains(bound)),
                "every search of the second run asks from one minute before the cursor: " + bound);
    }
}
