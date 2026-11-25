package com.pulse.platform.live;

import com.pulse.modules.probe.ProbeModule;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.ModuleRegistry;
import com.pulse.platform.module.PulseModule;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 24.3: connector registration through a module descriptor, and the shared base URL rules. */
class LiveRegistryAndBaseUrlTest
{
    // ------------------------------------------------------------------ registry

    @Test
    void stubConnectorRegisteredFromATestModuleIsListed()
    {
        ModuleRegistry modules = ModuleRegistry.of(List.of(new ProbeModule()));

        ModuleContext ctx = new ModuleContext(null, new JsonObject(), null, null, null, modules);

        for (PulseModule module : modules.modules())
        {
            module.start(ctx);
        }

        assertEquals(List.of(StubLiveConnector.TOOL), ctx.connectors().tools());
        assertTrue(ctx.connectors().get(StubLiveConnector.TOOL).live());

        JsonObject listed = ctx.connectors().list().getJsonObject(0);

        assertEquals("stub", listed.getString("tool"));
        assertEquals("stub", listed.getString("source"));
        assertEquals("baseUrl", listed.getJsonObject("configSchema").getJsonArray("fields").getJsonObject(0)
                .getString("name"));
        assertEquals(new JsonArray().add("read"), listed.getJsonArray("requiredScopes"));

        assertNull(ctx.connectors().get("jira"), "a tool without a live connector stays seeded");
    }

    @Test
    void aToolCanBeRegisteredOnlyOnce()
    {
        LiveConnectorRegistry registry = new LiveConnectorRegistry().register(new StubLiveConnector());

        assertThrows(IllegalStateException.class, () -> registry.register(new StubLiveConnector()));

        assertThrows(IllegalArgumentException.class, () -> registry.register(new StubLiveConnector()
        {
            @Override
            public String tool()
            {
                return "Bad Tool";
            }
        }));

        assertThrows(IllegalArgumentException.class, () -> registry.register(new StubLiveConnector()
        {
            @Override
            public String tool()
            {
                return "other";
            }

            @Override
            public String source()
            {
                return "seed";
            }
        }));
    }

    @Test
    void phase1FetchChangesNeedsAContext()
    {
        assertTrue(new StubLiveConnector().fetchChanges(null).failed());
    }

    // ------------------------------------------------------------------ base URLs

    @Test
    void lanHttpBaseUrlIsAcceptedWithAWarning()
    {
        JsonObject v = BaseUrls.validate("http://172.16.10.6:8080/", false);

        assertTrue(v.getBoolean("valid"), v.encode());
        assertEquals("http://172.16.10.6:8080", v.getString("url"));
        assertEquals(new JsonObject().put("scheme", "http").put("host", "172.16.10.6").put("port", 8080)
                .put("pathPrefix", ""), v.getJsonObject("origin"));
        assertEquals(BaseUrls.INSECURE_HTTP, v.getJsonArray("warnings").getJsonObject(0).getString("code"));
    }

    @Test
    void httpsWithContextPathIsNormalized()
    {
        JsonObject v = BaseUrls.validate("HTTPS://Jira.Example.com:443/jira//", false);

        // a doubled trailing slash is still just a trailing slash
        assertTrue(v.getBoolean("valid"), v.encode());
        assertEquals("https://jira.example.com/jira", v.getString("url"));
        assertEquals("/jira", v.getJsonObject("origin").getString("pathPrefix"));
        assertEquals(443, v.getJsonObject("origin").getInteger("port"));
        assertTrue(v.getJsonArray("warnings").isEmpty());
    }

    @Test
    void badBaseUrlsAreRejected()
    {
        for (String url : new String[] {
                "ftp://jira.example.com",
                "http://user:pass@jira.example.com",
                "http://jira.example.com/?a=1",
                "http://jira.example.com/#top",
                "http://jira.example.com/a/../b",
                "http://jira.example.com/a/%2e%2e/b",
                "jira.example.com",
                "http://jira example.com",
                "",
                null,
                "http://169.254.169.254",
                "http://169.254.169.254/latest/meta-data",
                "http://0.0.0.0:8080",
                "http://[fe80::1]/",
                "http://100.100.100.200/",
                "http://127.0.0.1:8080",
                "http://localhost:8080",
                "http://[::1]:8080",
                "http://999.1.1.1/"})
        {
            JsonObject v = BaseUrls.validate(url, false);

            assertFalse(v.getBoolean("valid"), url);
            assertTrue(v.getString("message").length() > 0, url);
        }
    }

    @Test
    void loopbackOnlyWithTheTestFlag()
    {
        assertTrue(BaseUrls.validate("http://127.0.0.1:8080", true).getBoolean("valid"));
        assertTrue(BaseUrls.validate("http://localhost:8080", true).getBoolean("valid"));
        assertFalse(BaseUrls.validate("http://169.254.169.254", true).getBoolean("valid"),
                "metadata stays refused with the flag");
    }
}
