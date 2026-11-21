package com.pulse.platform.http;

import com.pulse.platform.auth.SessionHandler;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.ext.web.RoutingContext;

import java.util.function.Function;

/**
 * Fixed-window request limits (task 3.6, spec security-baseline "Rate limiting and payload limits").
 * Counters live in {@code vertx.sharedData()}, so every HTTP verticle instance of the process counts
 * against the same budget (design.md decision 1: no per-instance mutable state). Over the limit the
 * request fails with 429 {@code rate_limited} and a Retry-After value.
 *
 * <p>The platform mounts a per-IP limit on every route and a per-user limit on {@code /api/v1}.
 * Modules add stricter named limits on expensive routes:
 * {@code router.post("/upload").handler(RateLimiter.perUser(vertx, "imports", 10, 60))}.
 * Sign-in and password reset keep their own database-backed throttle (task 3.1).
 */
public final class RateLimiter implements Handler<RoutingContext>
{
    private static final String MAP = "pulse.rateLimits";

    private final Vertx vertx;

    private final String name;

    private final int limit;

    private final long windowMillis;

    private final Function<RoutingContext, String> key;

    private RateLimiter(Vertx vertx, String name, int limit, int windowSeconds, Function<RoutingContext, String> key)
    {
        if (limit < 1 || windowSeconds < 1)
        {
            throw new IllegalArgumentException("limit and window must be positive");
        }

        this.vertx = vertx;
        this.name = name;
        this.limit = limit;
        this.windowMillis = windowSeconds * 1000L;
        this.key = key;
    }

    /** Limit per client IP. */
    public static RateLimiter perIp(Vertx vertx, String name, int limit, int windowSeconds, boolean trustProxy)
    {
        return new RateLimiter(vertx, name, limit, windowSeconds, ctx -> "ip:" + ClientIp.of(ctx, trustProxy));
    }

    /** Limit per signed-in user (after the session guard); requests without a user are not counted. */
    public static RateLimiter perUser(Vertx vertx, String name, int limit, int windowSeconds)
    {
        return new RateLimiter(vertx, name, limit, windowSeconds, ctx ->
        {
            JsonObject user = ctx.get(SessionHandler.USER);

            return user == null ? null : "user:" + user.getString("id");
        });
    }

    @Override
    public void handle(RoutingContext ctx)
    {
        String who = key.apply(ctx);

        if (who == null)
        {
            ctx.next();

            return;
        }

        long now = System.currentTimeMillis();
        long window = now / windowMillis;

        LocalMap<String, Long> counters = vertx.sharedData().getLocalMap(MAP);

        String counter = name + "|" + who + "|" + windowMillis + "|" + window;

        long count = counters.merge(counter, 1L, Long::sum);

        if (count == 1)
        {
            // first hit of a new window: drop this limiter's expired windows so the map stays small
            String prefix = name + "|";

            for (String k : counters.keySet())
            {
                if (k.startsWith(prefix) && expired(k, now))
                {
                    counters.remove(k);
                }
            }
        }

        if (count > limit)
        {
            long retryAfter = Math.max(1, ((window + 1) * windowMillis - now + 999) / 1000);

            ctx.fail(429, ApiException.tooManyRequests("rate_limited", "Too many requests. Try again in a moment.")
                    .retryAfter(retryAfter));

            return;
        }

        ctx.next();
    }

    private static boolean expired(String counterKey, long now)
    {
        String[] parts = counterKey.split("\\|");

        try
        {
            long size = Long.parseLong(parts[parts.length - 2]);
            long window = Long.parseLong(parts[parts.length - 1]);

            return window < now / size;
        }
        catch (RuntimeException e)
        {
            return true;
        }
    }
}
