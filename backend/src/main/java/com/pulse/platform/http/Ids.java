package com.pulse.platform.http;

import io.vertx.ext.web.RoutingContext;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Record ids in URLs (task 3.8, spec api-conventions "Opaque identifiers"): every id is a random UUID.
 * A path id that is not a UUID addresses nothing, so it gets the same 404 as a missing record, never a
 * different error that would help enumeration. Person ids go through
 * {@link com.pulse.platform.policy.PolicyService#requireVisible} instead, which answers 403 alike.
 */
public final class Ids
{
    private static final Pattern CANONICAL = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Ids()
    {
    }

    /** The UUID in {@code value}, or null when it is not a canonical UUID. */
    public static UUID parse(String value)
    {
        return value != null && CANONICAL.matcher(value).matches() ? UUID.fromString(value) : null;
    }

    /** The path parameter as a UUID; throws 404 {@code not_found} when it is not one. */
    public static UUID path(RoutingContext ctx, String name)
    {
        UUID id = parse(ctx.pathParam(name));

        if (id == null)
        {
            throw ApiException.notFound("not_found", "Not found");
        }

        return id;
    }
}
