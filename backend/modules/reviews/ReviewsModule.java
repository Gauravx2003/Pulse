package com.pulse.modules.reviews;

import com.pulse.platform.module.BadgeProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

import java.util.List;

/**
 * Reviews (Track 3, task group 20). Task 20.2: 1:1s with shared notes and action items, and kudos.
 * Task 20.3: 360° cycles, assignment and the review form (V340, S340).
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
                KudosService.GIVEN, Review360Service.CYCLE_CREATED, Review360Service.REQUEST_REMOVED,
                Review360Service.REQUEST_DECLINED, Review360Service.REVIEW_SUBMITTED);
    }

    @Override
    public List<BadgeProvider> badges()
    {
        return List.of(new ReviewsBadge());
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new ReviewsRoutes(ctx).mount(router);
    }
}
