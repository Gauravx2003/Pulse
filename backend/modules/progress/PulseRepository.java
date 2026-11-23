package com.pulse.modules.progress;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads of the weekly pulse (task 7.3, spec insight/progress-pulse): answers are visible only to the
 * person, their manager and all.view, so every read takes a {@link Scope} of the
 * {@link PolicyService#DOMAIN_PULSE pulse} domain and refuses a scope made for another domain. The
 * note is free text: never log it. Submitting arrives with task 13.3.
 */
public class PulseRepository extends ScopedRepository
{

    private static final String ANSWER = """
            SELECT w.id, w.person_id AS "personId", w.week_start AS "weekStart", w.mood, w.workload, w.note,
                   w.submitted_at AS "submittedAt"
            FROM weekly_pulse w
            """;

    public PulseRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * A person's answers, oldest week first.
     */
    public Future<JsonArray> forPerson(Scope scope, UUID personId)
    {
        return pulseScope(scope).compose(s -> scopedList(s, "w.person_id",
                ANSWER + " WHERE w.person_id = $1 AND {scope} ORDER BY w.week_start", Tuple.of(personId)));
    }

    /**
     * A person's latest answer, or {@code null}.
     */
    public Future<JsonObject> latest(Scope scope, UUID personId)
    {
        return pulseScope(scope).compose(s -> scopedOne(s, "w.person_id",
                ANSWER + " WHERE w.person_id = $1 AND {scope} ORDER BY w.week_start DESC LIMIT 1", Tuple.of(personId)));
    }

    /**
     * Those of {@code people} whose answers the scope may read, as id strings.
     */
    public Future<Set<String>> visible(Scope scope, List<UUID> people)
    {
        return pulseScope(scope).compose(s -> scopedList(s, "p.id", "SELECT p.id FROM people p WHERE p.id = ANY($1) AND {scope}",
                Tuple.of((Object) people.toArray(UUID[]::new)))).map(rows ->
        {
            Set<String> out = new HashSet<>();

            rows.forEach(row -> out.add(((JsonObject) row).getString("id")));

            return out;
        });
    }

    /**
     * Fails unless {@code scope} was made for the pulse domain, so a wider scope of another domain can
     * never read pulse answers.
     */
    private static Future<Scope> pulseScope(Scope scope)
    {
        if (scope == null || !PolicyService.DOMAIN_PULSE.equals(scope.domain()))
        {
            return Future.failedFuture(new IllegalArgumentException("Weekly pulse reads need a scope of the '"
                    + PolicyService.DOMAIN_PULSE + "' domain"));
        }

        return Future.succeededFuture(scope);
    }
}
