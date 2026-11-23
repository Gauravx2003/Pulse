package com.pulse.modules.training;

import com.pulse.platform.module.BadgeProvider;
import com.pulse.platform.module.ModuleContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * The Training menu badge (spec platform/notifications "Menu badges": training actions): the number
 * of items in the caller's Pending-for-you list, read with the same rules as that list, so
 * "Training 3" means three items there.
 */
public final class TrainingBadge implements BadgeProvider
{
    @Override
    public String screen()
    {
        return "training";
    }

    @Override
    public Future<Integer> countFor(JsonObject user, ModuleContext ctx)
    {
        return new MentoringService(ctx, new AssignmentService(ctx)).pending(user)
                .map(pending -> pending.getJsonArray("items").size());
    }
}
