package com.pulse.modules.reviews;

import com.pulse.platform.audit.AuditService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.outbox.OutboxRepository;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.UUID;

/**
 * Kudos (spec culture/one-on-ones-kudos, task 20.2): a holder of oneonone recognises a colleague for
 * one culture value. Kudos are evidence, never score. Self-kudos are refused; a copy can go to Slack
 * or Teams through the outbox.
 */
final class KudosService
{
    static final String GIVEN = "kudos.given";

    /** The 11 culture values (reference spec section 12); V330 checks the same list. */
    static final List<String> CULTURE_VALUES = List.of("Outcome Ownership", "Moral & Ethics", "Decision Making",
            "Problem Solving", "Risk Appetite", "Adaptability", "Agility in Execution", "Collaboration", "Resilience",
            "Resourcefulness", "Discipline");

    /** Where the Slack or Teams copy is posted until per-workspace channels are configurable. */
    static final String CHAT_CHANNEL = "#kudos";

    private final ModuleContext ctx;

    KudosService(ModuleContext ctx)
    {
        this.ctx = ctx;
    }

    Future<JsonObject> give(JsonObject user, JsonObject body)
    {
        UUID personId = UUID.fromString(body.getString("personId"));

        String value = body.getString("value");

        String message = body.getString("message") == null ? "" : body.getString("message").strip();

        String shareTo = body.getString("shareTo", "none");

        if (message.isEmpty())
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Say what they did.").field("message"));
        }

        return new KudosRepository(ctx.sql()).recipient(personId).compose(recipient ->
        {
            if (recipient == null)
            {
                return Future.failedFuture(PolicyService.notFound());
            }

            if (user.getString("id").equals(recipient.getString("userId")))
            {
                return Future.failedFuture(ApiException.unprocessable("self_kudos_refused", "You can't give kudos to yourself."));
            }

            return ctx.sql().inTransaction(tx -> new KudosRepository(tx)
                    .insert(personId, UUID.fromString(user.getString("id")), value, message, shareTo)
                    .compose(row ->
                    {
                        Future<Void> copy = "none".equals(shareTo)
                                ? Future.succeededFuture()
                                : new OutboxRepository(tx).enqueue(shareTo, CHAT_CHANNEL, "Kudos",
                                        "Kudos to " + recipient.getString("name") + " for " + value + ": " + message
                                                + " — " + user.getString("name"),
                                        new JsonObject().put("kudosId", row.getString("id")).put("value", value));

                        return copy
                                .compose(v -> AuditService.record(tx, AuditService.entry(user, GIVEN, "kudos", row.getString("id"),
                                        new JsonObject().put("personId", personId.toString()).put("value", value)
                                                .put("sharedTo", shareTo))))
                                .map(v -> row.put("personId", personId.toString()).put("personName", recipient.getString("name"))
                                        .put("value", value).put("message", message).put("sharedTo", shareTo));
                    }));
        });
    }

    /** The feed of visible people's kudos, newest first. */
    Future<JsonObject> feed(JsonObject user, MultiMap params)
    {
        return ctx.policy().scope(user, PolicyService.DOMAIN_REVIEWS)
                .compose(scope -> new KudosRepository(ctx.sql()).feed(scope, params));
    }
}
