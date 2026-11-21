package com.pulse.modules.reviews;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.people.ReviewFormulas;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 360° feedback (spec culture/review-360, task 20.3): cycles, reviewer assignment with derived rater
 * groups, the review form (draft, submit, decline) and progress.
 *
 * <p>Reviewers are stored by pseudonym ({@link Review360Repository}). Nobody gets another reviewer's
 * answers from this service: a reviewer reads only their own request, the manager's assignment view
 * shows names and states but no answers, and a subject sees only counts.
 *
 * <p>Results come from calc/people through CalcService ({@code people.review360Results}), which merges
 * or hides groups under the anonymity threshold; every change publishes {@link ReviewFormulas#REVIEWS}.
 *
 * <p>Not here yet (they depend on other tasks): publish into culture values (4.4 / group 19),
 * notifications and reminders (5.3) and training checkpoints (task 21).
 */
final class Review360Service
{
    static final String CYCLE_CREATED = "review360.cycle_created";

    static final String REQUEST_REMOVED = "review360.request_removed";

    static final String REQUEST_DECLINED = "review360.request_declined";

    static final String REVIEW_SUBMITTED = "review360.review_submitted";

    /** The 10 values rated in a 360° review: Discipline is measured from Jira, not rated. */
    static final List<String> RATED_VALUES = KudosService.CULTURE_VALUES.stream().filter(v -> !"Discipline".equals(v)).toList();

    static final int EXAMPLE_MIN = 10;

    static final int COMMENT_MIN = 15;

    private static final String UNIQUE_VIOLATION = "23505";

    private static final Pattern QUARTER_KEY = Pattern.compile("^([0-9]{4})-Q([1-4])$", Pattern.CASE_INSENSITIVE);

    private static final Pattern QUARTER_LABEL = Pattern.compile("^Q([1-4])\\s+([0-9]{4})$", Pattern.CASE_INSENSITIVE);

    private final ModuleContext ctx;

    private final PolicyService policy;

    Review360Service(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();
    }

    // ------------------------------------------------------------------ cycles

    Future<JsonArray> cycles()
    {
        return new Review360Repository(ctx.sql()).cycles();
    }

    /** Creates a cycle; refused while another one runs (spec "Second cycle"). */
    Future<JsonObject> createCycle(JsonObject user, JsonObject body)
    {
        String quarter = quarterKey(body.getString("quarter"));

        if (quarter == null)
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Use a quarter such as Q3 2026.").field("quarter"));
        }

        if (body.getInteger("internalMin", 3) > body.getInteger("internalMax", 5)
                || body.getInteger("crossMin", 2) > body.getInteger("crossMax", 4))
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "The minimum can't be above the maximum."));
        }

        return ctx.sql().inTransaction(tx ->
        {
            Review360Repository repo = new Review360Repository(tx);

            return repo.runningCycle().compose(running ->
            {
                if (running != null)
                {
                    return Future.failedFuture(cycleRunning(running.getString("name")));
                }

                return repo.insertCycle(body, quarter, UUID.fromString(user.getString("id")))
                        .compose(row -> AuditService.record(tx, AuditService.entry(user, CYCLE_CREATED, "review_cycle",
                                        row.getString("id"), new JsonObject().put("quarter", quarter)))
                                .compose(v -> repo.cycle(UUID.fromString(row.getString("id")))));
            });
        }).recover(error -> error instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState())
                ? Future.failedFuture(cycleRunning("another cycle"))
                : Future.failedFuture(error));
    }

    // ------------------------------------------------------------------ assignment

    /**
     * Assigns reviewers to subjects. Groups are derived: the subject themself → self, their manager →
     * manager, same department → internal, other department → cross. Self and manager reviews are added
     * by default; duplicates, deactivated reviewers and groups already at their maximum are skipped with a
     * reason.
     */
    Future<JsonObject> assign(JsonObject user, UUID cycleId, JsonObject body, JsonObject request)
    {
        Review360Repository repo = new Review360Repository(ctx.sql());

        List<String> subjectIds = strings(body.getJsonArray("subjectIds"));

        List<String> reviewerIds = strings(body.getJsonArray("reviewerIds", new JsonArray()));

        boolean includeSelf = body.getBoolean("includeSelf", true);

        boolean includeManager = body.getBoolean("includeManager", true);

        return runningCycle(repo, cycleId)
                .compose(cycle -> allVisible(user, subjectIds).map(cycle))
                .compose(cycle -> repo.subjects(uuids(subjectIds)).compose(subjects ->
                {
                    Set<String> people = new LinkedHashSet<>(reviewerIds);

                    for (Object s : subjects)
                    {
                        addIfPresent(people, ((JsonObject) s).getString("userId"));

                        addIfPresent(people, ((JsonObject) s).getString("managerId"));
                    }

                    return repo.reviewers(uuids(new ArrayList<>(people)))
                            .compose(reviewers -> plan(user, cycle, subjects, reviewers, reviewerIds, includeSelf, includeManager, request));
                }))
                .compose(plan -> apply(user, cycleId, plan));
    }

    /** Works out every (subject, reviewer, group) to add, and what to skip and why. */
    private Future<JsonObject> plan(JsonObject user, JsonObject cycle, JsonArray subjects, JsonArray reviewers, List<String> explicit,
                                    boolean includeSelf, boolean includeManager, JsonObject request)
    {
        Map<String, JsonObject> byUser = new HashMap<>();

        reviewers.forEach(r -> byUser.put(((JsonObject) r).getString("id"), (JsonObject) r));

        JsonArray add = new JsonArray();

        JsonArray skipped = new JsonArray();

        // Refuse (and audit) assigning oneself as a reviewer of someone else, once.
        Future<Boolean> selfRefused = explicit.contains(user.getString("id"))
                ? policy.refuseSelf(user, AuditEvents.REVIEW360_REVIEWER_ASSIGN, null, user.getString("id"), request)
                        .map(false).otherwise(true)
                : Future.succeededFuture(false);

        return selfRefused.compose(refused ->
        {
            Review360Repository repo = new Review360Repository(ctx.sql());

            Future<Void> chain = Future.succeededFuture();

            for (Object item : subjects)
            {
                JsonObject subject = (JsonObject) item;

                chain = chain.compose(v -> repo.groupCounts(UUID.fromString(cycle.getString("id")), UUID.fromString(subject.getString("id")))
                        .map(counts ->
                        {
                            Map<String, Integer> planned = new HashMap<>();

                            Set<String> reviewersOf = new LinkedHashSet<>();

                            if (includeSelf)
                            {
                                addIfPresent(reviewersOf, subject.getString("userId"));
                            }

                            if (includeManager)
                            {
                                addIfPresent(reviewersOf, subject.getString("managerId"));
                            }

                            reviewersOf.addAll(explicit);

                            for (String reviewerId : reviewersOf)
                            {
                                JsonObject reviewer = byUser.get(reviewerId);

                                String reason = null;

                                String group = null;

                                if (reviewer == null)
                                {
                                    reason = "No such user.";
                                }
                                else if ("Deactivated".equals(reviewer.getString("status")))
                                {
                                    reason = reviewer.getString("name") + " is deactivated.";
                                }
                                else if (refused && reviewerId.equals(user.getString("id")) && !reviewerId.equals(subject.getString("managerId")))
                                {
                                    reason = "You can't assign yourself as a reviewer.";
                                }
                                else
                                {
                                    group = group(subject, reviewer);

                                    int already = counts.getJsonObject(group, new JsonObject()).getInteger("assigned", 0)
                                            + planned.getOrDefault(group, 0);

                                    int max = max(cycle, group);

                                    if (already >= max)
                                    {
                                        reason = label(group) + " already has " + already + " reviewer" + (already == 1 ? "" : "s")
                                                + " (at most " + max + ").";
                                    }
                                }

                                if (reason != null)
                                {
                                    skipped.add(skip(subject, reviewer, reason));

                                    continue;
                                }

                                planned.merge(group, 1, Integer::sum);

                                add.add(new JsonObject()
                                        .put("subjectId", subject.getString("id"))
                                        .put("subjectName", subject.getString("name"))
                                        .put("reviewerId", reviewerId)
                                        .put("reviewerName", reviewer.getString("name"))
                                        .put("group", group));
                            }

                            return null;
                        }));
            }

            return chain.map(v -> new JsonObject().put("add", add).put("skipped", skipped));
        });
    }

    private Future<JsonObject> apply(JsonObject user, UUID cycleId, JsonObject plan)
    {
        JsonArray assigned = new JsonArray();

        JsonArray skipped = plan.getJsonArray("skipped");

        return ctx.sql().inTransaction(tx ->
        {
            Review360Repository repo = new Review360Repository(tx);

            Future<Void> chain = Future.succeededFuture();

            for (Object item : plan.getJsonArray("add"))
            {
                JsonObject a = (JsonObject) item;

                chain = chain.compose(v -> repo.keyFor(cycleId, UUID.fromString(a.getString("reviewerId")))
                        .compose(key -> repo.insertRequest(cycleId, UUID.fromString(a.getString("subjectId")), key, a.getString("group"),
                                UUID.fromString(user.getString("id"))))
                        .compose(requestId ->
                        {
                            if (requestId == null)
                            {
                                skipped.add(new JsonObject().put("subjectName", a.getString("subjectName"))
                                        .put("reviewerName", a.getString("reviewerName")).put("reason", "Already assigned."));

                                return Future.succeededFuture();
                            }

                            assigned.add(a.copy().put("requestId", requestId));

                            // The audit names the request and its group, never the reviewer (spec: not in logs).
                            return AuditService.record(tx, AuditService.entry(user, AuditEvents.REVIEW360_REVIEWER_ASSIGN,
                                    "review_request", requestId, new JsonObject().put("cycleId", cycleId.toString())
                                            .put("subjectId", a.getString("subjectId")).put("group", a.getString("group"))));
                        }));
            }

            // TODO(5.3): notify every new reviewer with a link that opens the form.
            return chain.map(v -> new JsonObject().put("assigned", assigned).put("skipped", skipped));
        }).onSuccess(v -> changed());
    }

    /** The manager's view: reviewers and states per visible subject. */
    Future<JsonArray> assignments(JsonObject user, UUID cycleId)
    {
        return policy.scope(user, PolicyService.DOMAIN_REVIEWS)
                .compose(scope -> new Review360Repository(ctx.sql()).assignments(scope, cycleId));
    }

    /** Removes a request that is not submitted yet. */
    Future<Void> remove(JsonObject user, UUID requestId)
    {
        return policy.scope(user, PolicyService.DOMAIN_REVIEWS)
                .compose(scope -> new Review360Repository(ctx.sql()).visibleRequest(scope, requestId))
                .compose(found ->
                {
                    if (found == null)
                    {
                        return Future.failedFuture(PolicyService.notFound());
                    }

                    if ("submitted".equals(found.getString("status")))
                    {
                        return Future.failedFuture(ApiException.conflict("already_submitted", "A submitted review can't be removed."));
                    }

                    return ctx.sql().inTransaction(tx -> new Review360Repository(tx).deleteUnsubmitted(requestId)
                            .compose(n -> AuditService.record(tx, AuditService.entry(user, REQUEST_REMOVED, "review_request",
                                    requestId.toString(), new JsonObject().put("subjectId", found.getString("subjectId"))
                                            .put("group", found.getString("group"))))));
                })
                .onSuccess(v -> changed());
    }

    // ------------------------------------------------------------------ the reviewer's side

    Future<JsonArray> toGive(JsonObject user)
    {
        Review360Repository repo = new Review360Repository(ctx.sql());

        return repo.keysOf(UUID.fromString(user.getString("id"))).compose(repo::toGive);
    }

    Future<JsonObject> request(JsonObject user, UUID id)
    {
        Review360Repository repo = new Review360Repository(ctx.sql());

        return repo.keysOf(UUID.fromString(user.getString("id")))
                .compose(keys -> repo.ownRequest(id, keys))
                .compose(found -> found == null ? Future.failedFuture(PolicyService.notFound()) : Future.succeededFuture(found));
    }

    /** Saves a draft, or submits when {@code submit} (then the form must be complete). */
    Future<JsonObject> answer(JsonObject user, UUID id, JsonObject body, boolean submit, int expectedVersion)
    {
        return openRequest(user, id).compose(found ->
        {
            JsonObject scores = body.getJsonObject("scores", found.getJsonObject("scores"));

            JsonObject examples = body.getJsonObject("examples", found.getJsonObject("examples"));

            String keep = body.getString("keepDoing", found.getString("keepDoing"));

            String start = body.getString("startDoing", found.getString("startDoing"));

            if (submit)
            {
                ApiException incomplete = incomplete(scores, examples, keep, start);

                if (incomplete != null)
                {
                    return Future.failedFuture(incomplete);
                }
            }

            return ctx.sql().inTransaction(tx -> new Review360Repository(tx)
                    .saveAnswers(id, scores, examples, keep.strip(), start.strip(), submit, expectedVersion)
                    .compose(row -> submit
                            ? AuditService.record(tx, anonymize(AuditService.entry(auditActor(user, found), REVIEW_SUBMITTED, "review_request",
                                    id.toString(), new JsonObject().put("group", found.getString("group"))), found)).map(row)
                            : Future.succeededFuture(row)));
        }).onSuccess(v ->
        {
            if (submit)
            {
                changed();
            }
        }).compose(v -> request(user, id));
    }

    /** Peer requests (internal, cross) may be declined; self and manager reviews may not. */
    Future<JsonObject> decline(JsonObject user, UUID id, int expectedVersion)
    {
        return openRequest(user, id).compose(found ->
        {
            if (!"internal".equals(found.getString("group")) && !"cross".equals(found.getString("group")))
            {
                return Future.failedFuture(ApiException.unprocessable("decline_not_allowed", "Only peer requests can be declined."));
            }

            return ctx.sql().inTransaction(tx -> new Review360Repository(tx).decline(id, expectedVersion)
                    .compose(row -> AuditService.record(tx, anonymize(AuditService.entry(auditActor(user, found), REQUEST_DECLINED, "review_request",
                            id.toString(), new JsonObject().put("group", found.getString("group"))), found)).map(row)));
        }).onSuccess(v -> changed());
    }

    /**
     * The subject's own view of a cycle: response counts per group only. Results open when the cycle is
     * published (spec "Employee before publish"); the results themselves need calc/people.
     */
    Future<JsonObject> myProgress(JsonObject user, UUID cycleId)
    {
        if (user.getString("personId") == null)
        {
            return Future.failedFuture(PolicyService.notFound());
        }

        Review360Repository repo = new Review360Repository(ctx.sql());

        return repo.cycle(cycleId).compose(cycle ->
        {
            if (cycle == null)
            {
                return Future.failedFuture(PolicyService.notFound());
            }

            return repo.groupCounts(cycleId, UUID.fromString(user.getString("personId"))).map(counts -> new JsonObject()
                    .put("cycleId", cycleId.toString())
                    .put("status", cycle.getString("status"))
                    .put("published", "published".equals(cycle.getString("status")))
                    .put("progress", counts));
        });
    }

    /** The subject's results: refused before publish (only counts are shown, see {@link #myProgress}). */
    Future<JsonObject> myResults(JsonObject user, UUID cycleId)
    {
        return myProgress(user, cycleId).compose(progress ->
        {
            if (!progress.getBoolean("published"))
            {
                return Future.failedFuture(ApiException.forbidden("results_not_published",
                        "Results open when the cycle is published. Until then you see how many reviews are in."));
            }

            return results(cycleId, user.getString("personId")).map(results -> results.put("published", true));
        });
    }

    /**
     * Results of a person the manager (or HR) sees, preliminary while the cycle runs (spec "Differencing
     * protection": aggregates of groups at or above the threshold only, no response-level detail).
     */
    Future<JsonObject> resultsFor(JsonObject user, UUID cycleId, String personId)
    {
        return policy.requireVisible(user, personId, PolicyService.DOMAIN_REVIEWS)
                .compose(scope -> new Review360Repository(ctx.sql()).cycle(cycleId))
                .compose(cycle ->
                {
                    if (cycle == null)
                    {
                        return Future.failedFuture(PolicyService.notFound());
                    }

                    boolean published = "published".equals(cycle.getString("status"));

                    return results(cycleId, personId).map(results -> results.put("published", published).put("preliminary", !published));
                });
    }

    /** The anonymity-safe aggregation from calc/people. */
    private Future<JsonObject> results(UUID cycleId, String personId)
    {
        return ctx.calc().getObject("people.review360Results", new JsonObject().put("cycle", cycleId.toString()).put("person", personId))
                .map(results -> results.put("cycleId", cycleId.toString()).put("personId", personId));
    }

    /** After assignments, submissions, declines or removals: cached results are dropped. */
    private void changed()
    {
        CalcService.publish(ctx.vertx(), ReviewFormulas.REVIEWS);
    }

    // ------------------------------------------------------------------ rules

    /**
     * The actor recorded for a reviewer's own action: nobody for the anonymous groups (internal and
     * cross-department), so the audit log never links a reviewer to a subject (spec "Pseudonymous
     * reviewer storage": never to logs). Self and manager reviews are named anyway.
     */
    static JsonObject auditActor(JsonObject user, JsonObject request)
    {
        return named(request) ? user : null;
    }

    /**
     * For anonymous groups also blanks the request id and client IP, which could be matched against
     * request logs to find the reviewer.
     */
    static JsonObject anonymize(JsonObject entry, JsonObject request)
    {
        return named(request) ? entry : entry.put("requestId", "").put("sourceIp", "");
    }

    private static boolean named(JsonObject request)
    {
        String group = request.getString("group");

        return "self".equals(group) || "manager".equals(group);
    }

    /** Every rated value answered (1–5 or Not observed), examples for 1, 2 and 5, both comments long enough. */
    static ApiException incomplete(JsonObject scores, JsonObject examples, String keep, String start)
    {
        for (String value : RATED_VALUES)
        {
            if (!scores.containsKey(value))
            {
                return ApiException.unprocessable("review_incomplete", "Rate " + value + ", or choose Not observed.")
                        .field("scores." + value);
            }

            Integer score = scores.getInteger(value);

            String example = examples.getString(value, "");

            if (score != null && (score == 1 || score == 2 || score == 5) && example.strip().length() < EXAMPLE_MIN)
            {
                return ApiException.unprocessable("review_incomplete", value + " rated " + score
                        + " needs an example of at least " + EXAMPLE_MIN + " characters.").field("examples." + value);
            }
        }

        if (keep == null || keep.strip().length() < COMMENT_MIN)
        {
            return ApiException.unprocessable("review_incomplete", "Keep doing needs at least " + COMMENT_MIN + " characters.")
                    .field("keepDoing");
        }

        if (start == null || start.strip().length() < COMMENT_MIN)
        {
            return ApiException.unprocessable("review_incomplete", "Start or do differently needs at least " + COMMENT_MIN
                    + " characters.").field("startDoing");
        }

        return null;
    }

    /** The reviewer's own request while it can still change (running cycle, not submitted or declined). */
    private Future<JsonObject> openRequest(JsonObject user, UUID id)
    {
        return request(user, id).compose(found ->
        {
            if ("submitted".equals(found.getString("status")))
            {
                return Future.failedFuture(ApiException.conflict("already_submitted", "Submitted reviews are read-only."));
            }

            if ("declined".equals(found.getString("status")))
            {
                return Future.failedFuture(ApiException.conflict("declined", "This request was declined."));
            }

            if (!"running".equals(found.getString("cycleStatus")))
            {
                return Future.failedFuture(ApiException.conflict("cycle_not_running", "This 360° cycle is closed."));
            }

            return Future.succeededFuture(found);
        });
    }

    private Future<JsonObject> runningCycle(Review360Repository repo, UUID cycleId)
    {
        return repo.cycle(cycleId).compose(cycle ->
        {
            if (cycle == null)
            {
                return Future.failedFuture(PolicyService.notFound());
            }

            if (!"running".equals(cycle.getString("status")))
            {
                return Future.failedFuture(ApiException.conflict("cycle_not_running", "Reviewers can only be assigned while the cycle runs."));
            }

            return Future.succeededFuture(cycle);
        });
    }

    /** Every subject must be visible to the assigner (403 otherwise, as the policy service answers). */
    private Future<Void> allVisible(JsonObject user, List<String> subjectIds)
    {
        Future<Void> chain = Future.succeededFuture();

        for (String id : subjectIds)
        {
            chain = chain.compose(v -> policy.requireVisible(user, id, PolicyService.DOMAIN_REVIEWS).mapEmpty());
        }

        return chain;
    }

    /** self · manager · internal (same department) · cross (other department). */
    static String group(JsonObject subject, JsonObject reviewer)
    {
        String reviewerId = reviewer.getString("id");

        if (reviewerId.equals(subject.getString("userId")))
        {
            return "self";
        }

        if (reviewerId.equals(subject.getString("managerId")))
        {
            return "manager";
        }

        String a = subject.getString("department");

        String b = reviewer.getString("department");

        return a != null && b != null && a.strip().equalsIgnoreCase(b.strip()) ? "internal" : "cross";
    }

    private static int max(JsonObject cycle, String group)
    {
        return switch (group)
        {
            case "internal" -> cycle.getInteger("internalMax");
            case "cross" -> cycle.getInteger("crossMax");
            default -> 1;
        };
    }

    private static String label(String group)
    {
        return switch (group)
        {
            case "internal" -> "Internal team";
            case "cross" -> "Cross-department";
            case "manager" -> "Manager review";
            default -> "Self-review";
        };
    }

    private static JsonObject skip(JsonObject subject, JsonObject reviewer, String reason)
    {
        return new JsonObject()
                .put("subjectName", subject.getString("name"))
                .put("reviewerName", reviewer == null ? null : reviewer.getString("name"))
                .put("reason", reason);
    }

    static String quarterKey(String value)
    {
        if (value == null)
        {
            return null;
        }

        Matcher key = QUARTER_KEY.matcher(value.strip());

        if (key.matches())
        {
            return key.group(1) + "-Q" + key.group(2);
        }

        Matcher label = QUARTER_LABEL.matcher(value.strip());

        return label.matches() ? label.group(2) + "-Q" + label.group(1) : null;
    }

    private static ApiException cycleRunning(String name)
    {
        return ApiException.conflict("cycle_running", "A 360° cycle is already running (" + name + "). Close it first.");
    }

    private static List<String> strings(JsonArray array)
    {
        List<String> out = new ArrayList<>();

        if (array != null)
        {
            array.forEach(x -> out.add(String.valueOf(x).toLowerCase(Locale.ROOT)));
        }

        return out;
    }

    private static UUID[] uuids(List<String> ids)
    {
        return ids.stream().map(UUID::fromString).toArray(UUID[]::new);
    }

    private static void addIfPresent(Set<String> set, String value)
    {
        if (value != null)
        {
            set.add(value);
        }
    }
}
