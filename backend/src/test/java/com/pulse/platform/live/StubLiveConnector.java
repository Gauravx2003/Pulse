package com.pulse.platform.live;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.function.Function;

/**
 * A minimal live connector for platform tests (tasks 24.3 to 24.6). Config {@code {baseUrl}}, secret a
 * bearer token. {@code testConnection} reads {@code GET <baseUrl>/myself}; {@code fetchPages} reads
 * {@code GET <baseUrl>/items?page=N&since=<cursor>} until a page answers {@code {"items": []}}, and
 * hands each page to the sink as a batch of {@code stub_items} records. Answers
 * {@code {"items": [{"id", "updated", "assignee"?: {accountId, login, email}}]}}.
 */
public class StubLiveConnector implements LiveConnector
{
    public static final String TOOL = "stub";

    @Override
    public String tool()
    {
        return TOOL;
    }

    @Override
    public String source()
    {
        return "stub";
    }

    @Override
    public JsonObject configSchema()
    {
        return new JsonObject().put("fields", new JsonArray()
                .add(new JsonObject().put("name", "baseUrl").put("label", "Base URL").put("type", "url")
                        .put("required", true))
                // optional and unused by the reads: changing it is a config change (cursor reset tests)
                .add(new JsonObject().put("name", "label").put("label", "Label").put("type", "text")));
    }

    @Override
    public JsonObject secretSpec()
    {
        return new JsonObject().put("name", "token").put("label", "Token").put("authModes", new JsonArray().add("bearer"));
    }

    @Override
    public JsonArray allowedOrigins(JsonObject config)
    {
        return new JsonArray().add(BaseUrls.origin(config.getString("baseUrl")));
    }

    @Override
    public JsonArray requiredScopes()
    {
        return new JsonArray().add("read");
    }

    private static JsonObject auth(LiveContext ctx)
    {
        return new JsonObject().put("Authorization", "Bearer " + ctx.secret()).put("Accept", "application/json");
    }

    private static String base(LiveContext ctx)
    {
        return BaseUrls.normalize(ctx.config().getString("baseUrl"));
    }

    @Override
    public Future<JsonObject> testConnection(LiveContext ctx)
    {
        return ctx.http().get(base(ctx) + "/myself", auth(ctx)).map(res ->
        {
            int status = res.getInteger("status");

            JsonObject body = res.getValue("body") instanceof JsonObject b ? b : new JsonObject();

            return new JsonObject()
                    .put("ok", status == 200)
                    .put("status", status)
                    .put("account", status == 200 ? body.getString("name") : null)
                    .put("scopes", new JsonArray())
                    .put("scopeNote", null)
                    .put("fields", new JsonObject())
                    .put("expiresAt", null)
                    .put("hint", status == 401 ? "Check the token." : null);
        });
    }

    @Override
    public Future<JsonObject> fetchPages(LiveContext ctx, String since, Function<JsonObject, Future<Void>> sink)
    {
        return page(ctx, since, 1, since, sink);
    }

    private Future<JsonObject> page(LiveContext ctx, String since, int page, String cursor,
                                    Function<JsonObject, Future<Void>> sink)
    {
        String url = base(ctx) + "/items?page=" + page + (since == null ? "" : "&since=" + since);

        return ctx.http().getOk(url, auth(ctx)).compose(res ->
        {
            JsonObject body = res.getValue("body") instanceof JsonObject b ? b : null;

            if (body == null || body.getJsonArray("items") == null)
            {
                return Future.failedFuture(LiveHttpException.failed(LiveHttpException.BAD_PAYLOAD, "No items"));
            }

            JsonArray items = body.getJsonArray("items");

            if (items.isEmpty())
            {
                // a connector count of its own, which the sync log records beside the entity counts
                return Future.succeededFuture(new JsonObject().put("cursor", cursor).put("pagesRead", page - 1));
            }

            JsonArray records = new JsonArray();

            String latest = cursor;

            Future<Void> accounts = Future.succeededFuture();

            for (int i = 0; i < items.size(); i++)
            {
                JsonObject item = items.getJsonObject(i);

                JsonObject assignee = item.getJsonObject("assignee");

                if (assignee != null)
                {
                    accounts = accounts.compose(v -> ctx.accounts().resolve(TOOL, assignee).mapEmpty());
                }

                records.add(new JsonObject()
                        .put("external_id", item.getString("id"))
                        .put("updated", item.getString("updated"))
                        .put("assignee_account_id", assignee == null ? null : assignee.getString("accountId")));

                String updated = item.getString("updated");

                if (updated != null && (latest == null || updated.compareTo(latest) > 0))
                {
                    latest = updated;
                }
            }

            String next = latest;

            JsonObject batch = new JsonObject()
                    .put("source", source())
                    .put("entities", new JsonArray().add(new JsonObject()
                            .put("entity", "stub_items").put("records", records)));

            return accounts.compose(v -> sink.apply(batch)).compose(v -> page(ctx, since, page + 1, next, sink));
        });
    }
}
