package com.pulse.modules.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.work.WorkViewFormulas;
import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.outbox.OutboxRepository;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.UUID;

/**
 * The Nudge action of the issue drawer (task 8.3, spec work/work-items "Nudge"). A manager who can see
 * the issue sends its assignee a Slack or Teams message about a stale or not-started issue. The
 * message goes to the outbox in the same transaction as the {@code nudges} record and the audit entry.
 * Rate limits: one nudge per issue and sender every {@link #ISSUE_WINDOW_SECONDS}, and at most
 * {@link #SENDER_LIMIT} nudges per sender every {@link #SENDER_WINDOW_SECONDS}; a refused nudge answers
 * 429 {@code nudge_rate_limited} with {@code retryAfter}.
 */
public final class NudgeService
{

    public static final String NUDGED = "work.issue.nudged";

    public static final List<String> AUDIT_EVENTS = List.of(NUDGED);

    /**
     * One nudge per issue and sender a day.
     */
    static final long ISSUE_WINDOW_SECONDS = 24 * 60 * 60;

    /**
     * At most {@link #SENDER_LIMIT} nudges per sender an hour, across issues.
     */
    static final long SENDER_WINDOW_SECONDS = 60 * 60;

    static final int SENDER_LIMIT = 20;

    private static final List<String> NUDGEABLE = List.of("Stale", "Not started");

    private final ModuleContext ctx;

    private final CalcService calc;

    NudgeService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.calc = ctx.calc();
    }

    /**
     * Sends a nudge about the issue {@code key}; resolves to {@code {id, key, recipient, channel,
     * sentAt, retryAfter}}.
     */
    Future<JsonObject> nudge(JsonObject user, String key)
    {
        UUID sender = UUID.fromString(user.getString("id"));

        return ctx.policy().scope(user, PolicyService.DOMAIN_PERSON)
                .compose(scope -> new IssueRepository(ctx.sql()).byKey(scope, key))
                .compose(issue ->
                {
                    if (issue == null)
                    {
                        return Future.failedFuture(ApiException.notFound("not_found", "Issue not found."));
                    }

                    if (issue.getString("assigneeId") == null)
                    {
                        return Future.failedFuture(ApiException.unprocessable("no_assignee",
                                key + " has no assignee to nudge."));
                    }

                    UUID issueId = UUID.fromString(issue.getString("id"));

                    UUID assigneeId = UUID.fromString(issue.getString("assigneeId"));

                    NudgeRepository nudges = new NudgeRepository(ctx.sql());

                    Future<Object> enabled = calc.get(WorkViewFormulas.NUDGE_ENABLED, new JsonObject());

                    Future<Object> signals = calc.get(WorkloadFormulas.ISSUE, new JsonObject().put("issue", issueId.toString()));

                    Future<String> channel = nudges.chatChannel(calc.clock().today());

                    Future<JsonObject> recipient = nudges.recipient(assigneeId);

                    return Future.all(enabled, signals, channel, recipient).compose(all ->
                    {
                        ApiException refused = refusal(user, key, issue, enabled.result(), (JsonObject) signals.result(),
                                channel.result(), recipient.result());

                        if (refused != null)
                        {
                            return Future.failedFuture(refused);
                        }

                        return send(user, sender, issue, issueId, assigneeId, recipient.result(), channel.result());
                    });
                });
    }

    /**
     * The nudge part of the drawer: {@code {enabled, channel, nudgeable, count, lastSentAt, retryAfter}};
     * {@code retryAfter} is this sender's wait for this issue (null: may nudge now).
     */
    Future<JsonObject> state(JsonObject user, UUID issueId, JsonArray flags)
    {
        UUID sender = UUID.fromString(user.getString("id"));

        NudgeRepository nudges = new NudgeRepository(ctx.sql());

        Future<Object> enabled = calc.get(WorkViewFormulas.NUDGE_ENABLED, new JsonObject());

        Future<String> channel = nudges.chatChannel(calc.clock().today());

        Future<JsonObject> summary = nudges.summary(issueId);

        Future<Long> retry = nudges.issueRetryAfter(issueId, sender, ISSUE_WINDOW_SECONDS);

        return Future.all(enabled, channel, summary, retry).map(all -> new JsonObject()
                .put("enabled", Boolean.TRUE.equals(enabled.result()))
                .put("channel", channel.result())
                .put("nudgeable", nudgeable(flags))
                .put("count", summary.result().getLong("count"))
                .put("lastSentAt", summary.result().getValue("lastSentAt"))
                .put("retryAfter", retry.result()));
    }

    private ApiException refusal(JsonObject user, String key, JsonObject issue, Object enabled, JsonObject signals,
                                 String channel, JsonObject recipient)
    {
        if (!Boolean.TRUE.equals(enabled))
        {
            return ApiException.unprocessable("nudge_disabled", "Nudges are turned off in Rules & thresholds.");
        }

        if (recipient != null && user.getString("id").equals(recipient.getString("userId")))
        {
            return ApiException.unprocessable("self_action_refused", "You can't nudge yourself.");
        }

        if ("Done".equals(issue.getString("status")) || !nudgeable(signals.getJsonArray("flags", new JsonArray())))
        {
            return ApiException.unprocessable("not_stale", "Only stale or not-started issues can be nudged; " + key + " is not.");
        }

        if (channel == null)
        {
            return ApiException.conflict("chat_not_connected", "Connect Slack or Teams in Integrations to send nudges.");
        }

        if (recipient == null || recipient.getString("email") == null)
        {
            return ApiException.unprocessable("no_recipient_address", "The assignee has no address to nudge.");
        }

        return null;
    }

    private Future<JsonObject> send(JsonObject user, UUID sender, JsonObject issue, UUID issueId, UUID assigneeId,
                                    JsonObject recipient, String channel)
    {
        String key = issue.getString("key");

        String name = recipient.getString("name");

        return ctx.sql().inTransaction(tx ->
        {
            NudgeRepository nudges = new NudgeRepository(tx);

            return nudges.lockSender(sender)
                    .compose(v -> nudges.issueRetryAfter(issueId, sender, ISSUE_WINDOW_SECONDS))
                    .compose(wait ->
                    {
                        if (wait != null)
                        {
                            return Future.failedFuture(ApiException.tooManyRequests("nudge_rate_limited",
                                    "You already nudged " + name + " about " + key + ". Try again in " + hours(wait) + ".")
                                    .retryAfter(wait));
                        }

                        return nudges.senderRetryAfter(sender, SENDER_WINDOW_SECONDS, SENDER_LIMIT);
                    })
                    .compose(wait ->
                    {
                        if (wait != null)
                        {
                            return Future.failedFuture(ApiException.tooManyRequests("nudge_rate_limited",
                                    "You have sent " + SENDER_LIMIT + " nudges in the last hour. Try again in " + hours(wait) + ".")
                                    .retryAfter(wait));
                        }

                        return nudges.insert(issueId, sender, assigneeId, channel);
                    })
                    .compose(row ->
                    {
                        String body = user.getString("name") + " is checking on " + key + " (" + issue.getString("summary")
                                + "): it has had no recent activity. Please update it or log your time in Jira.";

                        JsonObject payload = new JsonObject()
                                .put("nudgeId", row.getString("id"))
                                .put("issueKey", key)
                                .put("link", "/work/issues/" + key);

                        // TODO(5.3): also post the in-app "stale-work nudge" notification once the notifications service exists.
                        return new OutboxRepository(tx).enqueue(channel, recipient.getString("email"), "Nudge: " + key, body, payload)
                                .compose(v -> AuditService.record(tx, AuditService.entry(user, NUDGED, "issue", issueId.toString(),
                                        new JsonObject()
                                                .put("key", key)
                                                .put("recipientId", assigneeId.toString())
                                                .put("channel", channel))))
                                .map(v -> new JsonObject()
                                        .put("id", row.getString("id"))
                                        .put("key", key)
                                        .put("recipient", name)
                                        .put("channel", channel)
                                        .put("sentAt", row.getValue("sentAt"))
                                        .put("retryAfter", ISSUE_WINDOW_SECONDS));
                    });
        });
    }

    private static boolean nudgeable(JsonArray flags)
    {
        return flags.stream().anyMatch(f -> f instanceof JsonArray flag && NUDGEABLE.contains(flag.getString(1)));
    }

    private static String hours(long seconds)
    {
        long hours = (seconds + 3599) / 3600;

        return hours <= 1 ? "an hour" : hours + " hours";
    }
}
