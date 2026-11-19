package com.pulse.platform.module;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

/** Contributes items to the Action Center for the current user. */
public interface ActionProvider {

    /** Unique key, e.g. {@code "okrs.checkin-due"}. */
    String key();

    /** Action items for the given user (the {@code GET /me} user object). */
    Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx);
}
