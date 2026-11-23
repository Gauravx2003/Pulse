package com.pulse.modules.utilization;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * The people on the Team utilization page (task 9.1): those the viewer may see, without the viewer
 * (a Manager sees their reports, {@code all.view} everyone).
 */
public class TeamRepository extends ScopedRepository
{

    public TeamRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * {@code [{id, name, team}]} by name.
     */
    public Future<JsonArray> members(Scope scope, UUID viewer)
    {
        return scopedList(scope, "p.id", """
                SELECT p.id, p.name, p.department AS team
                FROM people p
                WHERE p.user_id IS DISTINCT FROM $1 AND {scope}
                ORDER BY p.name, p.id""", Tuple.of(viewer));
    }
}
