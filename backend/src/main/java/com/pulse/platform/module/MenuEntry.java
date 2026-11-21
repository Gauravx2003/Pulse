package com.pulse.platform.module;

import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Set;

/**
 * One navigation item contributed by a module (or by the platform screen map, see
 * {@link com.pulse.platform.nav.Navigation}).
 *
 * @param key    unique key, e.g. {@code "okrs.mine"}
 * @param label  text shown in the menu
 * @param path   SPA route path; must match a route the frontend module registers
 * @param group  menu group, e.g. {@code "My work"}, {@code "Manager"}, {@code "Administration"}
 * @param anyOf  the entry is shown when the user holds at least one of these permissions; empty
 *               means every signed-in user
 * @param order  sort order inside the group (ascending)
 */
public record MenuEntry(String key, String label, String path, String group, List<String> anyOf, int order)
{
    public MenuEntry
    {
        anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
    }

    /** Entry guarded by one permission, or by none when {@code permission} is null. */
    public MenuEntry(String key, String label, String path, String group, String permission, int order)
    {
        this(key, label, path, group, permission == null ? List.of() : List.of(permission), order);
    }

    /** True when a user holding {@code granted} may see this entry. */
    public boolean visibleTo(Set<String> granted)
    {
        return anyOf.isEmpty() || anyOf.stream().anyMatch(granted::contains);
    }

    public JsonObject toJson()
    {
        return new JsonObject()
                .put("key", key)
                .put("label", label)
                .put("path", path)
                .put("group", group)
                .put("order", order);
    }
}
