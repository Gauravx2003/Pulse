package com.pulse.modules.reviews;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

import java.util.List;

/**
 * Reviews (Track 3, task group 20). Task 20.2: 1:1s with shared notes and action items, and kudos.
 * Migrations V330+, seed S330+. Uses the built-in {@code oneonone} permission.
 */
public class ReviewsModule implements PulseModule
{
    @Override
    public String id()
    {
        return "reviews";
    }

    @Override
    public List<String> auditEvents()
    {
        return List.of(OneOnOneService.CREATED, OneOnOneService.NOTES_UPDATED, OneOnOneService.ACTION_SAVED,
                KudosService.GIVEN);
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new ReviewsRoutes(ctx).mount(router);
    }
}
