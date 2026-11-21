package com.pulse.modules.reviews;

import com.pulse.platform.audit.AuditService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 1:1s (spec culture/one-on-ones-kudos, task 20.2): shared notes and action items that both
 * participants edit and only the two of them and HR (all.view) can read.
 *
 * <p>Reading goes through the policy service ({@link PolicyService#DOMAIN_REVIEWS}): the person sees
 * their own, a manager their direct reports', HR everyone's; a peer gets 403. Editing additionally
 * needs to be one of the two participants, so HR can read but not change the notes.
 */
final class OneOnOneService
{
    static final String CREATED = "oneonone.created";

    static final String NOTES_UPDATED = "oneonone.notes_updated";

    static final String ACTION_SAVED = "oneonone.action_saved";

    private final ModuleContext ctx;

    private final PolicyService policy;

    OneOnOneService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();
    }

    /** A person's 1:1s with notes and action items, newest first. */
    Future<JsonArray> forPerson(JsonObject user, String personId)
    {
        return policy.requireVisible(user, personId, PolicyService.DOMAIN_REVIEWS)
                .compose(scope -> new OneOnOneRepository(ctx.sql()).forPerson(scope, UUID.fromString(personId)));
    }

    /** Plans a 1:1 between the caller and a person they can see (not themselves). */
    Future<JsonObject> create(JsonObject user, JsonObject body)
    {
        String personId = body.getString("personId");

        if (personId.equals(user.getString("personId")))
        {
            return Future.failedFuture(ApiException.unprocessable("one_on_one_with_self", "You can't hold a 1:1 with yourself."));
        }

        UUID managerId = UUID.fromString(user.getString("id"));

        return policy.requireVisible(user, personId, PolicyService.DOMAIN_REVIEWS)
                .compose(scope -> ctx.sql().inTransaction(tx ->
                {
                    OneOnOneRepository repo = new OneOnOneRepository(tx);

                    return repo.insert(UUID.fromString(personId), managerId, LocalDate.parse(body.getString("heldOn")),
                                    body.getJsonArray("agenda", new JsonArray()), managerId)
                            .compose(row -> AuditService.record(tx, AuditService.entry(user, CREATED, "one_on_one",
                                            row.getString("id"), new JsonObject().put("personId", personId)))
                                    .compose(v -> repo.byId(scope, UUID.fromString(row.getString("id")))));
                }));
    }

    /** Saves the shared notes; a stale {@code version} answers 409 so two editors never overwrite each other. */
    Future<JsonObject> saveNotes(JsonObject user, UUID id, String notes, int expectedVersion)
    {
        return participantMeeting(user, id).compose(meeting -> ctx.sql().inTransaction(tx ->
        {
            OneOnOneRepository repo = new OneOnOneRepository(tx);

            return repo.updateNotes(id, notes, expectedVersion)
                    .compose(saved -> AuditService.record(tx, AuditService.entry(user, NOTES_UPDATED, "one_on_one",
                            id.toString(), new JsonObject().put("personId", meeting.getString("personId"))
                                    .put("length", notes.length()))))
                    .compose(v -> policy.scope(user, PolicyService.DOMAIN_REVIEWS))
                    .compose(scope -> repo.byId(scope, id));
        }));
    }

    /** Adds an action item to a 1:1; the owner, when given, must be one of the two participants. */
    Future<JsonObject> addAction(JsonObject user, UUID oneOnOneId, JsonObject body)
    {
        return participantMeeting(user, oneOnOneId).compose(meeting ->
        {
            String text = requireText(body.getString("text"));

            UUID owner = ownerOf(meeting, body.getString("ownerId"));

            return ctx.sql().inTransaction(tx -> new OneOnOneRepository(tx)
                    .insertAction(oneOnOneId, UUID.fromString(meeting.getString("personId")), text, owner, dueOn(body))
                    .compose(row -> AuditService.record(tx, AuditService.entry(user, ACTION_SAVED, "one_on_one_action",
                                    row.getString("id"), new JsonObject().put("oneOnOneId", oneOnOneId.toString())))
                            .map(row)));
        });
    }

    /** Edits an action item (text, owner, due, done) with optimistic locking. */
    Future<JsonObject> updateAction(JsonObject user, UUID actionId, JsonObject body, int expectedVersion)
    {
        return policy.scope(user, PolicyService.DOMAIN_REVIEWS)
                .compose(scope -> new OneOnOneRepository(ctx.sql()).actionById(scope, actionId))
                .compose(action ->
                {
                    if (action == null)
                    {
                        return Future.failedFuture(PolicyService.notFound());
                    }

                    if (!isParticipant(user, action))
                    {
                        return Future.failedFuture(PolicyService.noAccess());
                    }

                    String text = requireText(body.getString("text"));

                    UUID owner = ownerOf(action, body.getString("ownerId"));

                    return ctx.sql().inTransaction(tx -> new OneOnOneRepository(tx)
                            .updateAction(actionId, text, owner, dueOn(body), body.getBoolean("done"), expectedVersion)
                            .compose(row -> AuditService.record(tx, AuditService.entry(user, ACTION_SAVED, "one_on_one_action",
                                            actionId.toString(), new JsonObject().put("done", body.getBoolean("done"))))
                                    .map(row)));
                });
    }

    /** The 1:1 when the caller can see it (else 404) and is one of its two participants (else 403). */
    private Future<JsonObject> participantMeeting(JsonObject user, UUID id)
    {
        return policy.scope(user, PolicyService.DOMAIN_REVIEWS)
                .compose(scope -> new OneOnOneRepository(ctx.sql()).byId(scope, id))
                .compose(meeting ->
                {
                    if (meeting == null)
                    {
                        return Future.failedFuture(PolicyService.notFound());
                    }

                    return isParticipant(user, meeting)
                            ? Future.succeededFuture(meeting)
                            : Future.failedFuture(PolicyService.noAccess());
                });
    }

    /** The person (through their user) or the manager of the 1:1. */
    private static boolean isParticipant(JsonObject user, JsonObject meeting)
    {
        String userId = user.getString("id");

        return userId.equals(meeting.getString("managerId")) || userId.equals(meeting.getString("personUserId"));
    }

    private static UUID ownerOf(JsonObject meeting, String ownerId)
    {
        if (ownerId == null)
        {
            return null;
        }

        if (!ownerId.equals(meeting.getString("managerId")) && !ownerId.equals(meeting.getString("personUserId")))
        {
            throw ApiException.badRequest("invalid_field", "The owner must be one of the two people in the 1:1.").field("ownerId");
        }

        return UUID.fromString(ownerId);
    }

    private static String requireText(String text)
    {
        if (text == null || text.isBlank())
        {
            throw ApiException.badRequest("invalid_field", "Write the action item.").field("text");
        }

        return text.strip();
    }

    private static LocalDate dueOn(JsonObject body)
    {
        String due = body.getString("dueOn");

        return due == null ? null : LocalDate.parse(due);
    }
}
