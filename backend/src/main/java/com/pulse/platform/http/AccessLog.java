package com.pulse.platform.http;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.regex.Pattern;

/**
 * One access-log line per request, replacing Vert.x {@code LoggerHandler}, which writes the full URL,
 * client IP, referrer and user agent. Pulse URLs carry record ids (a 360° request id names its
 * reviewer), and spec 360-feedback keeps reviewer identity out of logs, so this line holds only:
 * method, path with every id replaced by {@code :id}, status, bytes, duration and request id. No
 * query string, IP, referrer or user agent. Security events keep their source IP in the audit log
 * (task 3.7), which has its own access rules.
 */
public final class AccessLog implements Handler<RoutingContext>
{
    private static final Logger log = LoggerFactory.getLogger(AccessLog.class);

    private static final Pattern ID_SEGMENT = Pattern.compile(
            "/(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|(?=[^/]*\\d)[A-Za-z0-9_-]{20,})(?=/|$)");

    @Override
    public void handle(RoutingContext ctx)
    {
        long started = System.nanoTime();
        String method = ctx.request().method().name();
        String path = safePath(ctx.request().path());
        String requestId = RequestId.of(ctx);

        ctx.addBodyEndHandler(v -> {
            long ms = (System.nanoTime() - started) / 1_000_000;

            MDC.put(RequestId.MDC_KEY, requestId);

            try
            {
                log.info("{} {} {} {}B {}ms", method, path, ctx.response().getStatusCode(),
                        ctx.response().bytesWritten(), ms);
            }
            finally
            {
                MDC.remove(RequestId.MDC_KEY);
            }
        });

        ctx.next();
    }

    /**
     * {@code path} with UUIDs and long opaque tokens (20+ URL-safe characters with a digit) replaced by
     * {@code :id}; route words such as {@code 360} or {@code v1} stay, so a log line never
     * names a record. Also used by {@link FailureHandler} for its error line.
     */
    public static String safePath(String path)
    {
        if (path == null)
        {
            return "-";
        }

        return ID_SEGMENT.matcher(path).replaceAll("/:id");
    }
}
