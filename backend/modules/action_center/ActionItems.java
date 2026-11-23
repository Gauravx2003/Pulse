package com.pulse.modules.action_center;

import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * The shape of an Action center item (task 13.1, spec insight/action-center "Do today list"), shared by
 * every {@link com.pulse.platform.module.ActionProvider}: {@code {key, priority, source, personId,
 * personName, text, button, target}}, where {@code priority} is High, Medium or Low, {@code personName}
 * is a person or "Team", and {@code target} names the screen, tab and person the one action opens
 * ({@code {module, tab?, personId?, id?}}).
 */
public final class ActionItems
{

    public static final String HIGH = "High";

    public static final String MEDIUM = "Medium";

    public static final String LOW = "Low";

    public static final List<String> PRIORITIES = List.of(HIGH, MEDIUM, LOW);

    /**
     * What the list reads when nothing needs the user.
     */
    public static final String EMPTY = "Nothing needs you right now.";

    public static final String TEAM = "Team";

    private ActionItems()
    {
    }

    /**
     * One item; {@code personId} null for a team item.
     */
    public static JsonObject item(String key, String priority, String source, String personId, String personName,
                                  String text, String button, JsonObject target)
    {
        if (!PRIORITIES.contains(priority))
        {
            throw new IllegalArgumentException("priority: " + priority);
        }

        return new JsonObject()
                .put("key", key)
                .put("priority", priority)
                .put("source", source)
                .put("personId", personId)
                .put("personName", personName == null ? TEAM : personName)
                .put("text", text)
                .put("button", button)
                .put("target", target == null ? new JsonObject() : target);
    }

    /**
     * {@code {module, tab?, personId?}}.
     */
    public static JsonObject target(String module, String tab, String personId)
    {
        JsonObject out = new JsonObject().put("module", module);

        if (tab != null)
        {
            out.put("tab", tab);
        }

        if (personId != null)
        {
            out.put("personId", personId);
        }

        return out;
    }

    /**
     * 0 for High, 1 for Medium, 2 for Low, 3 for anything else.
     */
    public static int rank(String priority)
    {
        int i = PRIORITIES.indexOf(priority);

        return i < 0 ? PRIORITIES.size() : i;
    }
}
