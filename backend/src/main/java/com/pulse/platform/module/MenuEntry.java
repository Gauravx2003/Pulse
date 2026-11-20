package com.pulse.platform.module;

import io.vertx.core.json.JsonObject;

/**
 * One navigation item contributed by a module.
 *
 * @param key        unique key, e.g. {@code "okrs.mine"}
 * @param label      text shown in the menu
 * @param path       SPA route path; must match a route the frontend module registers
 * @param group      menu group, e.g. {@code "My work"}, {@code "Manager"}, {@code "Administration"}
 * @param permission permission required to see the entry, or {@code null} for every signed-in user
 * @param order      sort order inside the group (ascending)
 */
public record MenuEntry(String key, String label, String path, String group, String permission, int order) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("key", key)
                .put("label", label)
                .put("path", path)
                .put("group", group)
                .put("order", order);
    }
}
