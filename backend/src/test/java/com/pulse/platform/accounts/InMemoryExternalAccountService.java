package com.pulse.platform.accounts;

import com.pulse.platform.http.ApiException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Day 1 stub of {@link ExternalAccountService} (task 24.1) for connector and work-table tests that run
 * without a database: Track 3 hands it to a fake {@code LiveContext}, Track 2 links accounts with it.
 * Same rules as the platform implementation (existing row first, then a case-insensitive email match
 * against {@link #person}, else unmatched; 404 and 409 alike), held in memory. No paging: {@link #list}
 * returns every matching row as one page.
 */
public final class InMemoryExternalAccountService implements ExternalAccountService
{
    private final Map<String, JsonObject> byKey = new LinkedHashMap<>();

    /** lower-case work email -> {id, name} */
    private final Map<String, JsonObject> people = new LinkedHashMap<>();

    /** Registers a person that {@link #resolve} can match by work email and {@link #link} can link to. */
    public InMemoryExternalAccountService person(UUID personId, String name, String email)
    {
        people.put(email.toLowerCase(Locale.ROOT), new JsonObject().put("id", personId.toString()).put("name", name));

        return this;
    }

    /** Every recorded row, in first-seen order. */
    public synchronized JsonArray all()
    {
        JsonArray out = new JsonArray();

        byKey.values().forEach(row -> out.add(row.copy()));

        return out;
    }

    @Override
    public synchronized Future<JsonObject> resolve(String tool, JsonObject account)
    {
        String accountId = account == null ? null : account.getString("accountId");

        if (tool == null || accountId == null || accountId.isBlank())
        {
            return Future.failedFuture(new IllegalArgumentException("tool and accountId are required"));
        }

        String key = tool + "\u0000" + accountId;

        JsonObject row = byKey.get(key);

        if (row == null)
        {
            String email = account.getString("email");

            JsonObject person = email == null ? null : people.get(email.strip().toLowerCase(Locale.ROOT));

            row = new JsonObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("tool", tool)
                    .put("accountId", accountId)
                    .put("isBot", false)
                    .put("personId", person == null ? null : person.getString("id"))
                    .put("personName", person == null ? null : person.getString("name"))
                    .put("firstSeenAt", Instant.now().toString());

            byKey.put(key, row);
        }

        String login = account.getString("login");

        row.put("login", login != null ? login : row.getString("login", accountId));

        if (account.getString("displayName") != null)
        {
            row.put("displayName", account.getString("displayName"));
        }

        if (account.getString("email") != null)
        {
            row.put("email", account.getString("email"));
        }

        row.put("updatedAt", Instant.now().toString());

        return Future.succeededFuture(row.copy());
    }

    @Override
    public synchronized Future<JsonObject> link(JsonObject actor, UUID accountId, UUID personId)
    {
        JsonObject row = find(accountId);

        if (row == null)
        {
            return Future.failedFuture(ApiException.notFound("not_found", "Not found"));
        }

        if (row.getString("personId") != null)
        {
            return Future.failedFuture(ApiException.conflict("already_linked",
                    "This account is already linked to a person. Unlink it first."));
        }

        JsonObject person = people.values().stream()
                .filter(p -> p.getString("id").equals(String.valueOf(personId)))
                .findFirst().orElse(null);

        if (person == null)
        {
            return Future.failedFuture(ApiException.notFound("not_found", "Not found"));
        }

        row.put("personId", person.getString("id")).put("personName", person.getString("name"));

        return Future.succeededFuture(row.copy());
    }

    @Override
    public synchronized Future<JsonObject> unlink(JsonObject actor, UUID accountId)
    {
        JsonObject row = find(accountId);

        if (row == null)
        {
            return Future.failedFuture(ApiException.notFound("not_found", "Not found"));
        }

        row.putNull("personId").putNull("personName");

        return Future.succeededFuture(row.copy());
    }

    @Override
    public synchronized Future<JsonObject> setBot(JsonObject actor, UUID accountId, boolean bot)
    {
        JsonObject row = find(accountId);

        if (row == null)
        {
            return Future.failedFuture(ApiException.notFound("not_found", "Not found"));
        }

        row.put("isBot", bot);

        return Future.succeededFuture(row.copy());
    }

    @Override
    public synchronized Future<JsonObject> list(MultiMap params)
    {
        String status = params == null || params.get("status") == null ? UNMATCHED : params.get("status");

        String tool = params == null ? null : params.get("tool");

        JsonArray items = new JsonArray();

        for (JsonObject row : byKey.values())
        {
            boolean linked = row.getString("personId") != null;

            boolean bot = row.getBoolean("isBot");

            boolean inStatus = switch (status)
            {
                case UNMATCHED -> !linked && !bot;

                case LINKED -> linked;

                case BOT -> bot;

                default -> true;
            };

            if (inStatus && (tool == null || tool.equals(row.getString("tool"))))
            {
                items.add(row.copy());
            }
        }

        return Future.succeededFuture(new JsonObject()
                .put("items", items)
                .put("page", 1)
                .put("pageSize", items.size())
                .put("total", items.size())
                .put("pageSizeCapped", false));
    }

    private JsonObject find(UUID accountId)
    {
        return byKey.values().stream()
                .filter(r -> r.getString("id").equals(String.valueOf(accountId)))
                .findFirst().orElse(null);
    }
}
