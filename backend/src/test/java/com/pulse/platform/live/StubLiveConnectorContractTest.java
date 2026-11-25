package com.pulse.platform.live;

import com.pulse.platform.accounts.InMemoryExternalAccountService;
import com.pulse.platform.live.testkit.AbstractLiveConnectorContractTest;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 24.4: the stub connector passes every case of the shared contract test, against
 * {@code live-fixtures/stub/}. This is also the smallest example of a connector contract test.
 */
class StubLiveConnectorContractTest extends AbstractLiveConnectorContractTest
{
    private static final String SECRET = "stub-secret-7c1f9a";

    @Override
    protected LiveConnector connector()
    {
        return new StubLiveConnector();
    }

    @Override
    protected String fixtures()
    {
        return "stub";
    }

    @Override
    protected JsonObject config(String baseUrl)
    {
        return new JsonObject().put("baseUrl", baseUrl);
    }

    @Override
    protected String secret()
    {
        return SECRET;
    }

    @Override
    protected void people(InMemoryExternalAccountService accounts)
    {
        accounts.person(UUID.randomUUID(), "Priya", "priya@corp.com");
    }

    @Override
    protected Set<String> expectedUnmatchedAccounts()
    {
        return Set.of("jdoe", "kmehta");
    }

    @Override
    protected void assertCursorUsed(String cursor, List<JsonObject> requests)
    {
        assertTrue(requests.stream().anyMatch(r -> cursor.equals(r.getJsonObject("query").getString("since"))),
                "the second run sends since=" + cursor);
    }
}
