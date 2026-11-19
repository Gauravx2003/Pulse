package com.pulse;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(VertxExtension.class)
public class HealthTest {

    private int port = 8081;

    @BeforeEach
    void deploy(Vertx vertx, VertxTestContext testContext) {
        DeploymentOptions options = new DeploymentOptions()
                .setConfig(new JsonObject().put("http.port", port));

        vertx.deployVerticle(new HttpServerVerticle(), options, testContext.succeedingThenComplete());
    }

    @Test
    @DisplayName("GET /health should return 200 OK with status UP")
    void testHealthEndpoint(Vertx vertx, VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);

        client.get(port, "localhost", "/health")
                .send(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body);
                    assertEquals("UP", body.getString("status"));
                    assertEquals("pulse-backend", body.getString("service"));
                    testContext.completeNow();
                })));
    }
}
