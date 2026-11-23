package com.pulse.modules.pull_requests;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Reads of pull requests from TFS and GitHub (task 7.3): PRs by author, reviews and comments, and the
 * reviews a person gave. Every read takes a {@link Scope} from the policy service ({@code person}
 * domain); a PR's reviews and comments are visible when its author is. Rows are written only by
 * {@code IngestService} (task 5.4).
 */
public class PullRequestRepository extends ScopedRepository
{

    private static final String PULL_REQUEST = """
            SELECT p.id, p.number, p.tool, p.repo, p.title, p.suggested_title AS "suggestedTitle",
                   p.author_id AS "authorId", p.work_item_key AS "workItemKey", p.status,
                   p.opened_at AS "openedAt", p.merged_at AS "mergedAt", p.lines_changed AS "linesChanged",
                   p.files_changed AS "filesChanged", p.has_summary AS "hasSummary",
                   p.has_testing AS "hasTesting", p.has_risk AS "hasRisk", p.build_passed AS "buildPassed",
                   p.first_review_hours AS "firstReviewHours", p.url, p.source
            FROM pull_requests p
            """;

    public PullRequestRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * A person's PRs opened between two dates (inclusive); {@code null} dates mean no bound ("All").
     */
    public Future<JsonArray> byAuthor(Scope scope, UUID personId, LocalDate from, LocalDate to)
    {
        return scopedList(scope, "p.author_id", PULL_REQUEST + """
                         WHERE p.author_id = $1
                           AND ($2::date IS NULL OR p.opened_at >= $2::date)
                           AND ($3::date IS NULL OR p.opened_at < $3::date + 1)
                           AND {scope}
                        ORDER BY p.opened_at DESC, p.id""",
                Tuple.tuple().addUUID(personId).addLocalDate(from).addLocalDate(to));
    }

    /**
     * One PR, or {@code null} when it does not exist or its author is not visible.
     */
    public Future<JsonObject> byId(Scope scope, UUID id)
    {
        return scopedOne(scope, "p.author_id", PULL_REQUEST + " WHERE p.id = $1 AND {scope}", Tuple.of(id));
    }

    /**
     * A PR's reviewers and votes.
     */
    public Future<JsonArray> reviews(Scope scope, UUID pullRequestId)
    {
        return scopedList(scope, "p.author_id", """
                SELECT r.id, r.reviewer_id AS "reviewerId", r.vote, r.reviewed_at AS "reviewedAt"
                FROM pr_reviews r JOIN pull_requests p ON p.id = r.pull_request_id
                WHERE r.pull_request_id = $1 AND {scope}
                ORDER BY r.reviewed_at NULLS LAST, r.id""", Tuple.of(pullRequestId));
    }

    /**
     * A PR's review comments, oldest first (their class is computed in {@code calc/work}).
     */
    public Future<JsonArray> comments(Scope scope, UUID pullRequestId)
    {
        return scopedList(scope, "p.author_id", """
                SELECT c.id, c.review_id AS "reviewId", c.author_id AS "authorId", c.body,
                       c.is_resolved AS "isResolved", c.commented_at AS "commentedAt"
                FROM pr_comments c JOIN pull_requests p ON p.id = c.pull_request_id
                WHERE c.pull_request_id = $1 AND {scope}
                ORDER BY c.commented_at, c.id""", Tuple.of(pullRequestId));
    }

    /**
     * Reviews a person gave on PRs opened between two dates, with the PR's size and comment count
     * ("Reviews given", rubber-stamps); visible when the reviewer is.
     */
    public Future<JsonArray> reviewsGiven(Scope scope, UUID reviewerId, LocalDate from, LocalDate to)
    {
        return scopedList(scope, "r.reviewer_id", """
                SELECT r.id, r.pull_request_id AS "pullRequestId", r.vote, r.reviewed_at AS "reviewedAt",
                       p.number, p.title, p.author_id AS "authorId", p.lines_changed AS "linesChanged",
                       p.opened_at AS "openedAt",
                       (SELECT count(*) FROM pr_comments c
                        WHERE c.pull_request_id = p.id AND c.author_id = r.reviewer_id) AS comments
                FROM pr_reviews r JOIN pull_requests p ON p.id = r.pull_request_id
                WHERE r.reviewer_id = $1
                  AND ($2::date IS NULL OR p.opened_at >= $2::date)
                  AND ($3::date IS NULL OR p.opened_at < $3::date + 1)
                  AND {scope}
                ORDER BY p.opened_at DESC, r.id""",
                Tuple.tuple().addUUID(reviewerId).addLocalDate(from).addLocalDate(to));
    }
}
