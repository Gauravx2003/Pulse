package com.pulse.platform.ingest;

import com.pulse.calc.core.CalcService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.logging.Redactor;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One sync run (spec admin/integrations-connectors "Sync now"): opens a {@code sync_log} entry, calls
 * the connector's {@code fetchChanges(since)}, writes the batch through {@link IngestService}, closes
 * the entry with the counts and outcome, and publishes the {@link CalcService} invalidation event so
 * the next read of every calculated figure sees the new rows. A failed run keeps the rows already
 * written by earlier runs, records the failure and resolves (it does not fail) with the closed entry,
 * whose {@code outcome} is {@code failed}.
 */
public final class SyncService
{
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private static final int MAX_MESSAGE = 200;

    private final Vertx vertx;

    private final SqlExecutor sql;

    public SyncService(Vertx vertx, SqlExecutor sql)
    {
        this.vertx = vertx;
        this.sql = sql;
    }

    /**
     * Runs {@code connector} once under the tool name {@code logTool} (the tile id, or
     * {@link SeedConnector#ALL_TOOLS} for the start-up load). Resolves to the closed sync_log row.
     *
     * @param since       cursor for {@code fetchChanges}, or null for a full read
     * @param triggeredBy user who clicked Sync now, or null for the system
     */
    public Future<JsonObject> run(ConnectorPort connector, String logTool, String since, UUID triggeredBy)
    {
        SyncLogRepository logs = new SyncLogRepository(sql);

        return logs.start(logTool, triggeredBy, !connector.live()).compose(entry ->
        {
            UUID id = UUID.fromString(entry.getString("id"));

            return connector.fetchChanges(since)
                    .compose(batch -> new IngestService(sql).ingest(batch).map(counts ->
                    {
                        counts.put("cursor", batch.getString("cursor"));

                        return counts;
                    }))
                    .compose(counts ->
                    {
                        CalcService.publish(vertx, topics(counts));

                        return logs.finish(id, "success", counts, summary(connector, counts));
                    })
                    .recover(err ->
                    {
                        log.warn("Sync of {} failed: {}", logTool, Redactor.redact(String.valueOf(err.getMessage())));

                        return logs.finish(id, "failed", new JsonObject(), failure(err));
                    });
        });
    }

    /** Every touched table as a topic, plus {@link CalcService#ALL} so no cached figure survives. */
    static String[] topics(JsonObject counts)
    {
        Set<String> topics = new LinkedHashSet<>();

        topics.add(CalcService.ALL);
        topics.addAll(counts.getJsonObject("entities", new JsonObject()).fieldNames());

        return topics.toArray(String[]::new);
    }

    private static String summary(ConnectorPort connector, JsonObject counts)
    {
        JsonObject entities = counts.getJsonObject("entities", new JsonObject());
        JsonArray parts = new JsonArray();

        for (String table : entities.fieldNames())
        {
            JsonObject c = entities.getJsonObject(table);
            int n = c.getInteger("inserted") + c.getInteger("updated") + c.getInteger("unchanged");

            parts.add(n + " " + table.replace('_', ' '));
        }

        String what = parts.isEmpty() ? "no records" : String.join(", ", parts.stream().map(String::valueOf).toList());

        return (connector.live() ? "Synced " : "Simulated sync from seed: ") + what;
    }

    /** The failure as shown on the tile, e.g. "401 Unauthorized"; never a secret. */
    static String failure(Throwable err)
    {
        String message = err instanceof ConnectorException c ? c.display()
                : Redactor.redact(err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage());

        return message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message;
    }
}
