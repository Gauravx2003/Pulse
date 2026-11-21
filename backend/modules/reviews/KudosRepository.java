package com.pulse.modules.reviews;

import com.pulse.platform.db.ListQuery;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/** Kudos (V330). Reads of the feed take a policy {@link Scope}. */
final class KudosRepository extends ScopedRepository
{
    private static final String FEED = """
            SELECT k.id, k.person_id AS "personId", p.name AS "personName", k.from_user_id AS "fromUserId",
                   u.name AS "fromName", k.culture_value AS "value", k.message, k.shared_to AS "sharedTo",
                   k.source, k.given_at AS "givenAt"
            FROM kudos k JOIN people p ON p.id = k.person_id LEFT JOIN users u ON u.id = k.from_user_id
            """;

    KudosRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * The recipient's id, name and user id, or null. Anyone holding oneonone may recognise any
     * colleague, so this is an existence check, not a read of personal data.
     */
    Future<JsonObject> recipient(UUID personId)
    {
        return queryOne("SELECT id, name, user_id AS \"userId\" FROM people WHERE id = $1", Tuple.of(personId));
    }

    Future<JsonObject> insert(UUID personId, UUID fromUserId, String value, String message, String sharedTo)
    {
        return queryOne("""
                INSERT INTO kudos (person_id, from_user_id, culture_value, message, shared_to)
                VALUES ($1, $2, $3, $4, $5) RETURNING id, given_at AS "givenAt"
                """,
                Tuple.of(personId, fromUserId, value, message, sharedTo));
    }

    /** The feed of people the scope may see, newest first ({@code ?personId}, {@code ?value}, paging). */
    Future<JsonObject> feed(Scope scope, MultiMap params)
    {
        return scopedPage(scope, "k.person_id", ListQuery.from(FEED)
                .filter("personId", "k.person_id")
                .filter("value", "k.culture_value")
                .sort("given", "k.given_at")
                .defaultSort("-given")
                .tieBreaker("k.id"), params);
    }
}
