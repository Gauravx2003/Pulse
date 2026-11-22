package com.pulse.modules.integrations;

import com.pulse.platform.ingest.SeedFiles;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The connector catalogue (spec admin/integrations-connectors "Connector catalogue"): every tile with
 * its name, the data it reads, the modules it feeds, its auth methods with their form fields, the
 * minimum scopes Pulse needs and the credential rotation period. Built from the extracted prototype
 * data {@code integrations.json} and {@code integration_auth_methods.json}.
 *
 * <p>A tool is <em>seeded</em> when the prototype ships it connected; only seeded tools can be connected
 * in Phase 1, the others show "Not connected" with Connect disabled as planned.
 */
final class IntegrationCatalogue
{
    /** Form field types whose values are secrets: stored only as a masked placeholder. */
    static final List<String> SECRET_TYPES = List.of("secret", "textarea");

    private final Map<String, JsonObject> tools;

    private IntegrationCatalogue(Map<String, JsonObject> tools)
    {
        this.tools = tools;
    }

    /** Reads the catalogue; blocking, call on a worker thread. */
    static IntegrationCatalogue load(SeedFiles files)
    {
        Map<String, JsonObject> auth = new LinkedHashMap<>();

        files.array("integration_auth_methods").forEach(o -> auth.put(((JsonObject) o).getString("tool"), (JsonObject) o));

        Map<String, JsonObject> tools = new LinkedHashMap<>();

        for (Object o : files.array("integrations"))
        {
            JsonObject t = (JsonObject) o;
            String id = t.getString("id");
            JsonObject a = auth.getOrDefault(id, new JsonObject());

            tools.put(id, new JsonObject()
                    .put("id", id)
                    .put("mark", t.getString("mk"))
                    .put("name", t.getString("name"))
                    .put("reads", t.getString("desc"))
                    .put("feeds", t.getString("feeds"))
                    .put("seeded", !t.getString("state", "").isBlank())
                    .put("methods", methods(a.getJsonObject("methods", new JsonObject())))
                    .put("scopes", a.getJsonArray("scopes", new JsonArray()))
                    .put("rotateDays", a.getInteger("rotateDays")));
        }

        return new IntegrationCatalogue(tools);
    }

    /** Prototype field tuples {@code [key, label, type, placeholder, required, hint]} as objects. */
    private static JsonArray methods(JsonObject methods)
    {
        JsonArray out = new JsonArray();

        for (String name : methods.fieldNames())
        {
            JsonArray fields = new JsonArray();

            for (Object f : methods.getJsonArray(name))
            {
                JsonArray a = (JsonArray) f;

                fields.add(new JsonObject()
                        .put("key", a.getString(0))
                        .put("label", a.getString(1))
                        .put("type", a.getString(2))
                        .put("placeholder", a.getString(3))
                        .put("required", a.getValue(4) != null && ((Number) a.getValue(4)).intValue() == 1)
                        .put("hint", a.size() > 5 ? a.getString(5) : ""));
            }

            out.add(new JsonObject().put("name", name).put("fields", fields));
        }

        return out;
    }

    /** Tiles in catalogue order. */
    List<JsonObject> all()
    {
        return List.copyOf(tools.values());
    }

    /** One tile, or null for an unknown id. */
    JsonObject tool(String id)
    {
        return tools.get(id);
    }

    /** The form fields of {@code method} for {@code tool}, or null when the tool has no such method. */
    JsonArray fields(String tool, String method)
    {
        JsonObject t = tools.get(tool);

        if (t == null)
        {
            return null;
        }

        for (Object m : t.getJsonArray("methods"))
        {
            JsonObject o = (JsonObject) m;

            if (o.getString("name").equals(method))
            {
                return o.getJsonArray("fields");
            }
        }

        return null;
    }
}
