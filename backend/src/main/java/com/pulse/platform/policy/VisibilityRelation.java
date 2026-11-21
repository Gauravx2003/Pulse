package com.pulse.platform.policy;

import java.util.Objects;

/**
 * An exception to the base visibility rules that a module declares through
 * {@link com.pulse.platform.module.PulseModule#visibilityRelations()} (task 3.5, design.md decision 2):
 * for one data domain it adds the people the viewer may see through a relation the module owns, e.g.
 * the training module declares that a mentor sees their mentees' training data.
 *
 * <pre>{@code
 * new VisibilityRelation(PolicyService.DOMAIN_TRAINING, "mentor",
 *         "SELECT mentee_id AS person_id FROM training_mentors WHERE mentor_id = $1")
 * }</pre>
 *
 * <p>The query receives the viewer's person id as {@code $1} (uuid) and returns the visible people
 * in a {@code person_id} column. It is the only SQL a module contributes to visibility; the policy
 * service runs it and builds every filter. The relation applies to {@code domain} only, so a mentor
 * still cannot see the mentee's OKRs, attendance or appraisal data.
 *
 * @param domain data domain the relation widens, e.g. {@code "training"}
 * @param name   short name for logs and error messages, e.g. {@code "mentor"}
 * @param sql    parameterized query returning {@code person_id}; {@code $1} is the viewer's person id
 */
public record VisibilityRelation(String domain, String name, String sql)
{
    public VisibilityRelation
    {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sql, "sql");

        if (PolicyService.DOMAIN_CHECKIN.equals(domain))
        {
            throw new IllegalArgumentException("Check-in times are visible to the person and their direct manager only; "
                    + "relation '" + name + "' cannot widen them");
        }

        if (!sql.contains("$1"))
        {
            throw new IllegalArgumentException("Relation '" + name + "' must filter on the viewer ($1)");
        }
    }
}
