package com.pulse.platform.notify;

import com.pulse.platform.http.ApiException;
import com.pulse.platform.http.Ids;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.BadgeProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.nav.Navigation;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The bell and the menu badges (task 5.3, spec platform/notifications). Every signed-in user reads only
 * their own notifications; no permission is needed.
 *
 * <pre>
 * GET  /api/v1/notifications[?limit=]      {items: [{id, kind, text, link, createdAt, read}], unread}
 * POST /api/v1/notifications/:id/read      {unread}; 404 when the notification is not the caller's
 * POST /api/v1/notifications/read-all      {unread: 0, marked}
 * GET  /api/v1/notifications/badges        {badges: {menuKey: count}} for the screens in the caller's menu
 * </pre>
 */
public final class NotificationRoutes
{
    /** Menu key of the Action center, whose badge defaults to its item count. */
    static final String ACTIONS = "actions";

    /** The Action center badge counts High-priority items only (prototype menu badge, {@code pri === 1}). */
    static final String HIGH = "High";

    private static final Logger log = LoggerFactory.getLogger(NotificationRoutes.class);

    private final ModuleContext ctx;

    private final Navigation navigation;

    private final List<BadgeProvider> badges;

    private final List<ActionProvider> actions;

    private NotificationRoutes(ModuleContext ctx, Navigation navigation)
    {
        this.ctx = ctx;
        this.navigation = navigation;
        this.badges = ctx.registry().badgeProviders();
        this.actions = ctx.registry().actionProviders();
    }

    public static void mount(Router router, String apiPrefix, ModuleContext ctx, Navigation navigation)
    {
        NotificationRoutes routes = new NotificationRoutes(ctx, navigation);
        String base = apiPrefix + "/notifications";

        router.get(base).handler(routes::list);
        router.get(base + "/badges").handler(routes::badges);
        router.post(base + "/read-all").handler(routes::readAll);
        router.post(base + "/:id/read").handler(routes::read);
    }

    private void list(RoutingContext rc)
    {
        Integer limit;

        try
        {
            String raw = rc.queryParams().get("limit");

            limit = raw == null ? null : Integer.valueOf(raw);
        }
        catch (NumberFormatException e)
        {
            rc.fail(400, ApiException.badRequest("invalid_parameter", "limit must be a number.").field("limit"));

            return;
        }

        ctx.notifications().list(PolicyService.user(rc), limit).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void read(RoutingContext rc)
    {
        UUID id = Ids.parse(rc.pathParam("id"));

        if (id == null)
        {
            rc.fail(404, PolicyService.notFound());

            return;
        }

        ctx.notifications().markRead(PolicyService.user(rc), id)
                .onSuccess(result ->
                {
                    if (result == null)
                    {
                        rc.fail(404, PolicyService.notFound());
                    }
                    else
                    {
                        rc.json(result);
                    }
                })
                .onFailure(rc::fail);
    }

    private void readAll(RoutingContext rc)
    {
        ctx.notifications().markAllRead(PolicyService.user(rc)).onSuccess(rc::json).onFailure(rc::fail);
    }

    /**
     * Sums the providers of each screen in the caller's menu. A provider that fails is logged and left
     * out (a screen whose providers all failed shows no badge), so one broken count never hides the others.
     */
    private void badges(RoutingContext rc)
    {
        JsonObject user = PolicyService.user(rc);

        Set<String> screens = menuKeys(user);

        Map<String, List<Future<Integer>>> counts = new LinkedHashMap<>();

        for (BadgeProvider provider : badges)
        {
            if (screens.contains(provider.screen()))
            {
                counts.computeIfAbsent(provider.screen(), s -> new ArrayList<>())
                        .add(safe(provider.screen(), provider.countFor(user, ctx)));
            }
        }

        if (screens.contains(ACTIONS) && !counts.containsKey(ACTIONS) && !actions.isEmpty())
        {
            List<Future<Integer>> parts = new ArrayList<>();

            for (ActionProvider provider : actions)
            {
                parts.add(safe(provider.key(), provider.actionsFor(user, ctx)
                        .map(items -> (int) items.stream().filter(i -> HIGH.equals(i.getString("priority"))).count())));
            }

            counts.put(ACTIONS, parts);
        }

        List<Future<Integer>> all = counts.values().stream().flatMap(List::stream).toList();

        Future.join(all).onComplete(done ->
        {
            JsonObject out = new JsonObject();

            counts.forEach((screen, parts) ->
            {
                if (parts.stream().allMatch(f -> f.result() == null))
                {
                    return;
                }

                int sum = parts.stream().mapToInt(f -> f.result() == null ? 0 : Math.max(0, f.result())).sum();

                out.put(screen, sum);
            });

            rc.json(new JsonObject().put("badges", out));
        });
    }

    private Set<String> menuKeys(JsonObject user)
    {
        Set<String> keys = new LinkedHashSet<>();

        JsonArray menu = navigation.menuFor(PolicyService.permissions(user), user.getString("personId") != null);

        menu.forEach(group -> ((JsonObject) group).getJsonArray("items")
                .forEach(item -> keys.add(((JsonObject) item).getString("key"))));

        return keys;
    }

    private static Future<Integer> safe(String name, Future<Integer> count)
    {
        return count.recover(e ->
        {
            log.warn("Badge count '{}' failed", name, e);

            return Future.succeededFuture(null);
        });
    }
}
