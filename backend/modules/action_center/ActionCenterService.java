package com.pulse.modules.action_center;

import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Action center engine (task 13.1, spec insight/action-center "Do today list"): asks every
 * {@link ActionProvider} the modules registered through their descriptors for the caller's items, puts
 * them in one list sorted High, Medium, Low (then source, person and text) and counts them. A provider
 * that fails or returns an item without a priority and text is left out and named in
 * {@code unavailable}, so one broken module never empties the page. Providers decide visibility
 * themselves (a Manager's reports, everyone for {@code all.view}).
 */
public final class ActionCenterService
{

    private static final Logger log = LoggerFactory.getLogger(ActionCenterService.class);

    private final ModuleContext ctx;

    private final List<ActionProvider> providers;

    public ActionCenterService(ModuleContext ctx, List<ActionProvider> providers)
    {
        this.ctx = ctx;

        this.providers = List.copyOf(providers);
    }

    /**
     * {@code {items, counts: {High, Medium, Low}, empty, unavailable}}; {@code empty} is the empty-list
     * text when there are no items, otherwise null.
     */
    public Future<JsonObject> items(JsonObject user)
    {
        List<Future<List<JsonObject>>> parts = new ArrayList<>();

        for (ActionProvider provider : providers)
        {
            Future<List<JsonObject>> part;

            try
            {
                part = provider.actionsFor(user, ctx);
            }
            catch (RuntimeException e)
            {
                part = Future.failedFuture(e);
            }

            parts.add(part == null ? Future.succeededFuture(List.of()) : part);
        }

        return Future.join(parts).transform(done ->
        {
            List<JsonObject> items = new ArrayList<>();

            JsonArray unavailable = new JsonArray();

            for (int i = 0; i < providers.size(); i++)
            {
                String key = providers.get(i).key();

                Future<List<JsonObject>> part = parts.get(i);

                if (part.failed())
                {
                    log.warn("Action provider {} failed: {}", key, part.cause().toString());

                    unavailable.add(key);

                    continue;
                }

                for (JsonObject item : part.result() == null ? List.<JsonObject>of() : part.result())
                {
                    if (item == null || !ActionItems.PRIORITIES.contains(item.getString("priority")) || item.getString("text") == null)
                    {
                        if (!unavailable.contains(key))
                        {
                            unavailable.add(key);
                        }

                        continue;
                    }

                    items.add(item.getString("key") == null ? item.copy().put("key", key) : item);
                }
            }

            items.sort(Comparator.comparingInt((JsonObject item) -> ActionItems.rank(item.getString("priority")))
                    .thenComparing(item -> item.getString("source", ""))
                    .thenComparing(item -> item.getString("personName", ""))
                    .thenComparing(item -> item.getString("text", "")));

            JsonObject counts = new JsonObject();

            ActionItems.PRIORITIES.forEach(p -> counts.put(p, (int) items.stream().filter(i -> p.equals(i.getString("priority"))).count()));

            return Future.succeededFuture(new JsonObject()
                    .put("items", new JsonArray(new ArrayList<>(items)))
                    .put("counts", counts)
                    .put("empty", items.isEmpty() ? ActionItems.EMPTY : null)
                    .put("unavailable", unavailable));
        });
    }
}
