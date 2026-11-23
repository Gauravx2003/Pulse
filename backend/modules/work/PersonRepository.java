package com.pulse.modules.work;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * The identity header of the person screens (task 8.2, reference section 7.1 "identity (name, title,
 * team, level, manager)"): read from the platform's people, users and reporting lines, scoped like any
 * personal data. The shell's {@code GET /api/v1/people} lists name, title and team only.
 */
public class PersonRepository extends ScopedRepository
{

    public PersonRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * {@code {id, name, title, team, level, joinedOn, manager}}, or null when the person is not visible.
     */
    public Future<JsonObject> identity(Scope scope, UUID personId)
    {
        return scopedOne(scope, "p.id", """
                SELECT p.id, p.name, p.title, p.department AS team, p.level, p.joined_on AS "joinedOn",
                       (SELECT m.name FROM reporting_lines l JOIN users m ON m.id = l.manager_id
                        WHERE l.user_id = p.user_id AND l.valid_to IS NULL) AS manager
                FROM people p
                WHERE p.id = $1 AND {scope}""", Tuple.of(personId));
    }
}
