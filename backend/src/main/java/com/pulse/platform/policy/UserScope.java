package com.pulse.platform.policy;

import com.pulse.platform.db.ListQuery;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The user accounts a viewer may see and manage in the Users directory, resolved by
 * {@link PolicyService#userScope} (task 5.1, spec admin/users-roles "Directory", "Role limits for
 * managers", platform/privacy-retention "Deactivation versus deletion"). It is about accounts, not
 * performance profiles, so it holds user ids; {@link Scope} holds person ids.
 *
 * <p>A holder of users.all sees every account. A holder of users.team sees the accounts whose current
 * reporting line points to them, without deactivated ones. Only the policy service can create one.
 */
public final class UserScope
{
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final String viewerUserId;

    private final boolean all;

    private final Set<String> userIds;

    UserScope(String viewerUserId, boolean all, Set<String> userIds)
    {
        this.viewerUserId = viewerUserId;
        this.all = all;
        this.userIds = Collections.unmodifiableSet(userIds);
    }

    public String viewerUserId()
    {
        return viewerUserId;
    }

    /** True for users.all: every account, deactivated ones included. */
    public boolean all()
    {
        return all;
    }

    /** The visible accounts when {@link #all()} is false. */
    public Set<String> userIds()
    {
        return userIds;
    }

    public boolean allows(String userId)
    {
        return userId != null && (all || userIds.contains(userId));
    }

    /** Rows whose {@code userColumn} is a visible account ({@code ?} placeholders, like {@link ListQuery#where}). */
    public Scope.Condition condition(String userColumn)
    {
        if (userColumn == null || !COLUMN.matcher(userColumn).matches())
        {
            throw new IllegalArgumentException("Not a column reference: " + userColumn);
        }

        if (all)
        {
            return new Scope.Condition("TRUE", List.of());
        }

        return new Scope.Condition(userColumn + " = ANY(?)",
                List.of((Object) userIds.stream().map(UUID::fromString).toArray(UUID[]::new)));
    }

    /** Adds this scope's condition to a list query. */
    public ListQuery applyTo(ListQuery query, String userColumn)
    {
        Scope.Condition c = condition(userColumn);

        return query.where(c.sql(), c.values().toArray());
    }

    @Override
    public String toString()
    {
        return "UserScope[viewer " + viewerUserId + (all ? ", all" : ", " + userIds.size() + " users") + "]";
    }
}
