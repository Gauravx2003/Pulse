package com.pulse.platform.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 3.6: passwords, tokens, CTC and free-text review content never reach the log. */
class RedactorTest
{
    @Test
    void masksJsonMembers()
    {
        String out = Redactor.redact("{\"email\":\"priya@pulse.demo\",\"password\":\"demo123\",\"newPassword\":\"s3cret!x\","
                + "\"csrfToken\":\"abc\",\"ctc\":18.5,\"comment\":\"He was late \\\"again\\\"\",\"level\":\"L3\"}");

        assertEquals("{\"email\":\"priya@pulse.demo\",\"password\":\"***\",\"newPassword\":\"***\","
                + "\"csrfToken\":\"***\",\"ctc\":***,\"comment\":\"***\",\"level\":\"L3\"}", out);
    }

    @Test
    void masksKeyValuePairsCookiesAndBearerTokens()
    {
        String out = Redactor.redact("POST /api/v1/auth/password/set?token=6f1e2a&x=1 password=hunter2 ctc: 42.5 "
                + "Cookie: pulse_session=QmFzZTY0; Authorization: Bearer eyJhbGciOi.payload.sig api_key='k-123'");

        for (String secret : new String[] {"6f1e2a", "hunter2", "42.5", "QmFzZTY0", "eyJhbGciOi", "k-123"})
        {
            assertFalse(out.contains(secret), secret + " leaked: " + out);
        }

        assertTrue(out.contains("x=1"), out);
        assertTrue(out.contains("/api/v1/auth/password/set"), out);
    }

    @Test
    void leavesOrdinaryTextAlone()
    {
        String line = "Mounted module 'okrs' at /api/v1/okrs (request 1234)";

        assertEquals(line, Redactor.redact(line));
        assertEquals(null, Redactor.redact(null));
    }

    @Test
    void isIdempotent()
    {
        String once = Redactor.redact("{\"password\":\"x\"} token=abc");

        assertEquals(once, Redactor.redact(once));
    }

    @Test
    void masksLiveToolAuthorizationHeadersAndBasicCredentials()
    {
        // task 24.2: "jira-reader:S3cret-Pass" in base64
        String basic = java.util.Base64.getEncoder().encodeToString("jira-reader:S3cret-Pass".getBytes());

        String out = Redactor.redact("GET /rest/api/2/myself Authorization: Basic " + basic + " status=401 "
                + "{\"Authorization\":\"Basic " + basic + "\",\"token\":\"ghp_x1\",\"secret\":\"s-2\"} "
                + "header Basic " + basic + " authorization=token ghp_Abc");

        for (String secret : new String[] {basic, "ghp_x1", "s-2", "ghp_Abc"})
        {
            assertFalse(out.contains(secret), secret + " leaked: " + out);
        }

        assertTrue(out.contains("Authorization: ***"), out);
        assertTrue(out.contains("status=401"), out);
        assertEquals(out, Redactor.redact(out));
    }

    @Test
    void leavesOrdinaryBasicWordsAlone()
    {
        String line = "Basic setup done; basic auth mode chosen for tool jira";

        assertEquals(line, Redactor.redact(line));
    }
}
