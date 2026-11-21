package com.pulse.platform.http;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Gives every request an id (task 3.6, spec security-baseline "Safe errors and logging"): stored on
 * the routing context, sent back as {@code X-Request-Id}, quoted in error bodies and audit entries,
 * and put in the logging MDC as {@code requestId} while the handler chain runs on this thread.
 */
public final class RequestId implements Handler<RoutingContext>
{
    public static final String HEADER = "X-Request-Id";

    /** Context key of the request id (String). */
    public static final String KEY = "pulse.requestId";

    public static final String MDC_KEY = "requestId";

    @Override
    public void handle(RoutingContext ctx)
    {
        String id = UUID.randomUUID().toString();

        ctx.put(KEY, id);
        ctx.response().putHeader(HEADER, id);

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
}
