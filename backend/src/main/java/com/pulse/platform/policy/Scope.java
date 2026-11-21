package com.pulse.platform.policy;

import com.pulse.platform.db.ListQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The people a viewer may see in one data domain, resolved by {@link PolicyService#scope} (task 3.5).
 * Only the policy service can create one, so a repository that requires a {@code Scope} argument
 * cannot be called with a filter some other code built (spec rbac-visibility "Single enforcement
 * point").
 *
 * <p>Repositories turn it into SQL with {@link #condition(String)} (or let {@link ScopedRepository} do
 * it); the condition uses {@code ?} placeholders like {@link ListQuery#where}.
 */
public final class Scope
{
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final String domain;

    private final String viewerUserId;

    private final String viewerPersonId;

    private final boolean all;

    private final Set<String> personIds;

    Scope(String domain, String viewerUserId, String viewerPersonId, boolean all, Set<String> personIds)
    {
        this.domain = domain;
        this.viewerUserId = viewerUserId;
        this.viewerPersonId = viewerPersonId;
        this.all = all;
        this.personIds = Collections.unmodifiableSet(personIds);
    }

    /** A condition with {@code ?} placeholders and the values to bind, in order. */
    public record Condition(String sql, List<Object> values)
    {
        public Condition
        {
            values = List.copyOf(values);
        }
    }

    public String domain()
    {
        return domain;
    }

    public String viewerUserId()
    {
        return viewerUserId;
    }

    /** The viewer's own person id; null for an account without a performance profile. */
    public String viewerPersonId()
    {
        return viewerPersonId;
    }

    /** True when the viewer sees everyone in this domain (all.view). */
    public boolean all()
    {
        return all;
    }

    /** The people visible in this domain when {@link #all()} is false. */
    public Set<String> personIds()
    {
        return personIds;
    }

    public boolean allows(String personId)
    {
        return personId != null && (all || personIds.contains(personId));
    }

    /** Rows whose {@code personColumn} is a visible person. */
    public Condition condition(String personColumn)
    {
        return condition(personColumn, null);
    }

    /**
     * Rows whose {@code personColumn} is a visible person, or, with {@code ownerColumn}, rows the viewer
     * owns (account owner of a customer visit). Column names come from repository code, never from a
     * request.
     */
    public Condition condition(String personColumn, String ownerColumn)
    {
        checkColumn(personColumn);

        if (all)
        {
            return new Condition("TRUE", List.of());
        }

        List<Object> values = new ArrayList<>();

        String sql = personColumn + " = ANY(?)";

        values.add(personIds.stream().map(UUID::fromString).toArray(UUID[]::new));

        if (ownerColumn != null && viewerPersonId != null)
        {
            checkColumn(ownerColumn);

            sql = "(" + sql + " OR " + ownerColumn + " = ?)";

            values.add(UUID.fromString(viewerPersonId));
        }

        return new Condition(sql, values);
    }

    /** Adds this scope's condition to a list query. */
    public ListQuery applyTo(ListQuery query, String personColumn)
    {
        return applyTo(query, personColumn, null);
    }

    public ListQuery applyTo(ListQuery query, String personColumn, String ownerColumn)
    {
        Condition c = condition(personColumn, ownerColumn);

        return query.where(c.sql(), c.values().toArray());
    }

    private static void checkColumn(String column)
    {
        Objects.requireNonNull(column, "column");

        if (!COLUMN.matcher(column).matches())
        {
            throw new IllegalArgumentException("Not a column reference: " + column);
        }
    }

    @Override
    public String toString()
    {
        return "Scope[" + domain + ", viewer " + viewerUserId + (all ? ", all" : ", " + personIds.size() + " people") + "]";
    }
}
