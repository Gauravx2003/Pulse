package com.pulse.platform.config;

import com.pulse.Main;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 2.4: profile, PULSE_DEMO_SEED and the startup guard. */
class RuntimeProfileTest {

    private static RuntimeProfile of(String profile, Object demo) {
        JsonObject c = new JsonObject();
        if (profile != null) c.put("profile", profile);
        if (demo != null) c.put("demoSeed", demo);
        return RuntimeProfile.from(c);
    }

    @Test
    void defaultsToDevelopmentWithDemoSeedOff() {
        RuntimeProfile p = of(null, null);
        // The environment of a developer or CI may set these; only assert when it does not.
        if (System.getenv("PULSE_PROFILE") == null) assertEquals(RuntimeProfile.DEVELOPMENT, p.profile());
        if (System.getenv("PULSE_DEMO_SEED") == null) assertFalse(p.demoSeed());
    }

    @Test
    void parsesFlagValues() {
        assertTrue(of("development", "true").demoSeed());
        assertTrue(of("development", "1").demoSeed());
        assertTrue(of("development", "YES").demoSeed());
        assertFalse(of("development", "false").demoSeed());
        assertFalse(of("development", "off").demoSeed());
        assertTrue(of("prod", "false").production());
        assertThrows(IllegalStateException.class, () -> of("development", "maybe"));
        assertThrows(IllegalStateException.class, () -> of("staging", "false"));
    }

    @Test
    void productionWithDemoSeedIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> of("production", "true").guard());
        assertTrue(e.getMessage().contains("PULSE_DEMO_SEED"), e.getMessage());
    }

    @Test
    void otherCombinationsPassTheGuard() {
        of("production", "false").guard();
        of("development", "true").guard();
        of("development", "false").guard();
    }

    @Test
    void startupFailsUnderProductionWithDemoSeed() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            ExecutionException e = assertThrows(ExecutionException.class, () -> Main.start(vertx,
                            new JsonObject().put("profile", "production").put("demoSeed", "true").put("http.port", 0))
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(e.getCause().getMessage().contains("PULSE_DEMO_SEED"), e.getCause().getMessage());
            assertTrue(vertx.deploymentIDs().isEmpty(), "nothing may be deployed");
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
