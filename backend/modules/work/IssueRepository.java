package com.pulse.modules.work;

import com.pulse.platform.db.ListQuery;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Reads of Jira work data (task 7.3): issues, their activity timeline, worklogs and commit days. Every
 * read takes a {@link Scope} from the policy service ({@code person} domain: own, direct reports,
 * all.view), so a peer's work cannot be read. Rows are written only by {@code IngestService} (task 5.4).
 *
 * <p>Issues are read through the view {@code issues_with_assignee} (V290, task 25.1 of
 * pulse-v2-live-connectors): a live issue's assignee is the person linked to its tool account, null
 * while the account is unmatched, so scopes and filters on {@code i.assignee_id} follow the link. The
 * timeline reads {@code issue_events_with_actor} (V292), where a live author is matched the same way.
 */
public class IssueRepository extends ScopedRepository
{

    private static final String ISSUE = """
            SELECT i.id, i.key, i.summary, i.issue_type AS "issueType", i.status, i.assignee_id AS "assigneeId",
                   i.sprint_id AS "sprintId", i.release_id AS "releaseId", i.story_points AS "storyPoints",
                   i.estimate_hours AS "estimateHours", i.spent_hours AS "spentHours",
                   i.remaining_hours AS "remainingHours", i.due_on AS "dueOn", i.started_on AS "startedOn",
                   i.resolved_on AS "resolvedOn", i.carried_over AS "carriedOver", i.reopened,
                   i.was_stale AS "wasStale", i.source, i.external_id AS "externalId", i.synced_at AS "syncedAt",
                   i.html_url AS "htmlUrl"
            FROM issues_with_assignee i
            """;

    public IssueRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * A person's issues, in one sprint when {@code sprintId} is given.
     */
    public Future<JsonArray> forPerson(Scope scope, UUID personId, UUID sprintId)
    {
        return scopedList(scope, "i.assignee_id", ISSUE
                        + " WHERE i.assignee_id = $1 AND ($2::uuid IS NULL OR i.sprint_id = $2) AND {scope}"
                        + " ORDER BY i.key",
                Tuple.tuple().addUUID(personId).addUUID(sprintId));
    }

    /**
     * One issue by key (the most recently synced row when several sources hold it), or {@code null}.
     */
    public Future<JsonObject> byKey(Scope scope, String key)
    {
        return scopedOne(scope, "i.assignee_id", ISSUE
                        + " WHERE i.key = $1 AND {scope} ORDER BY i.synced_at DESC NULLS LAST, i.id LIMIT 1",
                Tuple.of(key));
    }

    /**
     * An issue's activity timeline, newest first; visible when the issue's assignee is.
     */
    public Future<JsonArray> events(Scope scope, UUID issueId)
    {
        return scopedList(scope, "i.assignee_id", """
                SELECT e.id, e.occurred_at AS "occurredAt", e.kind, e.field, e.from_value AS "fromValue",
                       e.to_value AS "toValue", e.actor_id AS "actorId", e.actor_name AS "actorName",
                       e.is_bot AS "isBot", e.summary
                FROM issue_events_with_actor e JOIN issues_with_assignee i ON i.id = e.issue_id
                WHERE e.issue_id = $1 AND {scope}
                ORDER BY e.occurred_at DESC, e.id""", Tuple.of(issueId));
    }

    /**
     * A person's worklogs between two dates (inclusive).
     */
    public Future<JsonArray> worklogs(Scope scope, UUID personId, LocalDate from, LocalDate to)
    {
        return scopedList(scope, "w.person_id", """
                SELECT w.id, w.person_id AS "personId", w.issue_id AS "issueId", w.work_date AS "workDate",
                       w.hours, w.logged_at AS "loggedAt"
                FROM worklogs w
                WHERE w.person_id = $1 AND w.work_date BETWEEN $2 AND $3 AND {scope}
                ORDER BY w.work_date, w.id""", Tuple.of(personId, from, to));
    }

    /**
     * A person's days with commits between two dates (inclusive).
     */
    public Future<JsonArray> commitDays(Scope scope, UUID personId, LocalDate from, LocalDate to)
    {
        return scopedList(scope, "c.person_id", """
                SELECT c.id, c.person_id AS "personId", c.commit_date AS "commitDate",
                       c.commit_count AS "commitCount", c.issue_keys AS "issueKeys",
                       c.first_commit_at AS "firstCommitAt", c.last_commit_at AS "lastCommitAt"
                FROM commit_days c
                WHERE c.person_id = $1 AND c.commit_date BETWEEN $2 AND $3 AND {scope}
                ORDER BY c.commit_date""", Tuple.of(personId, from, to));
    }

    /**
     * Issues of every visible person, for team views: filters {@code assigneeId}, {@code sprintId},
     * {@code status}, {@code source}; search {@code q} on key and summary; sorts {@code key}, {@code due},
     * {@code status}.
     */
    public Future<JsonObject> page(Scope scope, MultiMap params)
    {
        return scopedPage(scope, "i.assignee_id", ListQuery.from(ISSUE)
                .filter("assigneeId", "i.assignee_id")
                .filter("sprintId", "i.sprint_id")
                .filter("status", "i.status")
                .filter("source", "i.source")
                .search("i.key", "i.summary")
                .sort("key", "i.key")
                .sort("due", "i.due_on")
                .sort("status", "i.status")
                .defaultSort("key")
                .tieBreaker("i.id"), params);
    }
}
