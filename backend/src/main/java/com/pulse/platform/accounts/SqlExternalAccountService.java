package com.pulse.platform.accounts;

import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The platform {@link ExternalAccountService} over {@code external_accounts} (task 24.1). Admin
 * changes run in one transaction with their audit entry; nothing here blocks the event loop.
 */
public final class SqlExternalAccountService implements ExternalAccountService
{
    /** A tile id: lower-case letters, digits, dash or underscore. */
    private static final Pattern TOOL = Pattern.compile("[a-z0-9_-]{1,40}");

    private static final int MAX_ACCOUNT_ID = 255;

    private static final int MAX_NAME = 255;

    private static final int MAX_EMAIL = 320;

    private static final Set<String> STATUSES = Set.of(UNMATCHED, LINKED, BOT, ALL);

    private static final String TARGET = "external_account";

    private final SqlExecutor sql;

    public SqlExternalAccountService(SqlExecutor sql)
    {
        this.sql = Objects.requireNonNull(sql, "sql");
    }

    // ------------------------------------------------------------------ connectors

    @Override
    public Future<JsonObject> resolve(String tool, JsonObject account)
    {
        if (tool == null || !TOOL.matcher(tool).matches())
        {
            return Future.failedFuture(new IllegalArgumentException("Not a tool id: " + tool));
        }

        if (account == null)
        {
            return Future.failedFuture(new IllegalArgumentException("account is required"));
        }

        String accountId = clean(account.getValue("accountId"), MAX_ACCOUNT_ID);

        if (accountId == null)
        {
            return Future.failedFuture(new IllegalArgumentException("accountId is required"));
        }

        String login = clean(account.getValue("login"), MAX_NAME);

        String displayName = clean(account.getValue("displayName"), MAX_NAME);

        String email = clean(account.getValue("email"), MAX_EMAIL);

        return new ExternalAccountRepository(sql).resolve(tool, accountId, login == null ? accountId : login,
                displayName, email);
    }

    // ------------------------------------------------------------------ Admin

    @Override
    public Future<JsonObject> link(JsonObject actor, UUID accountId, UUID personId)
    {
        return sql.inTransaction(tx ->
        {
            ExternalAccountRepository repo = new ExternalAccountRepository(tx);

            return repo.lockById(accountId).compose(account ->
            {
                if (account == null)
                {
                    return Future.failedFuture(notFound());
                }

                if (account.getBoolean("erased", false))
                {
                    return Future.failedFuture(ApiException.unprocessable("account_erased",
                            "This account belonged to a deleted user and can't be linked."));
                }

                if (account.getString("personId") != null)
                {
                    return Future.failedFuture(ApiException.conflict("already_linked",
                            "This account is already linked to a person. Unlink it first."));
                }

                return repo.linkablePerson(personId).compose(person ->
                {
                    if (person == null)
                    {
                        return Future.failedFuture(notFound());
                    }

                    return repo.linkIfUnlinked(accountId, personId)
                            .compose(n -> AuditService.record(tx, AuditService.entry(actor,
                                    AuditEvents.EXTERNAL_ACCOUNT_LINKED, TARGET, accountId.toString(),
                                    summary(account).put("personId", new JsonObject()
                                            .putNull("before").put("after", personId.toString())))))
                            .compose(v -> repo.byId(accountId));
                });
            });
        });
    }

    @Override
    public Future<JsonObject> unlink(JsonObject actor, UUID accountId)
    {
        return sql.inTransaction(tx ->
        {
            ExternalAccountRepository repo = new ExternalAccountRepository(tx);

            return repo.lockById(accountId).compose(account ->
            {
                if (account == null)
                {
                    return Future.failedFuture(notFound());
                }

                String before = account.getString("personId");

                if (before == null)
                {
                    // already unlinked: nothing changes, nothing to audit
                    return repo.byId(accountId);
                }

                return repo.unlink(accountId)
                        .compose(n -> AuditService.record(tx, AuditService.entry(actor,
                                AuditEvents.EXTERNAL_ACCOUNT_UNLINKED, TARGET, accountId.toString(),
                                summary(account).put("personId", new JsonObject()
                                        .put("before", before).putNull("after")))))
                        .compose(v -> repo.byId(accountId));
            });
        });
    }

    @Override
    public Future<JsonObject> setBot(JsonObject actor, UUID accountId, boolean bot)
    {
        return sql.inTransaction(tx ->
        {
            ExternalAccountRepository repo = new ExternalAccountRepository(tx);

            return repo.lockById(accountId).compose(account ->
            {
                if (account == null)
                {
                    return Future.failedFuture(notFound());
                }

                boolean before = Boolean.TRUE.equals(account.getBoolean("isBot"));

                if (before == bot)
                {
                    return repo.byId(accountId);
                }

                return repo.setBot(accountId, bot)
                        .compose(n -> AuditService.record(tx, AuditService.entry(actor,
                                AuditEvents.EXTERNAL_ACCOUNT_BOT_CHANGED, TARGET, accountId.toString(),
                                summary(account).put("isBot", new JsonObject()
                                        .put("before", before).put("after", bot)))))
                        .compose(v -> repo.byId(accountId));
            });
        });
    }

    @Override
    public Future<JsonObject> list(MultiMap params)
    {
        MultiMap rest = MultiMap.caseInsensitiveMultiMap();

        if (params != null)
        {
            rest.addAll(params);
        }

        String status = rest.contains("status") ? rest.get("status").strip().toLowerCase(Locale.ROOT) : UNMATCHED;

        rest.remove("status");

        if (!STATUSES.contains(status))
        {
            return Future.failedFuture(ApiException.badRequest("invalid_filter",
                    "Unknown status '" + status + "'. Allowed: unmatched, linked, bot, all"));
        }

        return new ExternalAccountRepository(sql).page(status, rest);
    }

    // ------------------------------------------------------------------ helpers

    /** Names the account in an audit entry; never the email. */
    private static JsonObject summary(JsonObject account)
    {
        return new JsonObject().put("tool", account.getString("tool")).put("login", account.getString("login"));
    }

    private static ApiException notFound()
    {
        return ApiException.notFound("not_found", "Not found");
    }

    /** The trimmed string, cut to {@code max} characters, or null when blank or absent. */
    private static String clean(Object value, int max)
    {
        if (value == null)
        {
            return null;
        }

        String s = value.toString().strip();

        if (s.isEmpty())
        {
            return null;
        }

        return s.length() > max ? s.substring(0, max) : s;
    }
}
