package com.pulse.platform.http;

import io.vertx.core.json.JsonArray;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The access log never names a record (spec 360-feedback: reviewer identity never reaches logs), and
 * error objects can carry module-specific details.
 */
class AccessLogTest
{
    @Test
    void idsInPathsAreMasked()
    {
        assertEquals("/api/v1/reviews/360/requests/:id/submit",
                AccessLog.safePath("/api/v1/reviews/360/requests/3f2a9c1e-8b7d-4e6f-9a0b-1c2d3e4f5a6b/submit"));
        assertEquals("/api/v1/imports/:id/undo", AccessLog.safePath("/api/v1/imports/3F2A9C1E-8B7D-4E6F-9A0B-1C2D3E4F5A6B/undo"));
        assertEquals("/api/v1/auth/invite/:id", AccessLog.safePath("/api/v1/auth/invite/Zx8_kq-3LmP0aB7cD9eF2gH"));
        assertEquals("/api/v1/auth/reset/:id", AccessLog.safePath("/api/v1/auth/reset/9f86d081884c7d659a2feaa0c55ad015"));
        assertEquals("/api/v1/reviews/360/cycles", AccessLog.safePath("/api/v1/reviews/360/cycles"));
        assertEquals("/health", AccessLog.safePath("/health"));
        assertEquals("-", AccessLog.safePath(null));
    }

    @Test
    void apiExceptionCarriesGenericDetailsButNotReservedNames()
    {
        JsonArray conflicts = new JsonArray().add("row 3: edited after import");

        ApiException e = ApiException.conflict("undo_conflict", "Some rows changed since the import")
                .detail("conflicts", conflicts);

        assertEquals(conflicts, e.details().getJsonArray("conflicts"));

        for (String reserved : new String[] {"code", "message", "requestId", "field", "retryAfter", "currentVersion"})
        {
            assertThrows(IllegalArgumentException.class, () -> e.detail(reserved, "x"));
        }
    }
}
