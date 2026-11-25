package com.pulse.platform.accounts;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;

import java.util.UUID;

/**
 * Person matching for live tools (task 24.1, change pulse-v2-live-connectors design.md decision 5).
 * Every live tool account a connector sees (a Jira username, a GitHub login) gets one row in
 * {@code external_accounts}, unique on (tool, tool account id). Live rows keep the raw account id and
 * resolve their person through this table at read time, so a link applies from the next read.
 *
 * <p>All inputs and outputs are {@link JsonObject}. An account row is
 * {@code {id, tool, accountId, login, displayName, email, isBot, personId, personName, firstSeenAt,
 * updatedAt}}; {@code personId} is null while the account is unmatched.
 *
 * <p>Connectors reach {@link #resolve} through {@code LiveContext.accounts()}; Admin screens use the
 * other operations through {@code /api/v1/integrations/accounts}. Get the platform implementation from
 * {@code ModuleContext.accounts()}.
 */
public interface ExternalAccountService
{
    /** List filter values of {@link #list}: {@code status=unmatched} (default), linked, bot, all. */
    String UNMATCHED = "unmatched";

    String LINKED = "linked";

    String BOT = "bot";

    String ALL = "all";

    /**
     * Records the account a connector saw and resolves its person, in this order: an existing row
     * (its link, if any, is kept as the Admin left it), else a case-insensitive match of {@code email}
     * against a person's work email, else a new unmatched row. Repeated calls never duplicate a row;
     * login, display name and email are refreshed when given.
     *
     * @param tool    the tile id, e.g. {@code jira}
     * @param account {@code {accountId (required), login, displayName, email}}; login defaults to accountId
     * @return the account row
     */
    Future<JsonObject> resolve(String tool, JsonObject account);

    /**
     * Links an account to a person and audits it. Fails 404 when the account or the person does not
     * exist, and 409 {@code already_linked} when the account is linked already (unlink it first); the
     * existing link is never changed by a refused call.
     */
    Future<JsonObject> link(JsonObject actor, UUID accountId, UUID personId);

    /** Removes the account's person link and audits it; 404 when the account does not exist. */
    Future<JsonObject> unlink(JsonObject actor, UUID accountId);

    /** Marks or unmarks the account as a bot and audits it; a bot leaves the unmatched list. */
    Future<JsonObject> setBot(JsonObject actor, UUID accountId, boolean bot);

    /**
     * A page of accounts, newest first: {@code {items, page, pageSize, total, pageSizeCapped}}.
     * Parameters: {@code status} (see the constants, default unmatched), {@code tool}, {@code q}
     * (login, display name or email), {@code sort}, {@code page}, {@code pageSize}.
     */
    Future<JsonObject> list(MultiMap params);

    /** Unmatched, non-bot accounts of {@code tool}, or of every tool when it is null. */
    default Future<JsonObject> listUnmatched(String tool)
    {
        MultiMap params = MultiMap.caseInsensitiveMultiMap().add("status", UNMATCHED);

        if (tool != null)
        {
            params.add("tool", tool);
        }

        return list(params);
    }
}
