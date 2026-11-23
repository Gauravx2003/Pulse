package com.pulse.modules.appraisal;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

import java.util.List;

/**
 * Appraisal & increments (Track 3, task group 22). Migration V360 (cycles and proposals); scores come from
 * {@code core.appraisal} and are frozen in {@code calc_snapshots} at submission. Uses the built-in
 * {@code appraisal.manage} / {@code appraisal.approve} permissions, salary audit events and the
 * {@code appraisal.increment.*} separation-of-duties actions.
 */
public class AppraisalModule implements PulseModule
{
    @Override
    public String id()
    {
        return "appraisal";
    }

    @Override
    public List<String> auditEvents()
    {
        return List.of(AppraisalService.SUBMITTED_EVENT, AppraisalService.CYCLE_UPDATED);
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new AppraisalRoutes(ctx).mount(router);
    }
}
