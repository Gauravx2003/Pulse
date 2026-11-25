package com.pulse.platform.live;

import com.pulse.calc.core.CalcService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.ingest.ConnectorException;
import com.pulse.platform.ingest.IngestService;
import com.pulse.platform.ingest.SyncLogRepository;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.secrets.SecretKeyException;
import io.vertx.core.Future;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sync now for any registered live connector (task 24.6, design.md decision 4, spec
 * admin/integrations-connectors "One sync per tool at a time" and "Partial sync keeps written pages").
 * Nothing here knows a tool. One run:
 *
 * <ol>
 *   <li>Refuses with 409 {@code not_connected} when the tool has no stored secret.</li>
 *   <li>Takes the per-tool lock ({@link SyncStateRepository#lock}); a concurrent call gets 409
 *       {@code sync_running}.</li>
 *   <li>Opens a {@code sync_log} entry and decrypts the secret into the {@link LiveContext} only. A
 *       secret that does not decrypt fails the run with code {@code key} before any request.</li>
 *   <li>Calls {@code fetchPages(ctx, cursor, sink)}; the sink ingests each page through
 *       {@link IngestService} in its own transaction, always under the connector's own source.</li>
 *   <li>Closes the {@code sync_log} entry with the counts per entity, and advances the cursor only on
 *       success.</li>
 *   <li>Publishes the {@link CalcService} invalidation event whenever a page was ingested.</li>
 * </ol>
 *
 * <p>A 401, 403, 429, 5xx, timeout, refused request or bad payload stops the run: pages already
 * ingested stay, the cursor does not move, and the entry closes with outcome {@code failed} and the
 * code as its message (the tile shows "Sync failed · code"). The run then resolves (it does not fail)
 * with that entry; the caller records the credential activity. No secret reaches a log, an entry or
 * a response.
 */
public final class LiveSyncService
{
    /** A {@code running} lock not refreshed for this long belongs to a run that stopped mid-sync. */
    public static final int STALE_LOCK_SECONDS = 15 * 60;

    /** Code of a failure that is neither a tool answer nor a known refusal. */
    public static final String ERROR = "error";

    private static final Logger log = LoggerFactory.getLogger(LiveSyncService.class);

    private final ModuleContext ctx;

    public LiveSyncService(ModuleContext ctx)
    {
        this.ctx = ctx;
    }

    /**
     * Runs one sync of {@code connector}. Resolves to the closed {@code sync_log} row
     * ({@code outcome} {@code success} or {@code failed}); fails with 409 when the tool is not connected
     * or a sync of it is already running.
     *
     * @param triggeredBy user who clicked Sync now, or null for the system
     */
    public Future<JsonObject> run(LiveConnector connector, UUID triggeredBy)
    {
        String tool = connector.tool();

        ConnectionStore store = ctx.connections(ctx.sql());

        SyncStateRepository state = new SyncStateRepository(ctx.sql());

        return store.view(tool).compose(view ->
        {
            if (!connected(view))
            {
                return Future.failedFuture(ApiException.conflict("not_connected",
                        "This tool is not connected. Test and save its credentials first."));
            }

            return state.lock(tool, STALE_LOCK_SECONDS).compose(locked ->
            {
                if (locked == null)
                {
                    return Future.failedFuture(ApiException.conflict("sync_running",
                            "A sync of this tool is already running."));
                }

                JsonObject config = view.getJsonObject("config", new JsonObject());

                return locked(connector, config, locked.getString("cursor"), triggeredBy, store, state)
                        .recover(err -> state.fail(tool, ERROR).transform(v -> Future.failedFuture(err)));
            });
        });
    }

    /** True when the tool has a stored secret and is not disconnected. */
    public static boolean connected(JsonObject view)
    {
        return view != null && "connected".equals(view.getString("state"))
                && Boolean.TRUE.equals(view.getBoolean("hasSecret"));
    }

    /** The run while holding the lock; every path but an unexpected database error releases it. */
    private Future<JsonObject> locked(LiveConnector connector, JsonObject config, String cursor, UUID triggeredBy,
                                      ConnectionStore store, SyncStateRepository state)
    {
        String tool = connector.tool();

        SyncLogRepository logs = new SyncLogRepository(ctx.sql());

        JsonObject entities = new JsonObject();

        return logs.start(tool, triggeredBy, false).compose(entry ->
        {
            UUID id = UUID.fromString(entry.getString("id"));

            return read(connector, config, cursor, store, state, entities).transform(ar ->
            {
                JsonObject counts = counts(connector, entities);

                if (ar.succeeded())
                {
                    String next = ar.result() == null ? null : ar.result().getString("cursor");

                    counts.put("cursor", next);

                    return logs.finish(id, "success", counts, summary(entities))
                            .compose(row -> state.succeed(tool, next).map(row));
                }

                String code = code(ar.cause());

                // the code only: an error message could carry anything the tool or connector put there
                log.warn("Live sync of {} failed: {}", tool, code);

                return logs.finish(id, "failed", counts, code).compose(row -> state.fail(tool, code).map(row));
            }).onSuccess(row ->
            {
                if (!entities.isEmpty())
                {
                    CalcService.publish(ctx.vertx(), topics(entities));
                }
            });
        });
    }

    /** Decrypts the secret, builds the context and reads every page; closes the context afterwards. */
    private Future<JsonObject> read(LiveConnector connector, JsonObject config, String cursor, ConnectionStore store,
                                    SyncStateRepository state, JsonObject entities)
    {
        String tool = connector.tool();

        return store.secret(tool).compose(secret ->
        {
            LiveContext live;

            try
            {
                GuardedHttp http = new GuardedHttp(ctx.vertx(), tool, connector.allowedOrigins(config));

                live = LiveContext.of(tool, config, secret, http, ctx.accounts());
            }
            catch (RuntimeException e)
            {
                return Future.failedFuture(e);
            }

            Future<JsonObject> pages;

            try
            {
                pages = connector.fetchPages(live, cursor, page -> ingest(connector, page, state, entities));
            }
            catch (RuntimeException e)
            {
                pages = Future.failedFuture(e);
            }

            return pages.eventually(v ->
            {
                live.close();

                return Future.succeededFuture();
            });
        });
    }

    /**
     * Ingests one page in its own transaction, under the connector's source whatever the page says, so
     * a live run can never write seed rows. Adds the page's counts to {@code entities}.
     */
    private Future<Void> ingest(LiveConnector connector, JsonObject page, SyncStateRepository state,
                                JsonObject entities)
    {
        if (page == null)
        {
            return Future.failedFuture(LiveHttpException.failed(LiveHttpException.BAD_PAYLOAD, "Empty page"));
        }

        JsonObject batch = page.copy().put("source", connector.source());

        return new IngestService(ctx.sql()).ingest(batch)
                .compose(result ->
                {
                    add(entities, result.getJsonObject("entities", new JsonObject()));

                    return state.touch(connector.tool());
                });
    }

    // ------------------------------------------------------------------ results

    /** Adds one page's {@code {table: {inserted, updated, unchanged}}} to the run's totals. */
    static void add(JsonObject totals, JsonObject page)
    {
        for (String table : page.fieldNames())
        {
            JsonObject c = page.getJsonObject(table);

            JsonObject t = totals.getJsonObject(table);

            if (t == null)
            {
                t = new JsonObject().put("inserted", 0).put("updated", 0).put("unchanged", 0);

                totals.put(table, t);
            }

            for (String key : List.of("inserted", "updated", "unchanged"))
            {
                t.put(key, t.getInteger(key) + c.getInteger(key, 0));
            }
        }
    }

    /** The {@code sync_log.counts} of a run, the same shape as a Phase 1 sync. */
    private static JsonObject counts(LiveConnector connector, JsonObject entities)
    {
        int total = 0;

        for (String table : entities.fieldNames())
        {
            total += rows(entities.getJsonObject(table));
        }

        return new JsonObject().put("source", connector.source()).put("total", total).put("entities", entities.copy());
    }

    private static int rows(JsonObject c)
    {
        return c.getInteger("inserted") + c.getInteger("updated") + c.getInteger("unchanged");
    }

    private static String summary(JsonObject entities)
    {
        List<String> parts = new ArrayList<>();

        for (String table : entities.fieldNames())
        {
            parts.add(rows(entities.getJsonObject(table)) + " " + table.replace('_', ' '));
        }

        return "Synced " + (parts.isEmpty() ? "no records" : String.join(", ", parts));
    }

    /** Every touched table as a topic, plus {@link CalcService#ALL} so no cached figure survives. */
    static String[] topics(JsonObject entities)
    {
        Set<String> topics = new LinkedHashSet<>();

        topics.add(CalcService.ALL);
        topics.addAll(entities.fieldNames());

        return topics.toArray(String[]::new);
    }

    /**
     * The code the tile shows after "Sync failed · ": the HTTP status ({@code 401}, {@code 429},
     * {@code 500}), a guard code ({@code timeout}, {@code redirect}, {@code origin}, ...), {@code key}
     * for a secret that can't be decrypted, {@code bad_payload} for a page that is not a valid batch,
     * else {@code error}. Never a message.
     */
    static String code(Throwable err)
    {
        if (err instanceof LiveHttpException e)
        {
            return e.code();
        }

        if (err instanceof SecretKeyException)
        {
            return SecretKeyException.SYNC_ERROR;
        }

        if (err instanceof ConnectorException c)
        {
            return c.status() > 0 ? String.valueOf(c.status()) : ERROR;
        }

        if (err instanceof IllegalArgumentException || err instanceof ClassCastException
                || err instanceof DecodeException || err instanceof NullPointerException)
        {
            return LiveHttpException.BAD_PAYLOAD;
        }

        return ERROR;
    }
}
