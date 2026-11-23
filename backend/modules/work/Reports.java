package com.pulse.modules.work;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The people a manager's Action center items are about (task 13.1, spec insight/action-center
 * "Visibility"): a Manager's reports, everyone for {@code all.view}, never the viewer; nobody for a
 * user without {@code team.view} or {@code all.view}.
 */
public final class Reports extends ScopedRepository
{

    private Reports(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * {@code [{id, name}]} by name.
     */
    public static Future<List<JsonObject>> of(ModuleContext ctx, JsonObject user)
    {
        Set<String> held = PolicyService.permissions(user);

        if (!held.contains("team.view") && !held.contains("all.view"))
        {
            return Future.succeededFuture(List.of());
        }

        return ctx.policy().scope(user, PolicyService.DOMAIN_PERSON)
                .compose(scope -> new Reports(ctx.sql()).list(scope, UUID.fromString(user.getString("id"))))
                .map(rows ->
                {
                    List<JsonObject> out = new ArrayList<>();

                    rows.forEach(row -> out.add((JsonObject) row));

                    return out;
                });
    }

    private Future<JsonArray> list(Scope scope, UUID viewer)
    {
        return scopedList(scope, "p.id", """
                SELECT p.id, p.name FROM people p
                WHERE p.user_id IS DISTINCT FROM $1 AND {scope}
                ORDER BY p.name, p.id""", Tuple.of(viewer));
    }
}
