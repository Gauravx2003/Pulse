package com.pulse.modules.effort;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.work.EffortFormulas;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.policy.Scope;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.UUID;

/**
 * Confirm and reject of extra-mile items (task 11.2, spec work/effort-extra-mile "Confirmation"). Only a
 * holder of {@code effort.confirm} who can see the person decides; nobody confirms their own items
 * (separation of duties, 422 {@code self_action_refused}); only finished work is confirmed; a decided
 * item stays decided. Each decision is audited in its transaction and then drops the calculation cache
 * of {@link EffortFormulas#EXTRA_ITEMS} on every instance, so the culture bonus moves on the next read.
 * Points, the monthly caps and the 0.3 bonus cap are the formulas' ({@code work.extraMileItems},
 * {@code core.culture}).
 */
public final class EffortService
{

    public static final String CONFIRMED = "confirmed";

    public static final String REJECTED = "rejected";

    private static final String PENDING = "pending";

    private final ModuleContext ctx;

    private final PolicyService policy;

    public EffortService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();
    }

    /**
     * Confirms or rejects one item on the version the client saw.
     *
     * @param request {@code {sourceIp, requestId}} for the refused audit entry ({@link PolicyService#request})
     */
    public Future<JsonObject> decide(JsonObject user, UUID id, String decision, int version, JsonObject request)
    {
        return policy.scope(user, PolicyService.DOMAIN_PERSON)
                .compose(scope -> ctx.sql().inTransaction(tx -> new ExtraItemRepository(tx).lockVisible(scope, id)
                        .compose(item ->
                        {
                            if (item == null)
                            {
                                return Future.failedFuture(ApiException.notFound("not_found", "Item not found."));
                            }

                            Future<Void> self = CONFIRMED.equals(decision)
                                    ? policy.refuseSelf(user, AuditEvents.EFFORT_CONFIRM, item.getString("personId"), null, request)
                                    : Future.succeededFuture();

                            return self.compose(v ->
                            {
                                ApiException refused = refusal(item, decision);

                                return refused != null ? Future.failedFuture(refused) : record(tx, user, item, decision, version);
                            });
                        })))
                .onSuccess(item -> changed());
    }

    /**
     * "Confirm all done items": confirms each listed item that is visible, pending, done and not the
     * caller's own, in one transaction; the others are skipped with a reason
     * ({@code not_found}, {@code own}, {@code decided}, {@code not_done}). Resolves to
     * {@code {confirmed: [ids], skipped: [{id, reason}]}}.
     */
    public Future<JsonObject> confirmAll(JsonObject user, List<UUID> ids)
    {
        String own = user.getString("personId");

        return policy.scope(user, PolicyService.DOMAIN_PERSON)
                .compose(scope -> ctx.sql().inTransaction(tx ->
                {
                    JsonObject result = new JsonObject().put("confirmed", new JsonArray()).put("skipped", new JsonArray());

                    Future<Void> chain = Future.succeededFuture();

                    for (UUID id : ids.stream().distinct().toList())
                    {
                        chain = chain.compose(v -> confirmOne(tx, scope, user, own, id, result));
                    }

                    return chain.map(v -> result);
                }))
                .onSuccess(result ->
                {
                    if (!result.getJsonArray("confirmed").isEmpty())
                    {
                        changed();
                    }
                });
    }

    private Future<Void> confirmOne(SqlExecutor tx, Scope scope, JsonObject user, String own, UUID id, JsonObject result)
    {
        return new ExtraItemRepository(tx).lockVisible(scope, id).compose(item ->
        {
            String reason = item == null ? "not_found"
                    : item.getString("personId").equals(own) ? "own"
                    : !PENDING.equals(item.getString("state")) ? "decided"
                    : !item.getBoolean("isDone", false) ? "not_done"
                    : null;

            if (reason != null)
            {
                result.getJsonArray("skipped").add(new JsonObject().put("id", id.toString()).put("reason", reason));

                return Future.succeededFuture();
            }

            return record(tx, user, item, CONFIRMED, item.getInteger("version")).map(saved ->
            {
                result.getJsonArray("confirmed").add(id.toString());

                return null;
            });
        });
    }

    private static ApiException refusal(JsonObject item, String decision)
    {
        if (!PENDING.equals(item.getString("state")))
        {
            return ApiException.unprocessable("already_decided", "This item was already " + item.getString("state") + ".");
        }

        if (CONFIRMED.equals(decision) && !item.getBoolean("isDone", false))
        {
            return ApiException.unprocessable("not_done", "Only finished work can be confirmed; this item is waiting for Done.");
        }

        return null;
    }

    private static Future<JsonObject> record(SqlExecutor tx, JsonObject user, JsonObject item, String decision, int version)
    {
        UUID id = UUID.fromString(item.getString("id"));

        return new ExtraItemRepository(tx).decide(id, decision, UUID.fromString(user.getString("id")), version)
                .compose(saved -> AuditService.record(tx, AuditService.entry(user,
                                CONFIRMED.equals(decision) ? AuditEvents.EFFORT_CONFIRM : AuditEvents.EFFORT_REJECT,
                                "extra_item", id.toString(), new JsonObject()
                                        .put("personId", item.getString("personId"))
                                        .put("kind", item.getString("kind"))
                                        .put("ref", item.getString("ref"))))
                        .map(v -> saved));
    }

    /**
     * Drops the cached effort and culture results here and on every other instance.
     */
    private void changed()
    {
        ctx.calc().invalidate(EffortFormulas.EXTRA_ITEMS);

        CalcService.publish(ctx.vertx(), EffortFormulas.EXTRA_ITEMS);
    }
}
