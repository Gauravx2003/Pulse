package com.pulse.modules.imports;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

/**
 * CSV engine and Data imports (Track 3, task group 16).
 *
 * <p>Migrations: {@code modules/imports/migrations} (V300: import_checks, import_log,
 * import_before_images). Routes: the shared check / commit / undo API in {@link ImportsRoutes}.
 * Import types join through {@code com.pulse.modules.imports.type.ImportTypeRegistry}; the audit
 * events it records ({@code import.committed}, {@code import.undone}, {@code data.export}) are already
 * in the platform catalogue, and it adds no permissions of its own.
 */
public class ImportsModule implements PulseModule
{
    @Override
    public String id()
    {
        return "imports";
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new ImportsRoutes(ctx).mount(router);
    }
}
