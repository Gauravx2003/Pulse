package com.pulse.modules.sample;

import com.pulse.platform.module.MenuEntry;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import java.util.List;

/**
 * Reference module proving self-registration (task 1.2). Copy this shape for real
 * modules; delete it once the first real module lands.
 */
public class SampleModule implements PulseModule {

    @Override
    public String id() {
        return "sample";
    }

    @Override
    public List<String> permissions() {
        return List.of("sample.view");
    }

    @Override
    public List<MenuEntry> menu() {
        return List.of(new MenuEntry("sample.home", "Sample module", "/sample", "Developer", "sample.view", 100));
    }

    @Override
    public void routes(Router router, ModuleContext ctx) {
        router.get("/ping").handler(rc -> rc.json(new JsonObject().put("module", id()).put("pong", true)));
    }
}
