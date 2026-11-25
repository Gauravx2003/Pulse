package com.pulse.platform.audit;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Task 24.2: live-tool secrets never reach an audit summary. */
class AuditSanitizeTest
{
    @Test
    void redactsLiveToolSecretFields()
    {
        JsonObject summary = AuditService.sanitize(new JsonObject()
                .put("tool", "jira")
                .put("password", "Jira-P4ssw0rd")
                .put("token", "ghp_Abc123")
                .put("secret", "s3")
                .put("Authorization", "Basic amlyYTpQNHNz")
                .put("secret_ciphertext", "AAEC")
                .put("config", new JsonObject().put("baseUrl", "http://172.16.10.6:8080").put("apiToken", "t-9")));

        assertEquals("jira", summary.getString("tool"));
        assertEquals("http://172.16.10.6:8080", summary.getJsonObject("config").getString("baseUrl"));

        for (String secret : new String[] {"Jira-P4ssw0rd", "ghp_Abc123", "s3", "amlyYTpQNHNz", "AAEC", "t-9"})
        {
            assertFalse(summary.encode().contains(secret), secret + " in " + summary.encode());
        }

        assertEquals(AuditService.REDACTED, summary.getString("Authorization"));
        assertEquals(AuditService.REDACTED, summary.getString("secret_ciphertext"));
    }
}
