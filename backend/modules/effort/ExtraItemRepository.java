package com.pulse.modules.effort;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Reads of extra-mile items (task 7.3): unplanned and extra work detected for a person, awaiting a
 * manager or decided. Every read takes a {@link Scope} ({@code person} domain); confirm and reject
 * (task 11.2) lock the visible row and update it with its version.
 */
public class ExtraItemRepository extends ScopedRepository
{

    private static final String ITEM = """
            SELECT x.id, x.detection_key AS "detectionKey", x.person_id AS "personId", x.kind, x.ref, x.title,
                   x.requested_by AS "requestedBy", x.detection_note AS "detectionNote",
                   x.evidence_source AS "evidenceSource", x.occurred_on AS "occurredOn", x.hours,
                   x.is_done AS "isDone", x.values_credited AS "valuesCredited", x.state,
                   x.decided_by AS "decidedBy", x.decided_at AS "decidedAt", x.version
            FROM extra_items x
            """;

    public ExtraItemRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * A person's items that happened between two dates (inclusive), newest first.
     */
    public Future<JsonArray> forPerson(Scope scope, UUID personId, LocalDate from, LocalDate to)
    {
        return scopedList(scope, "x.person_id", ITEM
                        + " WHERE x.person_id = $1 AND x.occurred_on BETWEEN $2 AND $3 AND {scope}"
                        + " ORDER BY x.occurred_on DESC, x.id",
                Tuple.of(personId, from, to));
    }

    /**
     * One item (the evidence modal), or {@code null} when it does not exist or is not visible.
     */
    public Future<JsonObject> byId(Scope scope, UUID id)
    {
        return scopedOne(scope, "x.person_id", ITEM + " WHERE x.id = $1 AND {scope}", Tuple.of(id));
    }

    /**
     * One visible item, locked until the transaction ends, or {@code null}.
     */
    public Future<JsonObject> lockVisible(Scope scope, UUID id)
    {
        return scopedOne(scope, "x.person_id", ITEM + " WHERE x.id = $1 AND {scope} FOR UPDATE OF x", Tuple.of(id));
    }

    /**
     * Records a decision ({@code confirmed} or {@code rejected}) on the version the client saw; 409
     * {@code version_conflict} when the item changed since. Resolves to the item as {@link #byId} reads it.
     */
    public Future<JsonObject> decide(UUID id, String state, UUID decidedBy, int expectedVersion)
    {
        return updateVersioned("extra_items", "state = $1, decided_by = $2, decided_at = now()",
                Tuple.of(state, decidedBy), id, expectedVersion)
                .compose(row -> queryOne(ITEM + " WHERE x.id = $1", Tuple.of(id)));
    }
}
