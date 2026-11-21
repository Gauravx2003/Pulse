package com.pulse.platform.auth;

import com.pulse.platform.http.ApiException;
import io.vertx.core.Handler;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.util.Set;

/**
 * Guards every {@code /api/v1} route except sign-in (task 3.2): resolves the session cookie to the
 * user (401 otherwise) and, for state-changing methods, requires the {@code X-CSRF-Token} header to
 * match the session's CSRF token (403 otherwise). On success the user is available to handlers as
 * {@code ctx.get(SessionHandler.USER)}.
 */
public class SessionHandler implements Handler<RoutingContext>
{
    public static final String COOKIE = "pulse_session";

    public static final String CSRF_HEADER = "X-CSRF-Token";

    /** Context key of the signed-in user (JsonObject). */
    public static final String USER = "pulse.user";

    /** Context key of the session's CSRF token. */
    public static final String CSRF = "pulse.csrf";

    private static final Set<HttpMethod> SAFE = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final AuthService auth;

    public SessionHandler(AuthService auth)
    {
        this.auth = auth;
    }

    @Override
    public void handle(RoutingContext ctx)
    {
        Cookie cookie = ctx.request().getCookie(COOKIE);

        auth.authenticate(cookie == null ? null : cookie.getValue()).onComplete(result ->
        {
            if (result.failed())
            {
                Throwable cause = result.cause();
                int status = cause instanceof ApiException api ? api.status() : 500;

                if (status == 401)
                {
                    ctx.response().addCookie(expiredCookie());
                }

                ctx.fail(status, cause);

                return;
            }

            JsonObject session = result.result();

            if (!SAFE.contains(ctx.request().method())
                    && !SessionTokens.same(ctx.request().getHeader(CSRF_HEADER), session.getString("csrfToken")))
            {
                ctx.fail(403, ApiException.forbidden("csrf_invalid", "This request could not be verified. Reload the page and try again."));

                return;
            }

            ctx.put(USER, session.getJsonObject("user"));
            ctx.put(CSRF, session.getString("csrfToken"));
            ctx.next();
        });
    }

    /** HttpOnly, Secure, SameSite=Strict cookie that lives as long as the session. */
    public static Cookie sessionCookie(String token, int maxAgeSeconds)
    {
        return Cookie.cookie(COOKIE, token)
                .setPath("/")
                .setHttpOnly(true)
                .setSecure(true)
                .setSameSite(io.vertx.core.http.CookieSameSite.STRICT)
                .setMaxAge(maxAgeSeconds);
    }

    public static Cookie expiredCookie()
    {
        return sessionCookie("", 0);
    }
}
