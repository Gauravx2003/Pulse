package com.pulse;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

public class MainVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);

    @Override
    public void start(Promise<Void> startPromise) {
        int defaultPort = 8088;
        if (System.getenv("PORT") != null) {
            defaultPort = Integer.parseInt(System.getenv("PORT"));
        } else if (System.getenv("HTTP_PORT") != null) {
            defaultPort = Integer.parseInt(System.getenv("HTTP_PORT"));
        }

        int port = config().getInteger("http.port", Integer.getInteger("http.port", defaultPort));

        Router router = Router.router(vertx);
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());

        // GET /health
        router.get("/health").handler(ctx -> {
            JsonObject response = new JsonObject()
                    .put("status", "UP")
                    .put("service", "pulse-backend")
                    .put("timestamp", Instant.now().toString());

            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(response.encode());
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port, http -> {
                    if (http.succeeded()) {
                        log.info("Pulse Vert.x HTTP server started on port {}", port);
                        startPromise.complete();
                    } else {
                        log.error("Failed to start HTTP server", http.cause());
                        startPromise.fail(http.cause());
                    }
                });
    }
}
