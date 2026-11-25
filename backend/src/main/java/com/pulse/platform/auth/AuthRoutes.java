package com.pulse.platform.auth;

import com.pulse.platform.http.ApiException;
import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.ClientIp;
import com.pulse.platform.nav.Navigation;
import io.vertx.core.Handler;
import io.vertx.core.http.Cookie;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mounts sign-in, the password-link endpoints, the session guard, sign-out, password change and
 * {@code GET /me} (tasks 3.1 to 3.4). Everything under {@code /api/v1} registered after {@link #mount}
 * requires a session; sign-in, reset request and set-password are the only public API routes.
 */
public final class AuthRoutes
{
    /** Request bodies (task 3.6): only these fields, bounded lengths; blank values get the flows' own messages. */
    static final BodySchema SIGN_IN = BodySchema.object().string("email", 254).string("password", 200);

    static final BodySchema FORGOT = BodySchema.object().string("email", 254);

    static final BodySchema SET_PASSWORD = BodySchema.object().string("token", 200).string("password", 200);

    static final BodySchema CHANGE_PASSWORD = BodySchema.object().string("currentPassword", 200)
            .string("newPassword", 200);

    private AuthRoutes()
    {
    }

    /**
     * @param afterSession handlers run on every {@code /api/v1} request right after the session guard
     *                     (the per-user rate limit)
     */
    @SafeVarargs
    public static void mount(Router router, String apiPrefix, AuthService auth, PasswordService passwords,
                             Navigation navigation, Handler<RoutingContext>... afterSession)
    {
        boolean trustProxy = auth.settings().trustProxy();

        router.post(apiPrefix + "/auth/login").handler(ctx -> signIn(ctx, auth, navigation, trustProxy));

        router.post(apiPrefix + "/auth/password/forgot").handler(ctx ->
        {
            JsonObject body = jsonBody(ctx, FORGOT, "invalid_email", "Enter your work email.");

            if (body == null)
            {
                return;
            }

            passwords.requestReset(body.getString("email"), ClientIp.of(ctx, trustProxy))
                    .onSuccess(ctx::json)
                    .onFailure(err -> ctx.fail(status(err), err));
        });

        router.post(apiPrefix + "/auth/password/set").handler(ctx ->
        {
            JsonObject body = jsonBody(ctx, SET_PASSWORD, "invalid_password", PasswordService.MSG_ENTER_PASSWORD);

            if (body == null)
            {
                return;
            }

            Cookie previous = ctx.request().getCookie(SessionHandler.COOKIE);

            passwords.setPassword(body.getString("token"), body.getString("password"), ClientIp.of(ctx, trustProxy),
                            previous == null ? null : previous.getValue())
                    .onSuccess(result -> startSession(ctx, auth, navigation, result))
                    .onFailure(err -> ctx.fail(status(err), err));
        });

        router.route(apiPrefix + "/*").handler(new SessionHandler(auth));

        for (Handler<RoutingContext> handler : afterSession)
        {
            router.route(apiPrefix + "/*").handler(handler);
        }

        router.post(apiPrefix + "/auth/logout").handler(ctx ->
        {
            Cookie cookie = ctx.request().getCookie(SessionHandler.COOKIE);

            auth.signOut(cookie.getValue(), ctx.get(SessionHandler.USER), ClientIp.of(ctx, trustProxy))
                    .onSuccess(v -> ctx.response()
                            .addCookie(SessionHandler.expiredCookie())
                            .setStatusCode(204)
                            .end())
                    .onFailure(ctx::fail);
        });

        router.post(apiPrefix + "/auth/password/change").handler(ctx ->
        {
            JsonObject body = jsonBody(ctx, CHANGE_PASSWORD, "invalid_password", "Enter your current and new password.");

            if (body == null)
            {
                return;
            }

            passwords.changePassword(ctx.get(SessionHandler.USER), ctx.request().getCookie(SessionHandler.COOKIE).getValue(),
                            body.getString("currentPassword"), body.getString("newPassword"), ClientIp.of(ctx, trustProxy))
                    .onSuccess(v -> ctx.response().setStatusCode(204).end())
                    .onFailure(err -> ctx.fail(status(err), err));
        });

        router.get(apiPrefix + "/me").handler(ctx ->
                ctx.json(meBody(ctx.get(SessionHandler.USER), ctx.get(SessionHandler.CSRF), navigation)));
    }

    private static void signIn(RoutingContext ctx, AuthService auth, Navigation navigation, boolean trustProxy)
    {
        JsonObject body = jsonBody(ctx, SIGN_IN, "invalid_sign_in", "Enter your work email and password.");

        if (body == null)
        {
            return;
        }

        Cookie previous = ctx.request().getCookie(SessionHandler.COOKIE);

        auth.signIn(body.getString("email"), body.getString("password"), ClientIp.of(ctx, trustProxy),
                        previous == null ? null : previous.getValue())
                .onSuccess(result -> startSession(ctx, auth, navigation, result))
                .onFailure(err -> ctx.fail(status(err), err));
    }

    /** Sets the session cookie of {@code {token, csrfToken, user}} and answers with the {@code /me} body. */
    private static void startSession(RoutingContext ctx, AuthService auth, Navigation navigation, JsonObject result)
    {
        ctx.response().addCookie(SessionHandler.sessionCookie(result.getString("token"),
                auth.settings().sessionTtlSeconds()));

        ctx.json(meBody(result.getJsonObject("user"), result.getString("csrfToken"), navigation));
    }

    /**
     * The session bootstrap of spec api-conventions: user, role, permissions, the menu generated from
     * them (task 3.4), CSRF token and landing page.
     */
    static JsonObject meBody(JsonObject user, String csrfToken, Navigation navigation)
    {
        Set<String> permissions = new LinkedHashSet<>();

        user.getJsonArray("permissions", new JsonArray()).forEach(p -> permissions.add(String.valueOf(p)));

        JsonArray menu = navigation.menuFor(permissions, user.getString("personId") != null);

        return new JsonObject()
                .put("user", new JsonObject()
                        .put("id", user.getString("id"))
                        .put("name", user.getString("name"))
                        .put("email", user.getString("email"))
                        .put("personId", user.getString("personId")))
                .put("role", user.getString("role"))
                .put("permissions", new JsonArray(permissions.stream().toList()))
                .put("menu", menu)
                .put("csrfToken", csrfToken)
                .put("landing", landing(user.getString("landing"), menu));
    }

    /**
     * The landing page when the menu offers it, else the menu's first page: an account without a person
     * profile and without team.view or all.view has no Overview (prototype {@code routesFor}), so it
     * lands on, for example, Customer visits.
     */
    static String landing(String preferred, JsonArray menu)
    {
        String first = null;

        for (Object g : menu)
        {
            for (Object i : ((JsonObject) g).getJsonArray("items", new JsonArray()))
            {
                String path = ((JsonObject) i).getString("path");

                if (path != null && path.equals(preferred))
                {
                    return preferred;
                }

                first = first == null ? path : first;
            }
        }

        return first == null ? preferred : first;
    }

    /**
     * The request body checked against {@code schema}; on a missing or non-object body fails the request
     * with 400 {@code missingCode}, on a schema violation with 400 {@code invalid_field}, and returns null.
     */
    private static JsonObject jsonBody(RoutingContext ctx, BodySchema schema, String missingCode, String missingMessage)
    {
        JsonObject body;

        try
        {
            body = ctx.body().asJsonObject();
        }
        catch (DecodeException | ClassCastException e)
        {
            body = null;
        }

        if (body == null)
        {
            ctx.fail(400, ApiException.badRequest(missingCode, missingMessage));

            return null;
        }

        try
        {
            return schema.validate(body);
        }
        catch (ApiException e)
        {
            ctx.fail(400, e);

            return null;
        }
    }

    private static int status(Throwable err)
    {
        return err instanceof ApiException api ? api.status() : 500;
    }
}
