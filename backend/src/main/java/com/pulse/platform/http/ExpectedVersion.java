package com.pulse.platform.http;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/**
 * The version a write is based on (task 3.8, spec api-conventions "Optimistic concurrency"): the body's
 * {@code version} member, or else the {@code If-Match} header ({@code 3}, {@code "3"} or {@code W/"3"}).
 * Pass it to {@link com.pulse.platform.db.BaseRepository}'s {@code updateVersioned}; a stale value then
 * answers 409 {@code version_conflict} with the current version.
 */
public final class ExpectedVersion
{
    private ExpectedVersion()
    {
    }

    /** Throws 400 {@code invalid_field} (field {@code version}) when neither carries a whole number. */
    public static int of(RoutingContext ctx, JsonObject body)
    {
        Object fromBody = body == null ? null : body.getValue("version");

        if (fromBody instanceof Integer v)
        {
            return v;
        }

        if (fromBody != null)
        {
            throw invalid();
        }

        String header = ctx.request().getHeader("If-Match");

        if (header != null)
        {
            String value = header.strip();

            if (value.startsWith("W/"))
            {
                value = value.substring(2);
            }

            value = value.replace("\"", "");

            try
            {
                return Integer.parseInt(value);
            }
            catch (NumberFormatException e)
            {
                throw invalid();
            }
        }

        throw invalid();
    }

    private static ApiException invalid()
    {
        return ApiException.badRequest("invalid_field", "'version' is required: send the version you loaded.")
                .field("version");
    }
}
