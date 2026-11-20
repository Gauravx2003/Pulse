package com.pulse.modules.imports;

import com.pulse.platform.module.PulseModule;

/**
 * CSV engine and Data imports (Track 3, task group 16).
 *
 * <p>Task 16.1 registers the module so the migration runner picks up
 * {@code modules/imports/migrations} (V300: import_checks, import_log, import_before_images).
 * Routes, permissions, menu entries and the import-type registry arrive with tasks 16.2 to 16.4.
 */
public class ImportsModule implements PulseModule
{
    @Override
    public String id()
    {
        return "imports";
    }
}
