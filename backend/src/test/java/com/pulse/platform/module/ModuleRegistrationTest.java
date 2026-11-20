package com.pulse.platform.module;

import com.pulse.HttpServerVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class ModuleRegistrationTest {

    private static final int PORT = 8082;

    @BeforeEach
    void deploy(Vertx vertx, VertxTestContext ctx) {
        vertx.deployVerticle(new HttpServerVerticle(),
                new DeploymentOptions().setConfig(new JsonObject().put("http.port", PORT)),
                ctx.succeedingThenComplete());
    }

    @Test
    void scanDiscoversSampleModule() {
        ModuleRegistry registry = ModuleRegistry.discover();
        assertTrue(registry.modules().stream().anyMatch(m -> m.id().equals("sample")));
        assertTrue(registry.permissions().contains("sample.view"));
    }

    @Test
    void menuIsFilteredByPermission() {
        ModuleRegistry registry = ModuleRegistry.discover();
        assertTrue(registry.menuFor(Set.of()).stream()
                .map(JsonObject.class::cast)
                .noneMatch(g -> g.getString("group").equals("Developer")));
        assertEquals(1, registry.menuFor(Set.of("sample.view")).stream()
                .map(JsonObject.class::cast)
                .filter(g -> g.getString("group").equals("Developer"))
                .count());
    }

    @Test
    void duplicateModuleIdsAreRejected() {
        PulseModule a = () -> "dup";
        PulseModule b = () -> "dup";
        assertThrows(IllegalStateException.class, () -> ModuleRegistry.of(List.of(a, b)));
    }

    @Test
    void sampleModuleIsRoutedAndInMe(Vertx vertx, VertxTestContext ctx) {
        WebClient client = WebClient.create(vertx);
        client.get(PORT, "localhost", "/api/v1/sample/ping").send()
                .compose(ping -> {
                    ctx.verify(() -> {
                        assertEquals(200, ping.statusCode());
                        assertEquals("sample", ping.bodyAsJsonObject().getString("module"));
                    });
                    return client.get(PORT, "localhost", "/api/v1/me").send();
                })
                .onComplete(ctx.succeeding(me -> ctx.verify(() -> {
                    assertEquals(200, me.statusCode());
                    JsonObject body = me.bodyAsJsonObject();
                    assertTrue(body.getJsonArray("permissions").contains("sample.view"));
                    JsonArray menu = body.getJsonArray("menu");
                    boolean hasSample = menu.stream().map(JsonObject.class::cast)
                            .flatMap(g -> g.getJsonArray("items").stream().map(JsonObject.class::cast))
                            .anyMatch(i -> i.getString("path").equals("/sample"));
                    assertTrue(hasSample, "menu should contain the sample entry: " + menu.encode());
                    ctx.completeNow();
                })));
    }
}
