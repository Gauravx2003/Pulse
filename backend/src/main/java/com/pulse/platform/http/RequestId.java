package com.pulse.platform.http;

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Gives every request an id (task 3.6, spec security-baseline "Safe errors and logging"): stored on
 * the routing context, sent back as {@code X-Request-Id}, quoted in error bodies and audit entries,
 * and put in the logging MDC as {@code requestId} while the handler chain runs on this thread.
 *
 * <p>The id and the client IP are also stored on the request's own Vert.x context (each HTTP request
 * runs on a duplicated context that database callbacks keep), so services that never see the
 * {@link RoutingContext}, such as {@link com.pulse.platform.audit.AuditService}, can read them with
 * {@link #current()} and {@link #currentIp()} (task 3.7).
 */
public final class RequestId implements Handler<RoutingContext>
{
    public static final String HEADER = "X-Request-Id";

    /** Context key of the request id (String). */
    public static final String KEY = "pulse.requestId";

    /** Context-local key of the client IP (String). */
    public static final String IP_KEY = "pulse.clientIp";

    public static final String MDC_KEY = "requestId";

    private final boolean trustProxy;

    public RequestId(boolean trustProxy)
    {
        this.trustProxy = trustProxy;
    }

    @Override
    public void handle(RoutingContext ctx)
    {
        String id = UUID.randomUUID().toString();

        ctx.put(KEY, id);
        ctx.response().putHeader(HEADER, id);

        Context context = Vertx.currentContext();

        if (context != null)
        {
            context.putLocal(KEY, id);
            context.putLocal(IP_KEY, ClientIp.of(ctx, trustProxy));
        }

        MDC.put(MDC_KEY, id);

        try
        {
            ctx.next();
        }
        finally
        {
            MDC.remove(MDC_KEY);
        }
    }

    /** The id of this request; a fresh one when the handler did not run (e.g. a failure before it). */
    public static String of(RoutingContext ctx)
    {
        String id = ctx.get(KEY);

        if (id == null)
        {
            id = UUID.randomUUID().toString();

            ctx.put(KEY, id);
        }

        return id;
    }

    /** The id of the request being handled on the current Vert.x context; null outside a request. */
    public static String current()
    {
        Context context = Vertx.currentContext();

        return context == null ? null : context.getLocal(KEY);
    }

    /** The client IP of the request being handled on the current Vert.x context; null outside a request. */
    public static String currentIp()
    {
        Context context = Vertx.currentContext();

        return context == null ? null : context.getLocal(IP_KEY);
    }
}
