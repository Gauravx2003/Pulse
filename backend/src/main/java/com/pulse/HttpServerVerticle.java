package com.pulse;

import com.pulse.platform.auth.AuthRoutes;
import com.pulse.platform.auth.AuthService;
import com.pulse.platform.auth.AuthSettings;
import com.pulse.platform.auth.PasswordService;
import com.pulse.platform.auth.PermissionCatalogue;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.BodyLimits;
import com.pulse.platform.http.FailureHandler;
import com.pulse.platform.http.OriginGuard;
import com.pulse.platform.http.RateLimiter;
import com.pulse.platform.http.RequestId;
import com.pulse.platform.http.SecurityHeaders;
import com.pulse.platform.http.SecuritySettings;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.ModuleRegistry;
import com.pulse.platform.module.PulseModule;
import com.pulse.platform.nav.Navigation;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.pgclient.PgPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Set;

/**
 * HTTP entry point: mounts sign-in and the session guard, then the {@code /api/v1} sub-routers of the
 * self-registering modules. Every failure goes to the one {@link FailureHandler}.
 */
public class HttpServerVerticle extends AbstractVerticle
{
    private static final Logger log = LoggerFactory.getLogger(HttpServerVerticle.class);

    public static final String API_PREFIX = "/api/v1";

    @Override
    public void start(Promise<Void> startPromise)
    {
        int defaultPort = 8088;

        if (System.getenv("PORT") != null)
        {
            defaultPort = Integer.parseInt(System.getenv("PORT"));
        }
        else if (System.getenv("HTTP_PORT") != null)
        {
            defaultPort = Integer.parseInt(System.getenv("HTTP_PORT"));
        }

        int port = config().getInteger("http.port", Integer.getInteger("http.port", defaultPort));

        ClassLoader loader = getClass().getClassLoader();

        vertx.executeBlocking(() -> ModuleRegistry.discover(loader))
                .compose(registry -> vertx.createHttpServer().requestHandler(buildRouter(registry)).listen(port))
                .onSuccess(http ->
                {
                    log.info("Pulse HTTP server started on port {}", http.actualPort());
                    startPromise.complete();
                })
                .onFailure(err ->
                {
                    log.error("Failed to start HTTP server", err);
                    startPromise.fail(err);
                });
    }

    private Router buildRouter(ModuleRegistry registry)
    {
        Router router = Router.router(vertx);

        AuthSettings authSettings = AuthSettings.from(config());
        SecuritySettings security = SecuritySettings.from(config());

        // Security baseline (task 3.6), in order: request id, hardening headers, CORS to the app
        // origin, Origin check on writes, per-IP limit, then the body read with a streaming size limit.
        router.route().handler(new RequestId());
        router.route().handler(new SecurityHeaders());
        router.route().handler(CorsHandler.create()
                .addOrigin(security.appOrigin())
                .allowCredentials(true)
                .allowedMethods(Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE))
                .allowedHeaders(Set.of("Content-Type", "X-CSRF-Token", "If-Match"))
                .exposedHeaders(Set.of(RequestId.HEADER, "Retry-After")));
        router.route().handler(new OriginGuard(security.appOrigin()));
        router.route().handler(RateLimiter.perIp(vertx, "ip", security.ipPerMinute(), 60, authSettings.trustProxy()));
        router.route().handler(LoggerHandler.create());
        router.route().handler(new BodyLimits(security));
        router.route().failureHandler(new FailureHandler());

        router.get("/health").handler(ctx -> ctx.json(new JsonObject()
                .put("status", "UP")
                .put("service", "pulse-backend")
                .put("timestamp", Instant.now().toString())));

        PgPool pool = Db.pool(vertx, config());

        SqlExecutor sql = new SqlExecutor(pool);

        PermissionCatalogue catalogue = PermissionCatalogue.of(registry);

        AuthService auth = new AuthService(vertx, sql, authSettings, catalogue.keys());

        AuthRoutes.mount(router, API_PREFIX, auth, new PasswordService(vertx, sql, auth), Navigation.of(registry),
                RateLimiter.perUser(vertx, "user", security.userPerMinute(), 60));

        PolicyService policy = new PolicyService(sql, catalogue.keys(), registry.visibilityRelations(),
                authSettings.trustProxy());

        ModuleContext moduleContext = new ModuleContext(vertx, config(), pool, policy);

        for (PulseModule module : registry.modules())
        {
            Router sub = Router.router(vertx);

            module.routes(sub, moduleContext);

            router.route(API_PREFIX + "/" + module.id() + "/*").subRouter(sub);

            log.info("Mounted module '{}' at {}/{}", module.id(), API_PREFIX, module.id());
        }

        return router;
    }
}
