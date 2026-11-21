package com.pulse.platform.http;

import io.vertx.ext.web.RoutingContext;

/** The client IP used for throttling and audit entries. */
public final class ClientIp
{
    private ClientIp()
    {
    }

    /**
     * With {@code trustProxy} (only behind our nginx, which sets {@code X-Real-IP}) the header is
     * used; otherwise the socket address, so a client cannot choose its own IP.
     */
    public static String of(RoutingContext ctx, boolean trustProxy)
    {
        if (trustProxy)
        {
            String real = ctx.request().getHeader("X-Real-IP");

            if (real != null && !real.isBlank())
            {
                return real.strip();
            }
        }

        return ctx.request().remoteAddress() == null ? "unknown" : ctx.request().remoteAddress().host();
    }
}
