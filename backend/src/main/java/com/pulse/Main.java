package com.pulse;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Starting Pulse Vert.x application...");

        Vertx vertx = Vertx.vertx();

        vertx.deployVerticle(new HttpServerVerticle(), res -> {
            if (res.succeeded()) {
                log.info("Pulse HttpServerVerticle deployed successfully! Deployment ID: {}", res.result());
            } else {
                log.error("Failed to deploy Pulse HttpServerVerticle", res.cause());
                System.exit(1);
            }
        });
    }
}
