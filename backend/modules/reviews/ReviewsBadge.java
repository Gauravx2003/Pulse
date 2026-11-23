package com.pulse.modules.reviews;

import com.pulse.platform.module.BadgeProvider;
import com.pulse.platform.module.ModuleContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.UUID;

/**
 * The Reviews menu badge (spec platform/notifications "Menu badges": 360° reviews waiting): the
 * caller's To give requests still open (not started or in progress), 360° and training checkpoints,
 * read with the same query as the To give list, so "Reviews 2" means two open items there.
 */
public final class ReviewsBadge implements BadgeProvider
{
    @Override
    public String screen()
    {
        return "reviews";
    }

    @Override
    public Future<Integer> countFor(JsonObject user, ModuleContext ctx)
    {
        Review360Repository repo = new Review360Repository(ctx.sql());

        return repo.keysOf(UUID.fromString(user.getString("id"))).compose(repo::toGive).map(items ->
        {
            int open = 0;

            for (Object item : items)
            {
                String status = ((JsonObject) item).getString("status");

                open += "not_started".equals(status) || "in_progress".equals(status) ? 1 : 0;
            }

            return open;
        });
    }
}
