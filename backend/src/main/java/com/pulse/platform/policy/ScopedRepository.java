package com.pulse.platform.policy;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.ListQuery;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.Objects;

/**
 * Base class for repositories of personal, salary, attendance, review and pulse data (task 3.5, spec
 * rbac-visibility "Single enforcement point"). Its read helpers take a {@link Scope}, which only
 * {@link PolicyService} can create, so a read cannot be written without one: a missing scope fails
 * the call with {@link NullPointerException} before any SQL runs.
 *
 * <p>Statements mark where the visibility filter goes with {@code {scope}}:
 *
 * <pre>{@code
 * public class OkrRepository extends ScopedRepository {
 *     public Future<JsonArray> forPerson(Scope scope, String personId) {
 *         return scopedList(scope, "o.person_id",
 *                 "SELECT o.id, o.title FROM okrs o WHERE o.person_id = $1 AND {scope}",
 *                 Tuple.of(UUID.fromString(personId)));
 *     }
 * }
 * }</pre>
 */
public abstract class ScopedRepository extends BaseRepository
{
    public static final String SCOPE_MARK = "{scope}";

    protected ScopedRepository(SqlExecutor sql)
    {
        super(sql);
    }

    protected Future<JsonArray> scopedList(Scope scope, String personColumn, String statement, Tuple params)
    {
        return scopedList(scope, personColumn, (Scope.Owner) null, statement, params);
    }

    /** As {@link #scopedList(Scope, String, String, Tuple)}, also returning rows the viewer owns (person id column). */
    protected Future<JsonArray> scopedList(Scope scope, String personColumn, String ownerColumn, String statement,
                                           Tuple params)
    {
        return scopedList(scope, personColumn, owner(ownerColumn), statement, params);
    }

    /**
     * As {@link #scopedList(Scope, String, String, Tuple)}, also returning rows the viewer owns; use
     * {@code Scope.Owner.user("v.owner_id")} when the owner column holds a user account id.
     */
    protected Future<JsonArray> scopedList(Scope scope, String personColumn, Scope.Owner owner, String statement,
                                           Tuple params)
    {
        Bound bound = bind(scope, personColumn, owner, statement, params);

        return queryList(bound.sql(), bound.params());
    }

    protected Future<JsonObject> scopedOne(Scope scope, String personColumn, String statement, Tuple params)
    {
        return scopedOne(scope, personColumn, (Scope.Owner) null, statement, params);
    }

    protected Future<JsonObject> scopedOne(Scope scope, String personColumn, String ownerColumn, String statement,
                                           Tuple params)
    {
        return scopedOne(scope, personColumn, owner(ownerColumn), statement, params);
    }

    protected Future<JsonObject> scopedOne(Scope scope, String personColumn, Scope.Owner owner, String statement,
                                           Tuple params)
    {
        Bound bound = bind(scope, personColumn, owner, statement, params);

        return queryOne(bound.sql(), bound.params());
    }

    /** {@link BaseRepository#page} restricted to the scope. */
    protected Future<JsonObject> scopedPage(Scope scope, String personColumn, ListQuery query, MultiMap params)
    {
        Objects.requireNonNull(scope, "scope: personal data needs a policy scope");

        return page(scope.applyTo(query, personColumn), params);
    }

    private static Scope.Owner owner(String ownerColumn)
    {
        return ownerColumn == null ? null : Scope.Owner.person(ownerColumn);
    }

    record Bound(String sql, Tuple params)
    {
    }

    /** Replaces {@code {scope}} with the scope condition, numbering its placeholders after {@code params}. */
    static Bound bind(Scope scope, String personColumn, String ownerColumn, String statement, Tuple params)
    {
        return bind(scope, personColumn, owner(ownerColumn), statement, params);
    }

    static Bound bind(Scope scope, String personColumn, Scope.Owner owner, String statement, Tuple params)
    {
        Objects.requireNonNull(scope, "scope: personal data needs a policy scope");

        int at = statement.indexOf(SCOPE_MARK);

        if (at < 0 || statement.indexOf(SCOPE_MARK, at + 1) >= 0)
        {
            throw new IllegalArgumentException("A scoped statement needs exactly one " + SCOPE_MARK + ": " + statement);
        }

        Scope.Condition condition = scope.condition(personColumn, owner);

        Tuple bound = Tuple.tuple();

        int size = params == null ? 0 : params.size();

        for (int i = 0; i < size; i++)
        {
            bound.addValue(params.getValue(i));
        }

        StringBuilder sql = new StringBuilder();

        int next = size;

        for (char c : condition.sql().toCharArray())
        {
            if (c == '?')
            {
                sql.append('$').append(++next);
            }
            else
            {
                sql.append(c);
            }
        }

        condition.values().forEach(bound::addValue);

        return new Bound(statement.substring(0, at) + sql + statement.substring(at + SCOPE_MARK.length()), bound);
    }
}
