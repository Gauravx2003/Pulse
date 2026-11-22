package com.pulse.platform.ingest;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Turns extracted seed JSON ({@code seed/extracted/*.json}) into normalized records of one ingest table,
 * for {@link SeedConnector}. The track that owns a table writes its mapper and lists it in its module
 * descriptor ({@link com.pulse.platform.module.PulseModule#seedMappers()}), so seeding an ingest table
 * never needs a direct insert or an edit of platform code.
 *
 * <pre>{@code
 * public final class IssueSeedMapper implements SeedMapper
 * {
 *     public String tool() { return "jira"; }
 *     public String entity() { return "issues"; }
 *     public int order() { return 210; }                     // after people and sprints
 *
 *     public JsonArray map(SeedFiles files)
 *     {
 *         JsonArray out = new JsonArray();
 *         files.array("issues").forEach(o -> {
 *             JsonObject i = (JsonObject) o;
 *             out.add(new JsonObject()
 *                     .put("external_id", i.getString("key"))
 *                     .put("summary", i.getString("summary"))
 *                     .put("assignee_id", SeedMapper.ref("people", i.getString("person"))));
 *         });
 *         return out;
 *     }
 * }
 * }</pre>
 */
public interface SeedMapper
{
    /** Integrations tile the data belongs to, e.g. {@code "jira"}; Sync now of that tile re-reads it. */
    String tool();

    /** Ingest table the records go to. */
    String entity();

    /**
     * Load order: lower first. Platform tables use 0–99; a table that references another ingest table
     * through {@code $ref} must come after it. Ties keep module discovery order.
     */
    default int order()
    {
        return 100;
    }

    /** Normalized records (see {@link ConnectorPort}); runs on a worker thread, may read files. */
    JsonArray map(SeedFiles files);

    /** A {@code $ref} to the seed row of {@code entity} with that external id. */
    static JsonObject ref(String entity, String externalId)
    {
        return externalId == null ? null : new JsonObject()
                .put(IngestService.REF, entity)
                .put("external_id", externalId);
    }
}
