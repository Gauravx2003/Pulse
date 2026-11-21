package com.pulse.modules.reviews;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.UUID;

/** 1:1s and their action items (V330). Personal data: every read takes a policy {@link Scope}. */
final class OneOnOneRepository extends ScopedRepository
{
    /** One 1:1 with its manager's name and its action items, oldest action first. */
    private static final String MEETING = """
            SELECT o.id, o.person_id AS "personId", p.user_id AS "personUserId", p.name AS "personName",
                   o.manager_id AS "managerId", m.name AS "managerName",
                   o.held_on AS "heldOn", o.agenda, o.notes, o.version,
                   coalesce((SELECT json_agg(json_build_object(
                                   'id', a.id, 'text', a.text, 'ownerId', a.owner_id, 'dueOn', a.due_on,
                                   'done', a.done, 'version', a.version) ORDER BY a.created_at)
                             FROM one_on_one_actions a WHERE a.one_on_one_id = o.id), '[]'::json) AS actions
            FROM one_on_ones o JOIN users m ON m.id = o.manager_id JOIN people p ON p.id = o.person_id
            """;

    OneOnOneRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** A person's 1:1s, newest first. */
    Future<JsonArray> forPerson(Scope scope, UUID personId)
    {
        return scopedList(scope, "o.person_id",
                MEETING + " WHERE o.person_id = $1 AND {scope} ORDER BY o.held_on DESC, o.created_at DESC",
                Tuple.of(personId));
    }

    /** One 1:1 the scope may see, or null. */
    Future<JsonObject> byId(Scope scope, UUID id)
    {
        return scopedOne(scope, "o.person_id", MEETING + " WHERE o.id = $1 AND {scope}", Tuple.of(id));
    }

    Future<JsonObject> insert(UUID personId, UUID managerId, LocalDate heldOn, JsonArray agenda, UUID createdBy)
    {
        return queryOne("""
                INSERT INTO one_on_ones (person_id, manager_id, held_on, agenda, created_by)
                VALUES ($1, $2, $3, $4::jsonb, $5) RETURNING id
                """,
                Tuple.of(personId, managerId, heldOn, agenda, createdBy));
    }

    Future<JsonObject> updateNotes(UUID id, String notes, int expectedVersion)
    {
        return updateVersioned("one_on_ones", "notes = $1", Tuple.of(notes), id, expectedVersion);
    }

    /** An action item the scope may see, with its 1:1's participants, or null. */
    Future<JsonObject> actionById(Scope scope, UUID id)
    {
        return scopedOne(scope, "a.person_id", """
                SELECT a.id, a.one_on_one_id AS "oneOnOneId", a.person_id AS "personId", a.text,
                       a.owner_id AS "ownerId", a.due_on AS "dueOn", a.done, a.version, o.manager_id AS "managerId",
                       p.user_id AS "personUserId"
                FROM one_on_one_actions a JOIN one_on_ones o ON o.id = a.one_on_one_id JOIN people p ON p.id = a.person_id
                WHERE a.id = $1 AND {scope}
                """,
                Tuple.of(id));
    }

    Future<JsonObject> insertAction(UUID oneOnOneId, UUID personId, String text, UUID ownerId, LocalDate dueOn)
    {
        return queryOne("""
                INSERT INTO one_on_one_actions (one_on_one_id, person_id, text, owner_id, due_on)
                VALUES ($1, $2, $3, $4, $5)
                RETURNING id, text, owner_id AS "ownerId", due_on AS "dueOn", done, version
                """,
                Tuple.of(oneOnOneId, personId, text, ownerId, dueOn));
    }

    Future<JsonObject> updateAction(UUID id, String text, UUID ownerId, LocalDate dueOn, boolean done, int expectedVersion)
    {
        return updateVersioned("one_on_one_actions", "text = $1, owner_id = $2, due_on = $3, done = $4",
                Tuple.of(text, ownerId, dueOn, done), id, expectedVersion);
    }
}
