package com.pulse.modules.progress;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
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
     * Saves the person's answer for the week (one per person and week; a second answer replaces the
     * first): {@code {id, personId, weekStart, mood, workload, note, submittedAt}}. Self-service only: the
     * caller has checked that the person is the signed-in user.
     */
    public Future<JsonObject> upsert(UUID personId, LocalDate weekStart, int mood, int workload, String note)
    {
        return queryOne("""
                INSERT INTO weekly_pulse (person_id, week_start, mood, workload, note)
                VALUES ($1, $2, $3, $4, $5)
                ON CONFLICT (person_id, week_start) DO UPDATE
                    SET mood = EXCLUDED.mood, workload = EXCLUDED.workload, note = EXCLUDED.note,
                        submitted_at = now(), updated_at = now(), version = weekly_pulse.version + 1
                RETURNING id, person_id AS "personId", week_start AS "weekStart", mood, workload, note,
                          submitted_at AS "submittedAt\"""", Tuple.of(personId, weekStart, mood, workload, note));
    }

    /**
     * {@code {name, managerUserId}} of a person: the current manager from the reporting lines, null
     * without one.
     */
    public Future<JsonObject> managerOf(UUID personId)
    {
        return queryOne("""
                SELECT p.name, rl.manager_id AS "managerUserId"
                FROM people p LEFT JOIN reporting_lines rl ON rl.user_id = p.user_id AND rl.valid_to IS NULL
                WHERE p.id = $1 LIMIT 1""", Tuple.of(personId));
    }

    /**
     * {@code [{userId, personId}]}: active Employees with a performance profile and no answer for the week
     * (the Friday prompt, spec platform/jobs-scheduler "Friday pulse").
     */
    public Future<JsonArray> withoutAnswer(LocalDate weekStart)
    {
        return queryList("""
                SELECT u.id AS "userId", p.id AS "personId"
                FROM users u JOIN roles r ON r.id = u.role_id JOIN people p ON p.user_id = u.id
                WHERE u.status = 'Active' AND r.name = 'Employee'
                  AND NOT EXISTS (SELECT 1 FROM weekly_pulse w WHERE w.person_id = p.id AND w.week_start = $1)
                ORDER BY u.id""", Tuple.of(weekStart));
    }

    /**
     * Removes the free-text notes of a deleted user's answers (spec platform/privacy-retention); the
     * scores stay as pseudonymous aggregates. Does nothing where the Track 2 tables are not installed: the
     * table is checked first, as a failed statement would abort the caller's delete transaction.
     */
    public Future<Void> eraseNotes(UUID personId)
    {
        return queryOne("SELECT to_regclass('weekly_pulse') IS NOT NULL AS installed", Tuple.tuple()).compose(t -> !t.getBoolean("installed")
                ? Future.<Void>succeededFuture()
                : execute("UPDATE weekly_pulse SET note = '', updated_at = now() WHERE person_id = $1 AND note <> ''", Tuple.of(personId))
                        .mapEmpty());
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
