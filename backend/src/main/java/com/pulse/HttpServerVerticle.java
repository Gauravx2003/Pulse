package com.pulse;

import com.pulse.platform.db.Db;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.ModuleRegistry;
import com.pulse.platform.module.PulseModule;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Set;

/** HTTP entry point: mounts {@code /api/v1} sub-routers from self-registering modules. */
public class HttpServerVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(HttpServerVerticle.class);
    public static final String API_PREFIX = "/api/v1";

    private ModuleRegistry registry;

    @Override
    public void start(Promise<Void> startPromise) {
        int defaultPort = 8088;
        if (System.getenv("PORT") != null) {
            defaultPort = Integer.parseInt(System.getenv("PORT"));
        } else if (System.getenv("HTTP_PORT") != null) {
            defaultPort = Integer.parseInt(System.getenv("HTTP_PORT"));
        }
        int port = config().getInteger("http.port", Integer.getInteger("http.port", defaultPort));

        ClassLoader loader = getClass().getClassLoader();
        vertx.executeBlocking(() -> ModuleRegistry.discover(loader))
                .compose(found -> {
                    registry = found;
                    return vertx.createHttpServer().requestHandler(buildRouter()).listen(port);
                })
                .onSuccess(http -> {
                    log.info("Pulse HTTP server started on port {}", port);
                    startPromise.complete();
                })
                .onFailure(err -> {
                    log.error("Failed to start HTTP server", err);
                    startPromise.fail(err);
                });
    }

    private Router buildRouter() {
        Router router = Router.router(vertx);
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());

        router.get("/health").handler(ctx -> ctx.json(new JsonObject()
                .put("status", "UP")
                .put("service", "pulse-backend")
                .put("timestamp", Instant.now().toString())));

        router.get(API_PREFIX + "/me").handler(ctx -> ctx.json(currentUser()));

        ModuleContext moduleContext = new ModuleContext(vertx, config(), Db.pool(vertx, config()));
        for (PulseModule module : registry.modules()) {
            Router sub = Router.router(vertx);
            module.routes(sub, moduleContext);
            router.route(API_PREFIX + "/" + module.id() + "/*").subRouter(sub);
            log.info("Mounted module '{}' at {}/{}", module.id(), API_PREFIX, module.id());
        }
        return router;
    }

    /**
     * TODO(auth, group 3): replace with the session user, their role and its granted permissions.
     * Until auth exists, a development Admin holding every registered permission is returned.
     */
    private JsonObject currentUser() {
        Set<String> granted = registry.permissions();
        return new JsonObject()
                .put("user", new JsonObject().put("name", "Developer").put("email", "dev@pulse.local"))
                .put("role", "Admin")
                .put("permissions", new JsonArray(granted.stream().toList()))
                .put("menu", registry.menuFor(granted));
    }
}
