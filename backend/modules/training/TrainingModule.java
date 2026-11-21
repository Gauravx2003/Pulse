package com.pulse.modules.training;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

import java.util.List;

/**
 * Training and mentoring (Track 3, task group 21). Migrations V350+, seed S350+. Uses the built-in
 * {@code training.manage} permission. Part A: the plan library; assignments, the lifecycle, check-ins,
 * reviews, sign-off and mentoring follow.
 */
public class TrainingModule implements PulseModule
{
    @Override
    public String id()
    {
        return "training";
    }

    @Override
    public List<String> auditEvents()
    {
        return List.of(PlanService.CREATED, PlanService.UPDATED, PlanService.DUPLICATED, PlanService.DELETED);
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new TrainingRoutes(ctx).mount(router);
    }
}
