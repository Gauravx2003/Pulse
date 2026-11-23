package com.pulse.calc.work;

import io.vertx.core.json.JsonObject;

/**
 * Bug attribution (task 12.1, spec work/bugs-support "Attribution"): the code owner a bug is traced
 * to, from a Jira "caused by" link or blame on the fixing PR's diff. The link wins when both exist and
 * differ. A learning signal: it never scores by itself.
 */
public final class BugAttribution
{

    public static final String CAUSED_BY_LINK = "caused_by_link";

    public static final String BLAME = "blame";

    private BugAttribution()
    {
    }

    /**
     * {@code {person, method}} for the two results (any id form: people ids or tool account ids), or
     * {@code {person: null, method: null}} when neither is known.
     */
    public static JsonObject resolve(String causedBy, String blame)
    {
        if (causedBy != null && !causedBy.isBlank())
        {
            return new JsonObject().put("person", causedBy).put("method", CAUSED_BY_LINK);
        }

        if (blame != null && !blame.isBlank())
        {
            return new JsonObject().put("person", blame).put("method", BLAME);
        }

        return new JsonObject().putNull("person").putNull("method");
    }
}
