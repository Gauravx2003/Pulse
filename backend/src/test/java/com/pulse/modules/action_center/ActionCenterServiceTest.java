package com.pulse.modules.action_center;

import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 13.1: the Action center engine with stub providers: one list sorted High, Medium, Low; a failing
 * provider or a malformed item is left out and named, never emptying the page; the empty-list text.
 */
class ActionCenterServiceTest
{

    private static final JsonObject MANAGER = new JsonObject().put("id", "u-rn");

    @Test
    void itemsFromEveryProviderAreSortedHighMediumLow() throws Exception
    {
        ActionProvider track3 = stub("visits.verify", List.of(
                ActionItems.item(null, ActionItems.MEDIUM, "Customer visits", null, null, "2 visits to verify", "Verify", null),
                ActionItems.item(null, ActionItems.LOW, "Customer visits", "p1", "Dev Patel", "Visit report overdue", "Remind", null)));

        ActionProvider track2 = stub("work.overload", List.of(
                ActionItems.item("work.overload", ActionItems.HIGH, "Workload", "p2", "Priya Shah", "At 171% of capacity", "Rebalance", null)));

        JsonObject body = run(List.of(track3, track2));

        JsonArray items = body.getJsonArray("items");

        assertEquals(List.of("High", "Medium", "Low"), items.stream().map(i -> ((JsonObject) i).getString("priority")).toList());
        assertEquals("visits.verify", items.getJsonObject(1).getString("key"), "an item without a key gets its provider's");
        assertEquals(new JsonObject().put("High", 1).put("Medium", 1).put("Low", 1), body.getJsonObject("counts"));
        assertNull(body.getValue("empty"));
    }

    @Test
    void aFailingProviderOrAMalformedItemIsLeftOutAndNamed() throws Exception
    {
        ActionProvider broken = new ActionProvider()
        {
            @Override
            public String key()
            {
                return "broken";
            }

            @Override
            public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
            {
                throw new IllegalStateException("boom");
            }
        };

        ActionProvider failed = stub("failed", null);

        ActionProvider malformed = stub("malformed", List.of(new JsonObject().put("priority", "Urgent").put("text", "?")));

        ActionProvider fine = stub("fine", List.of(ActionItems.item("fine", ActionItems.LOW, "Effort", null, null, "1 day", "Open", null)));

        JsonObject body = run(List.of(broken, failed, malformed, fine));

        assertEquals(1, body.getJsonArray("items").size());
        assertEquals(new JsonArray().add("broken").add("failed").add("malformed"), body.getJsonArray("unavailable"));
    }

    @Test
    void anEmptyListSaysNothingNeedsYou() throws Exception
    {
        JsonObject body = run(List.of(stub("quiet", List.of())));

        assertTrue(body.getJsonArray("items").isEmpty());
        assertEquals("Nothing needs you right now.", body.getString("empty"));
    }

    private static JsonObject run(List<ActionProvider> providers) throws Exception
    {
        return new ActionCenterService(null, providers).items(MANAGER).toCompletionStage().toCompletableFuture().get();
    }

    /**
     * A provider answering {@code items}, or failing when {@code items} is null.
     */
    private static ActionProvider stub(String key, List<JsonObject> items)
    {
        return new ActionProvider()
        {
            @Override
            public String key()
            {
                return key;
            }

            @Override
            public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
            {
                return items == null ? Future.failedFuture(new IllegalStateException("down")) : Future.succeededFuture(items);
            }
        };
    }
}
