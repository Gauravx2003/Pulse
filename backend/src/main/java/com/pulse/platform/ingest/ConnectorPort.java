package com.pulse.platform.ingest;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * The seam between Pulse and an external tool (design.md decision 6, task 5.4). Every source of tool
 * data, the demo seed included, is a connector: it only reads and normalizes, and
 * {@link IngestService} is the single write path into the tables that carry the ingest columns
 * {@code source}, {@code external_id} and {@code synced_at}. Phase 1 has one implementation,
 * {@link SeedConnector}; the live GitHub and Jira connectors of group 24 implement the same port, so no
 * calculation or screen changes when they arrive.
 *
 * <p>{@link #fetchChanges} resolves to a normalized batch, a plain {@link JsonObject}:
 *
 * <pre>{@code
 * {
 *   "source":   "seed",                       // written to the source column of every row
 *   "cursor":   "2026-09-28T10:42:00Z",       // optional; the next fetchChanges(since) starts here
 *   "entities": [                             // applied in this order (parents before children)
 *     { "entity": "sprints",                  // ingest table
 *       "records": [
 *         { "external_id": "S43",             // required: the tool's id, unique per source
 *           "key": "S43", "starts_on": "2026-09-21",
 *           "assignee_id": { "$ref": "people", "external_id": "ps" } }   // id of another ingest row
 *       ] }
 *   ]
 * }
 * }</pre>
 *
 * Record fields are column names of the entity's table; values are JSON scalars, objects or arrays and
 * are cast to the column type by {@link IngestService}. A {@code $ref} value resolves to the {@code id}
 * of the row of that table with the same source (or the {@code source} given in the reference) and
 * that external id, or to null when there is none.
 *
 * <p>A connector never writes, never deletes and never calls a tool except with read-only requests.
 */
public interface ConnectorPort
{
    /** Integrations tile this connector feeds, e.g. {@code "jira"}, {@code "gh"}, {@code "hr"}. */
    String tool();

    /** Value of the {@code source} column for the rows this connector produces, e.g. {@code "seed"}. */
    String source();

    /** True for a connector that calls the tool; seeded tools answer false and make no network call. */
    boolean live();

    /**
     * The records changed since {@code since} (an ISO-8601 cursor from an earlier batch, or null for a
     * full read), as a normalized batch described on this interface.
     */
    Future<JsonObject> fetchChanges(String since);
}
