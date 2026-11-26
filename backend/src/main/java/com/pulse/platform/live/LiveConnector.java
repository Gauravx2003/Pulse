package com.pulse.platform.live;

import com.pulse.platform.ingest.ConnectorPort;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.function.Function;

/**
 * The plug-in contract of a live, read-only connector (task 24.3, change pulse-v2-live-connectors
 * design.md decision 1). A connector is a plain class in {@code backend/modules/connectors/<tool>/},
 * registered by the connectors module descriptor with {@code ctx.connectors().register(...)} from
 * {@link com.pulse.platform.module.PulseModule#start}. Platform code never imports a connector.
 *
 * <p>Rules:
 * <ul>
 *   <li>All inputs and outputs are {@link JsonObject} / {@link JsonArray}; no model classes.</li>
 *   <li>The network is reached only through {@link LiveContext#http()} (GET only, declared origins).</li>
 *   <li>Rows are written only by the platform, from the batches handed to the sink, through
 *       {@code IngestService}; a batch uses the {@link ConnectorPort} format (entities, records,
 *       {@code $ref}).</li>
 *   <li>The connector is the only place that knows the tool's URLs, auth headers, paging and fields.</li>
 * </ul>
 *
 * <p>{@link #tool()} is the Integrations tile id ({@code "jira"}, {@code "gh"}); {@link #source()} is
 * the row source ({@code "jira"}, {@code "github"}).
 */
public interface LiveConnector extends ConnectorPort
{
    @Override
    default boolean live()
    {
        return true;
    }

    /**
     * Non-secret config fields, rendered by the generic credentials form:
     * {@code {fields: [{name, label, type, required, pattern, help, options, dependsOn}]}} with type
     * text, url, list, date, boolean or choice.
     */
    JsonObject configSchema();

    /** The secret: {@code {name, label, help, authModes: [...], pattern}}. */
    JsonObject secretSpec();

    /**
     * Origins the connector may call for {@code config}: {@code [{scheme, host, port, pathPrefix}]};
     * {@code pathPrefix} is optional (a context path such as {@code /jira}). Derived from the config
     * (Jira: exactly the base URL's origin, see {@link BaseUrls#origin}) or fixed (GitHub).
     */
    JsonArray allowedOrigins(JsonObject config);

    /** Scopes shown on the tile; empty when the tool does not report scopes. */
    JsonArray requiredScopes();

    /**
     * Calls the tool with the context's config and secret. Resolves to
     * {@code {ok, status, account, scopes, scopeNote, fields, expiresAt, hint}}; {@code ok} is true only
     * when every call answered 200. A failure answer resolves (with ok false) rather than failing.
     */
    Future<JsonObject> testConnection(LiveContext ctx);

    /**
     * Reads every change since {@code since} (the cursor of the last successful sync, or null for a
     * full read), page by page. Each page is handed to {@code sink} as a normalized batch and the next
     * page is requested only after the sink's future succeeds. Resolves to {@code {"cursor": ...}}, plus
     * optional whole-number counts of the connector's own (e.g. Jira's {@code truncatedWorklogs}) that
     * the sync log records beside the entity counts; a failed page fails the whole read with the cause
     * (usually a {@link LiveHttpException}).
     */
    Future<JsonObject> fetchPages(LiveContext ctx, String since, Function<JsonObject, Future<Void>> sink);

    /**
     * The Phase 1 single-batch read for a live connector: every page of {@link #fetchPages} merged into
     * one batch ({@link ConnectorPort} format) carrying the final cursor.
     */
    default Future<JsonObject> fetchAll(LiveContext ctx, String since)
    {
        JsonArray entities = new JsonArray();

        return fetchPages(ctx, since, page ->
        {
            JsonArray pageEntities = page.getJsonArray("entities", new JsonArray());

            for (int i = 0; i < pageEntities.size(); i++)
            {
                entities.add(pageEntities.getJsonObject(i));
            }

            return Future.succeededFuture();
        }).map(result -> new JsonObject()
                .put("source", source())
                .put("cursor", result == null ? null : result.getString("cursor"))
                .put("entities", entities));
    }

    /**
     * A live connector needs its config, secret and guarded client, which only a {@link LiveContext}
     * carries; run it through the live sync orchestrator or {@link #fetchAll}.
     */
    @Override
    default Future<JsonObject> fetchChanges(String since)
    {
        return Future.failedFuture(new IllegalStateException("Live connector '" + tool()
                + "' needs a LiveContext: use fetchAll(ctx, since) or the live sync"));
    }
}
