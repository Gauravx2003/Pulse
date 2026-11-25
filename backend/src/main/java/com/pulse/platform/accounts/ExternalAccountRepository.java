package com.pulse.platform.accounts;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.ListQuery;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * SQL of {@code external_accounts} (V190, task 24.1). Every method returns account rows in the shape
 * documented on {@link ExternalAccountService}.
 */
public final class ExternalAccountRepository extends BaseRepository
{
    private static final String COLUMNS = "ea.id, ea.tool, ea.tool_account_id AS \"accountId\", ea.login, "
            + "ea.display_name AS \"displayName\", ea.email, ea.is_bot AS \"isBot\", ea.person_id AS \"personId\", "
            + "p.name AS \"personName\", ea.first_seen_at AS \"firstSeenAt\", ea.updated_at AS \"updatedAt\"";

    private static final String ROW = "SELECT " + COLUMNS
            + " FROM external_accounts ea LEFT JOIN people p ON p.id = ea.person_id";

    /**
     * Upsert on (tool, tool_account_id). A new row takes the person whose work email matches
     * case-insensitively (deleted accounts excluded); an existing row keeps its person and bot flag and
     * only refreshes the descriptive fields that were given.
     */
    private static final String RESOLVE = "WITH upserted AS ("
            + "INSERT INTO external_accounts AS ea (tool, tool_account_id, login, display_name, email, person_id) "
            + "VALUES ($1, $2, $3, $4, $5, "
            + "(SELECT p.id FROM people p JOIN users u ON u.id = p.user_id "
            + "WHERE $5::text IS NOT NULL AND lower(u.email) = lower($5::text) AND u.deleted_at IS NULL "
            + "ORDER BY p.created_at, p.id LIMIT 1)) "
            + "ON CONFLICT (tool, tool_account_id) DO UPDATE SET "
            + "login = EXCLUDED.login, "
            + "display_name = COALESCE(EXCLUDED.display_name, ea.display_name), "
            + "email = COALESCE(EXCLUDED.email, ea.email), "
            + "updated_at = CASE WHEN (ea.login, ea.display_name, ea.email) IS DISTINCT FROM "
            + "(EXCLUDED.login, COALESCE(EXCLUDED.display_name, ea.display_name), COALESCE(EXCLUDED.email, ea.email)) "
            + "THEN now() ELSE ea.updated_at END "
            + "RETURNING ea.*) "
            + "SELECT " + COLUMNS + " FROM upserted ea LEFT JOIN people p ON p.id = ea.person_id";

    public ExternalAccountRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** The account row after recording what a connector saw. */
    public Future<JsonObject> resolve(String tool, String accountId, String login, String displayName, String email)
    {
        return queryOne(RESOLVE, Tuple.of(tool, accountId, login, displayName, email));
    }

    /** The account row, or null. */
    public Future<JsonObject> byId(UUID id)
    {
        return queryOne(ROW + " WHERE ea.id = $1", Tuple.of(id));
    }

    /** The account row locked for the rest of the transaction, or null. */
    public Future<JsonObject> lockById(UUID id)
    {
        return queryOne("SELECT id, tool, login, person_id AS \"personId\", is_bot AS \"isBot\" "
                + "FROM external_accounts WHERE id = $1 FOR UPDATE", Tuple.of(id));
    }

    /** Links the account when it has no person yet; resolves to 1 when linked, 0 otherwise. */
    public Future<Integer> linkIfUnlinked(UUID id, UUID personId)
    {
        return execute("UPDATE external_accounts SET person_id = $2, updated_at = now() "
                + "WHERE id = $1 AND person_id IS NULL", Tuple.of(id, personId));
    }

    public Future<Integer> unlink(UUID id)
    {
        return execute("UPDATE external_accounts SET person_id = NULL, updated_at = now() WHERE id = $1",
                Tuple.of(id));
    }

    public Future<Integer> setBot(UUID id, boolean bot)
    {
        return execute("UPDATE external_accounts SET is_bot = $2, updated_at = now() WHERE id = $1",
                Tuple.of(id, bot));
    }

    /** {@code {id, name}} of a person a live account may be linked to (not of a deleted user), or null. */
    public Future<JsonObject> linkablePerson(UUID personId)
    {
        return queryOne("SELECT p.id, p.name FROM people p LEFT JOIN users u ON u.id = p.user_id "
                + "WHERE p.id = $1 AND u.deleted_at IS NULL", Tuple.of(personId));
    }

    /**
     * A page of accounts in {@code status} ({@link ExternalAccountService#UNMATCHED}, LINKED, BOT or
     * ALL); {@code params} must not carry {@code status} itself.
     */
    public Future<JsonObject> page(String status, MultiMap params)
    {
        ListQuery query = ListQuery.from(ROW)
                .filter("tool", "ea.tool")
                .search("ea.login", "ea.display_name", "ea.email")
                .sort("firstSeen", "ea.first_seen_at")
                .sort("login", "lower(ea.login)")
                .sort("tool", "ea.tool")
                .defaultSort("-firstSeen")
                .tieBreaker("ea.id");

        switch (status)
        {
            case ExternalAccountService.UNMATCHED -> query.where("ea.person_id IS NULL AND NOT ea.is_bot");

            case ExternalAccountService.LINKED -> query.where("ea.person_id IS NOT NULL");

            case ExternalAccountService.BOT -> query.where("ea.is_bot");

            default ->
            {
                // ALL: no condition
            }
        }

        return page(query, params);
    }
}
