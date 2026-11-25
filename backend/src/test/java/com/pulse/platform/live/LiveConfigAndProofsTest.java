package com.pulse.platform.live;

import com.pulse.platform.http.ApiException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 24.5: generic config validation against configSchema/secretSpec, and the test proof rules. */
class LiveConfigAndProofsTest
{
    private static Vertx vertx;

    @BeforeAll
    static void start()
    {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop()
    {
        vertx.close();
    }

    /** A Jira-shaped schema, written here so the platform test knows no real connector. */
    private static final LiveConnector FORM = new StubLiveConnector()
    {
        @Override
        public String tool()
        {
            return "form";
        }

        @Override
        public JsonObject configSchema()
        {
            return new JsonObject().put("fields", new JsonArray()
                    .add(field("baseUrl", "url", true))
                    .add(field("authMode", "choice", true).put("options", new JsonArray()
                            .add(new JsonObject().put("value", "basic").put("label", "Username and password"))
                            .add("bearer")))
                    .add(field("username", "text", true).put("dependsOn",
                            new JsonObject().put("field", "authMode").put("values", new JsonArray().add("basic"))))
                    .add(field("projectKeys", "list", true).put("pattern", "[A-Z][A-Z0-9]{1,9}"))
                    .add(field("ignoreBotEdits", "boolean", false))
                    .add(field("since", "date", false)));
        }

        @Override
        public JsonObject secretSpec()
        {
            return new JsonObject().put("name", "password").put("label", "Password / Token");
        }

        @Override
        public Future<JsonObject> fetchPages(LiveContext ctx, String since, Function<JsonObject, Future<Void>> sink)
        {
            return Future.succeededFuture(new JsonObject());
        }
    };

    private static JsonObject field(String name, String type, boolean required)
    {
        return new JsonObject().put("name", name).put("label", name).put("type", type).put("required", required);
    }

    private static JsonObject basic()
    {
        return new JsonObject()
                .put("baseUrl", "http://172.16.10.6:8080/")
                .put("authMode", "basic")
                .put("username", "pulse-reader")
                .put("projectKeys", "PUL, OPS ,PUL")
                .put("ignoreBotEdits", true);
    }

    private static ApiException invalid(JsonObject config, String secret)
    {
        return assertThrows(ApiException.class, () -> LiveConfig.validate(FORM, config, secret, false));
    }

    // ------------------------------------------------------------------ config

    @Test
    void normalizesUrlListAndWarnsOnHttp()
    {
        JsonObject out = LiveConfig.validate(FORM, basic(), "pw-123", false);

        JsonObject config = out.getJsonObject("config");

        assertEquals("http://172.16.10.6:8080", config.getString("baseUrl"));
        assertEquals(new JsonArray().add("PUL").add("OPS"), config.getJsonArray("projectKeys"));
        assertEquals("pulse-reader", config.getString("username"));
        assertTrue(config.getBoolean("ignoreBotEdits"));

        JsonObject warning = out.getJsonArray("warnings").getJsonObject(0);

        assertEquals(BaseUrls.INSECURE_HTTP, warning.getString("code"));
        assertEquals("baseUrl", warning.getString("field"));

        JsonObject https = LiveConfig.validate(FORM, basic().put("baseUrl", "https://jira.corp.example/jira/"), "pw", false);

        assertTrue(https.getJsonArray("warnings").isEmpty());
        assertEquals("https://jira.corp.example/jira", https.getJsonObject("config").getString("baseUrl"));
    }

    @Test
    void dependentFieldOnlyCountsWhenItsChoiceHolds()
    {
        assertEquals("config.username", invalid(basic().put("username", " "), "pw").details().getString("field"));

        JsonObject bearer = basic().put("authMode", "bearer");

        bearer.remove("username");

        assertFalse(LiveConfig.validate(FORM, bearer, "pat", false).getJsonObject("config").containsKey("username"));

        // a value sent for a field that does not apply is dropped, not stored
        JsonObject extra = basic().put("authMode", "bearer");

        assertFalse(LiveConfig.validate(FORM, extra, "pat", false).getJsonObject("config").containsKey("username"));
    }

    @Test
    void rejectsBadValuesNamingTheField()
    {
        assertEquals("config.authMode", invalid(basic().put("authMode", "oauth"), "pw").details().getString("field"));
        assertEquals("config.projectKeys", invalid(basic().put("projectKeys", "pul"), "pw").details().getString("field"));
        assertEquals("config.projectKeys", invalid(basic().put("projectKeys", new JsonArray()), "pw").details().getString("field"));
        assertEquals("config.ignoreBotEdits", invalid(basic().put("ignoreBotEdits", "yes"), "pw").details().getString("field"));
        assertEquals("config.since", invalid(basic().put("since", "31/12/2026"), "pw").details().getString("field"));
        assertEquals("config.other", invalid(basic().put("other", "x"), "pw").details().getString("field"));
        assertEquals("config.baseUrl", invalid(basic().put("baseUrl", "http://169.254.169.254"), "pw").details().getString("field"));
        assertEquals("config.baseUrl", invalid(basic().put("baseUrl", "http://user:pw@jira"), "pw").details().getString("field"));
        assertEquals("config.baseUrl", invalid(basic().put("baseUrl", "ftp://jira"), "pw").details().getString("field"));
        assertEquals("config.baseUrl", invalid(basic().put("baseUrl", "http://127.0.0.1:8080"), "pw").details().getString("field"));

        // loopback only with the test-only flag
        assertNotNull(LiveConfig.validate(FORM, basic().put("baseUrl", "http://127.0.0.1:8080"), "pw", true));
    }

    @Test
    void secretIsRequiredAndNeverEchoed()
    {
        ApiException blank = invalid(basic(), "   ");

        assertEquals("secret", blank.details().getString("field"));

        LiveConnector patterned = new StubLiveConnector()
        {
            @Override
            public JsonObject secretSpec()
            {
                return new JsonObject().put("label", "Token").put("pattern", "ghp_[A-Za-z0-9]{8,}");
            }
        };

        ApiException wrong = assertThrows(ApiException.class, () -> LiveConfig.validate(patterned,
                new JsonObject().put("baseUrl", "https://api.example.com"), "not-a-token-SECRET", false));

        assertFalse(wrong.getMessage().contains("SECRET"));
    }

    @Test
    void expiryIsADateOrTimestamp()
    {
        assertNull(LiveConfig.expiresAt(" "));
        assertEquals("2026-12-31", LiveConfig.expiresAt("2026-12-31"));
        assertEquals("2026-12-31T10:00:00Z", LiveConfig.expiresAt("2026-12-31T10:00:00Z"));
        assertThrows(ApiException.class, () -> LiveConfig.expiresAt("next week"));
    }

    // ------------------------------------------------------------------ proofs

    @Test
    void proofIsBoundToToolValuesTimeAndSingleUse()
    {
        AtomicLong now = new AtomicLong(1_000_000);

        TestProofs proofs = new TestProofs(vertx, now::get);

        JsonObject config = new JsonObject().put("baseUrl", "http://jira:8080");

        String p1 = proofs.issue("jira", config, "pw", null, "2027-01-01").getString("proof");

        JsonObject used = proofs.consume(p1, "jira", config, "pw", null);

        assertNotNull(used);
        assertEquals("2027-01-01", used.getString("testedExpiresAt"));
        assertNull(proofs.consume(p1, "jira", config, "pw", null), "single use");

        String p2 = proofs.issue("jira", config, "pw", null, null).getString("proof");

        assertNull(proofs.consume(p2, "gh", config, "pw", null), "other tool");

        String p3 = proofs.issue("jira", config, "pw", null, null).getString("proof");

        assertNull(proofs.consume(p3, "jira", config, "pw2", null), "other secret");

        String p4 = proofs.issue("jira", config, "pw", null, null).getString("proof");

        assertNull(proofs.consume(p4, "jira", config.copy().put("baseUrl", "http://jira:8081"), "pw", null), "other config");

        String p5 = proofs.issue("jira", config, "pw", null, null).getString("proof");

        assertNull(proofs.consume(p5, "jira", config, "pw", "2027-01-01"), "other expiry");

        String p6 = proofs.issue("jira", config, "pw", null, null).getString("proof");

        now.addAndGet(TestProofs.TTL_MS + 1);

        assertNull(proofs.consume(p6, "jira", config, "pw", null), "expired after 10 minutes");

        String p7 = proofs.issue("jira", config, "pw", null, null).getString("proof");

        now.addAndGet(TestProofs.TTL_MS);

        assertNotNull(proofs.consume(p7, "jira", config, "pw", null), "still valid at exactly 10 minutes");

        assertNull(proofs.consume(null, "jira", config, "pw", null));
        assertNull(proofs.consume("made-up", "jira", config, "pw", null));
    }

    @Test
    void proofHoldsNoSecret()
    {
        TestProofs proofs = new TestProofs(vertx);

        String proof = proofs.issue("jira", new JsonObject(), "Very-Secret-Pw", null, null).getString("proof");

        JsonObject stored = vertx.sharedData().<String, JsonObject>getLocalMap(TestProofs.MAP).get(proof);

        assertFalse(stored.encode().contains("Very-Secret-Pw"));
        assertFalse(proof.contains("Very-Secret-Pw"));
    }
}
