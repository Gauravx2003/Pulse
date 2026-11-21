package com.pulse.platform.http;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;

import java.util.Set;

/**
 * Rejects state-changing requests sent by a page on another origin (task 3.6, spec security-baseline
 * "Request forgery defence"): when a browser sends {@code Origin} (or, without it, {@code Referer}) on
 * POST, PUT, PATCH or DELETE, it must be the application origin. Requests without either header come
 * from non-browser clients and still need the session's CSRF token.
 */
public final class OriginGuard implements Handler<RoutingContext>
{
    private static final Set<HttpMethod> SAFE = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final String appOrigin;

    public OriginGuard(String appOrigin)
    {
        this.appOrigin = appOrigin;
    }

    @Override
    public void handle(RoutingContext ctx)
    {
        if (SAFE.contains(ctx.request().method()))
        {
            ctx.next();

            return;
        }

        String origin = ctx.request().getHeader("Origin");

        if (origin == null)
        {
            String referer = ctx.request().getHeader("Referer");

            origin = referer == null ? null : SecuritySettings.origin(referer);
        }

        if (origin != null && !appOrigin.equalsIgnoreCase(origin.strip()))
        {
            ctx.fail(403, ApiException.forbidden("origin_rejected", "This request could not be verified. Reload the page and try again."));

            return;
        }

        ctx.next();
    }
}
