package com.pulse.modules.culture;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

/**
 * Culture values (Track 3, task group 19). Migration V370 (Discipline overrides). The values themselves
 * come from {@code core.culture} / {@code core.cultureData} (task 4.4) with the people-measured inputs of
 * {@code calc/people} ({@code people.cultureInputs}, {@code people.disciplineOverride}); the endpoints come
 * with task 19.1.
 */
public class CultureModule implements PulseModule
{
    @Override
    public String id()
    {
        return "culture";
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
    }
}
