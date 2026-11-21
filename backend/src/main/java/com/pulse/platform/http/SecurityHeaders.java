package com.pulse.platform.http;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.RoutingContext;

/**
 * Browser hardening headers on every backend response (task 3.6, spec security-baseline "Browser
 * hardening headers"). The backend only answers JSON, so its CSP allows nothing to load or frame it;
 * the SPA's own CSP (scripts from 'self' only) is set by the frontend nginx. API responses are never
 * cached.
 */
public final class SecurityHeaders implements Handler<RoutingContext>
{
    public static final String CSP = "default-src 'none'; script-src 'self'; frame-ancestors 'none'; "
            + "base-uri 'none'; form-action 'self'";

    public static final String PERMISSIONS_POLICY = "accelerometer=(), camera=(), geolocation=(), gyroscope=(), "
            + "magnetometer=(), microphone=(), payment=(), usb=(), interest-cohort=()";

    @Override
    public void handle(RoutingContext ctx)
    {
        MultiMap headers = ctx.response().headers();

        headers.set("Content-Security-Policy", CSP);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", PERMISSIONS_POLICY);
        headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");

        if (ctx.request().path().startsWith("/api/"))
        {
            headers.set("Cache-Control", "no-store");
        }

        ctx.next();
    }
}
