package com.pulse.modules.rules;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

/**
 * Rules &amp; thresholds (Track 1, task 5.2): the Admin-editable thresholds, weights and points behind
 * every score, with validation, versioning, history and reset. The rule catalogue and its defaults live
 * in {@code calc/core/RuleCatalogue}; this module has no tables of its own ({@code rules} and
 * {@code rule_versions} are platform tables from V100) and uses the platform audit events
 * {@code rules.changed} and {@code rules.reset}.
 */
public class RulesModule implements PulseModule
{
    @Override
    public String id()
    {
        return "rules";
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new RulesRoutes(ctx).mount(router);
    }
}
