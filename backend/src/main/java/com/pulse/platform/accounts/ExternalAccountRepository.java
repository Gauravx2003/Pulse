package com.pulse.platform.accounts;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.ListQuery;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.LinkedHashMap;
import java.util.Map;
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

    /** Prefix of the opaque account id an erased account takes; no tool issues ids with a colon after this. */
    public static final String ERASED_PREFIX = "erased:";

    /** What an erased account, and the live events it wrote, show instead of a name. */
    public static final String ERASED_NAME = "Deleted user";

    /**
     * Erasure of a deleted person's tool accounts (spec privacy-retention, task 24.8), one statement:
     * every account linked to the person is unlinked and its account id, login, display name and email
     * are replaced by an opaque id. The raw copies in live rows (see {@link #LIVE_REFERENCES}) move to
     * that id, so the rows still join to the (now unlinked) account and stay in team totals with no person.
     * {@code $1} person, {@code $2} {@link #ERASED_PREFIX}, {@code $3} {@link #ERASED_NAME}.
     */
    private static final String ERASE_ACCOUNTS = """
            WITH target AS (
                SELECT id, tool, tool_account_id AS old_id, login, display_name
                FROM external_accounts WHERE person_id = $1 FOR UPDATE
            ), erased AS (
                UPDATE external_accounts ea
                SET person_id = NULL, tool_account_id = $2 || ea.id::text, login = $3,
                    display_name = NULL, email = NULL, updated_at = now()
                FROM target t WHERE ea.id = t.id
                RETURNING ea.id, t.tool, t.old_id, t.login AS old_login, t.display_name AS old_name,
                          ea.tool_account_id AS new_id
            )""";

    /**
     * Live tables holding raw account copies, each with the CTE that rewrites them from {@code erased}.
     * A table is touched only when its module is installed. A module that stores a new raw account
     * column (GitHub pull requests, task 25.5) adds its entry here.
     */
    private static final Map<String, String> LIVE_REFERENCES = new LinkedHashMap<>();

    static
    {
        // the issue assignee
        LIVE_REFERENCES.put("issues", """
                assigned AS (
                    UPDATE issues i SET assignee_account_id = e.new_id
                    FROM erased e WHERE i.source = e.tool AND i.assignee_account_id = e.old_id
                    RETURNING i.id
                )""");

        // the events they wrote, with their name, and assignee changes naming them in anyone's events; one
        // UPDATE, since a row changed by two parts of one statement keeps only one of the changes
        LIVE_REFERENCES.put("issue_events", """
                events AS (
                    UPDATE issue_events ev
                    SET actor_account_id = CASE WHEN ev.actor_account_id = e.old_id THEN e.new_id ELSE ev.actor_account_id END,
                        actor_name = CASE WHEN ev.actor_account_id = e.old_id THEN $3 ELSE ev.actor_name END,
                        from_value = CASE WHEN ev.kind = 'assignee_change' AND ev.from_value IN (e.old_id, e.old_login, e.old_name)
                                          THEN $3 ELSE ev.from_value END,
                        to_value = CASE WHEN ev.kind = 'assignee_change' AND ev.to_value IN (e.old_id, e.old_login, e.old_name)
                                        THEN $3 ELSE ev.to_value END,
                        summary = CASE WHEN ev.kind = 'assignee_change' AND ev.to_value IN (e.old_id, e.old_login, e.old_name)
                                       THEN 'Assigned to ' || $3 ELSE ev.summary END
                    FROM erased e
                    WHERE ev.source = e.tool
                      AND (ev.actor_account_id = e.old_id
                           OR (ev.kind = 'assignee_change' AND (ev.from_value IN (e.old_id, e.old_login, e.old_name)
                                                                 OR ev.to_value IN (e.old_id, e.old_login, e.old_name))))
                    RETURNING ev.id
                )""");
    }

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
        return queryOne("SELECT id, tool, login, person_id AS \"personId\", is_bot AS \"isBot\", "
                + "starts_with(tool_account_id, $2) AS erased FROM external_accounts WHERE id = $1 FOR UPDATE",
                Tuple.of(id, ERASED_PREFIX));
    }

    /**
     * Erases every account linked to the person and the raw copies in the installed live tables; see
     * {@link #ERASE_ACCOUNTS}. Resolves to the number of accounts erased.
     */
    public Future<Integer> eraseForPerson(UUID personId)
    {
        String[] tables = LIVE_REFERENCES.keySet().toArray(String[]::new);

        return queryList("SELECT t FROM unnest($1::text[]) AS t WHERE to_regclass(t) IS NOT NULL", Tuple.tuple().addArrayOfString(tables))
                .compose(installed ->
                {
                    StringBuilder statement = new StringBuilder(ERASE_ACCOUNTS);

                    for (Object row : installed)
                    {
                        statement.append(", ").append(LIVE_REFERENCES.get(((JsonObject) row).getString("t")));
                    }

                    statement.append(" SELECT count(*) AS accounts FROM erased");

                    return queryOne(statement.toString(), Tuple.of(personId, ERASED_PREFIX, ERASED_NAME));
                })
                .map(row -> row.getLong("accounts").intValue());
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
            // an erased account is nobody to link: it stays out of the unmatched list
            case ExternalAccountService.UNMATCHED -> query.where("ea.person_id IS NULL AND NOT ea.is_bot "
                    + "AND NOT starts_with(ea.tool_account_id, '" + ERASED_PREFIX + "')");

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
