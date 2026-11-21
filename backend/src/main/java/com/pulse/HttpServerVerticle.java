package com.pulse;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditRoutes;
import com.pulse.platform.auth.AuthRoutes;
import com.pulse.platform.auth.AuthService;
import com.pulse.platform.auth.AuthSettings;
import com.pulse.platform.auth.PasswordService;
import com.pulse.platform.auth.PermissionCatalogue;
import com.pulse.platform.config.RuntimeProfile;
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

    private static final long CLOSE_PERIODS_EVERY_MS = 60 * 60 * 1000L;

    private CalcService calc;

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
                .compose(registry -> vertx.executeBlocking(() -> FormulaRegistry.discover(loader))
                        .compose(formulas -> vertx.createHttpServer()
                                .requestHandler(buildRouter(registry, formulas)).listen(port)))
                .onSuccess(http ->
                {
                    log.info("Pulse HTTP server started on port {}", http.actualPort());
                    startPromise.complete();
                    closePeriods();
                })
                .onFailure(err ->
                {
                    log.error("Failed to start HTTP server", err);
                    startPromise.fail(err);
                });
    }

    /**
     * Freezes the periods that ended (task 4.10): once at start, then hourly. A failure is logged and
     * retried on the next run; it never stops the server.
     */
    private void closePeriods()
    {
        if (!config().getBoolean("calc.closePeriods", true))
        {
            return;
        }

        Runnable run = () -> calc.closeEndedPeriods().onFailure(e -> log.error("Closing ended periods failed", e));

        run.run();

        vertx.setPeriodic(CLOSE_PERIODS_EVERY_MS, id -> run.run());
    }

    private Router buildRouter(ModuleRegistry registry, FormulaRegistry formulas)
    {
        Router router = Router.router(vertx);

        AuthSettings authSettings = AuthSettings.from(config());
        SecuritySettings security = SecuritySettings.from(config());

        // Security baseline (task 3.6), in order: request id, hardening headers, CORS to the app
        // origin, Origin check on writes, per-IP limit, then the body read with a streaming size limit.
        router.route().handler(new RequestId(authSettings.trustProxy()));
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
        FailureHandler failures = new FailureHandler();

        router.route().failureHandler(failures);

        // unmatched routes and methods answer with the same JSON error contract (task 3.8)
        router.errorHandler(404, failures);
        router.errorHandler(405, failures);

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

        AuditEvents.register(registry.auditEvents());

        AuditRoutes.mount(router, API_PREFIX, policy, sql);

        calc = new CalcService(sql, PulseClock.from(config(), RuntimeProfile.from(config())), formulas).listen(vertx);

        ModuleContext moduleContext = new ModuleContext(vertx, config(), pool, policy, calc);

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
