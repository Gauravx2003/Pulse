package com.pulse.platform.module;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * Contributes a count to a menu item's badge (spec platform/notifications "Menu badges", task 5.3): stale
 * work items, pending effort items, visits needing action, training actions, 360° reviews waiting. The
 * count must come from the same rules (and the same repository query) as the list it points to, so the
 * badge always matches what the screen shows.
 *
 * <p>Badges are served by {@code GET /api/v1/notifications/badges} for the screens in the caller's menu
 * only. Several providers for one screen add up. The Action center badge defaults to the number of
 * High-priority Action Center items ({@link ActionProvider}) for the caller, as in the prototype; a
 * provider declared for {@code "actions"} replaces that default.
 */
public interface BadgeProvider
{
    /** Menu key of the screen whose item shows the count ({@link com.pulse.platform.nav.Navigation#SCREENS}). */
    String screen();

    /** The count for the signed-in user ({@code {id, role, personId, permissions}}); never negative. */
    Future<Integer> countFor(JsonObject user, ModuleContext ctx);
}
