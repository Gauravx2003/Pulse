package com.pulse.modules.bugs;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * Reads of bugs and support tickets (task 7.3). Bugs are scoped by the code owner they are attributed
 * to, and also visible to whoever is fixing them (the assignee owner column), so "Take it" and "Pick
 * up" can show a bug in someone else's code. Support tickets are scoped by the person who took them;
 * the unassigned queue is not personal data. Every personal read takes a {@link Scope} ({@code person}
 * domain). Rows are written only by {@code IngestService} (task 5.4).
 */
public class BugRepository extends ScopedRepository
{

    private static final String BUG = """
            SELECT b.id, b.key, b.title, b.severity, b.status, b.environment,
                   b.origin_person_id AS "originPersonId", b.origin_ref AS "originRef",
                   b.attribution_method AS "attributionMethod", b.found_by AS "foundBy",
                   b.assignee_id AS "assigneeId", b.fixed_by_id AS "fixedById", b.proactive,
                   b.reported_on AS "reportedOn", b.resolved_on AS "resolvedOn", b.source
            FROM bugs b
            """;

    private static final String TICKET = """
            SELECT t.id, t.key, t.title, t.customer, t.priority, t.person_id AS "personId",
                   t.how_taken AS "howTaken", t.hours, t.status, t.opened_on AS "openedOn",
                   t.resolved_on AS "resolvedOn", t.csat, t.source
            FROM support_tickets t
            """;

    public BugRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * Bugs attributed to a person's code, newest first.
     */
    public Future<JsonArray> inCodeOf(Scope scope, UUID personId)
    {
        return scopedList(scope, "b.origin_person_id",
                BUG + " WHERE b.origin_person_id = $1 AND {scope} ORDER BY b.reported_on DESC, b.key",
                Tuple.of(personId));
    }

    /**
     * Bugs a person fixed in someone else's code ("Bugs fixed for others"), newest first.
     */
    public Future<JsonArray> fixedForOthersBy(Scope scope, UUID personId)
    {
        return scopedList(scope, "b.fixed_by_id", BUG + """
                 WHERE b.fixed_by_id = $1 AND b.origin_person_id IS DISTINCT FROM b.fixed_by_id AND {scope}
                ORDER BY b.resolved_on DESC NULLS LAST, b.key""", Tuple.of(personId));
    }

    /**
     * One bug by key, visible to whoever may see its code owner and to the person fixing it.
     */
    public Future<JsonObject> byKey(Scope scope, String key)
    {
        return scopedOne(scope, "b.origin_person_id", "b.assignee_id",
                BUG + " WHERE b.key = $1 AND {scope} ORDER BY b.reported_on DESC, b.id LIMIT 1", Tuple.of(key));
    }

    /**
     * Support tickets a person took, newest first.
     */
    public Future<JsonArray> ticketsOf(Scope scope, UUID personId)
    {
        return scopedList(scope, "t.person_id",
                TICKET + " WHERE t.person_id = $1 AND {scope} ORDER BY t.opened_on DESC, t.key", Tuple.of(personId));
    }

    /**
     * Open tickets nobody has taken ("Support queue"): not personal data, so no scope.
     */
    public Future<JsonArray> ticketQueue()
    {
        return queryList(TICKET + " WHERE t.person_id IS NULL AND t.status = 'Open' ORDER BY t.opened_on, t.key",
                Tuple.tuple());
    }
}
