package com.pulse.modules.roadmap;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.CultureParameters;
import com.pulse.calc.work.PromotionReadiness;
import com.pulse.calc.work.RoadmapFormulas;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The road map (task 15.2, spec growth/roadmap): milestones (the person or their manager proposes, the
 * manager confirms), promotion readiness from {@code work.promotionReadiness} and the skills matrix the
 * manager rates. Only the person, their manager and {@code all.view} see a road map; a manager never
 * confirms or rates their own (separation of duties). Each change is audited and drops the road map's
 * cached results.
 */
public final class RoadmapService
{

    public static final String MILESTONE_PROPOSED = "roadmap.milestone.proposed";

    public static final String MILESTONE_CONFIRMED = "roadmap.milestone.confirmed";

    public static final String SKILL_RATED = "roadmap.skill.rated";

    public static final String SKILL_REMOVED = "roadmap.skill.removed";

    public static final List<String> AUDIT_EVENTS = List.of(MILESTONE_PROPOSED, MILESTONE_CONFIRMED, SKILL_RATED, SKILL_REMOVED);

    /**
     * Separation of duties: a manager's own milestone or skills.
     */
    public static final String CONFIRM_ACTION = "roadmap.milestone.confirm";

    public static final String RATE_ACTION = "roadmap.skill.rate";

    private static final String CONFIRMED = "confirmed";

    private final ModuleContext ctx;

    private final PolicyService policy;

    RoadmapService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();
    }

    /**
     * {@code {person, milestones, readiness, skills: {items, empty}, can: {propose, confirm, rate}}}.
     */
    Future<JsonObject> roadmap(JsonObject user, String personId)
    {
        UUID id = UUID.fromString(personId);

        return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON).compose(scope ->
        {
            RoadmapRepository repo = new RoadmapRepository(ctx.sql());

            Future<JsonArray> milestones = repo.milestones(scope, id);

            Future<JsonArray> skills = repo.skills(scope, id);

            Future<Object> readiness = ctx.calc().get(RoadmapFormulas.READINESS, new JsonObject().put("person", personId));

            return Future.all(milestones, skills, readiness).map(all ->
            {
                JsonArray items = new JsonArray();

                for (Object item : skills.result())
                {
                    JsonObject skill = (JsonObject) item;

                    items.add(skill.copy().put("gap", PromotionReadiness.gap(skill.getInteger("current"), skill.getInteger("needed"))));
                }

                boolean manager = manages(user, personId);

                return new JsonObject()
                        .put("person", personId)
                        .put("milestones", milestones.result())
                        .put("readiness", readiness.result())
                        .put("skills", new JsonObject().put("items", items).put("empty", items.isEmpty()))
                        .put("can", new JsonObject()
                                .put("propose", manager || self(user, personId))
                                .put("confirm", manager)
                                .put("rate", manager));
            });
        });
    }

    /**
     * Proposes a milestone for the person (by the person or their manager); it shows Proposed until the
     * manager confirms it.
     */
    Future<JsonObject> propose(JsonObject user, String personId, String title, String when, String value)
    {
        String outcome = title == null ? "" : title.strip();

        String period = when == null ? "" : when.strip();

        if (outcome.isEmpty())
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Describe the outcome.").field("title"));
        }

        if (period.isEmpty())
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Say when.").field("when"));
        }

        if (value != null && !value.isBlank() && !CultureParameters.VALUES.contains(value))
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Choose one of the culture values.").field("value"));
        }

        return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON).compose(scope ->
        {
            if (!self(user, personId) && !manages(user, personId))
            {
                return Future.failedFuture(ApiException.forbidden("no_access", "Only the person or their manager proposes milestones."));
            }

            // TODO(5.3): notify the manager to confirm once NotificationTriggers has a milestone helper; the
            // Action center shows it meanwhile (MilestoneActionProvider).
            return ctx.sql().inTransaction(tx -> new RoadmapRepository(tx)
                    .propose(UUID.fromString(personId), outcome, period, value == null || value.isBlank() ? null : value, userId(user))
                    .compose(saved -> audit(tx, user, MILESTONE_PROPOSED, saved.getString("id"), personId).map(saved)));
        }).onSuccess(saved -> changed());
    }

    /**
     * The manager confirms a proposed milestone on the version they saw.
     */
    Future<JsonObject> confirm(JsonObject user, UUID milestoneId, int version, JsonObject request)
    {
        return policy.scope(user, PolicyService.DOMAIN_PERSON).compose(scope -> ctx.sql().inTransaction(tx ->
        {
            RoadmapRepository repo = new RoadmapRepository(tx);

            return repo.lockMilestone(scope, milestoneId).compose(milestone ->
            {
                if (milestone == null)
                {
                    return Future.failedFuture(ApiException.notFound("not_found", "Milestone not found."));
                }

                String personId = milestone.getString("personId");

                return policy.refuseSelf(user, CONFIRM_ACTION, personId, null, request).compose(v ->
                {
                    if (CONFIRMED.equals(milestone.getString("status")))
                    {
                        return Future.failedFuture(ApiException.unprocessable("already_confirmed", "This milestone is already confirmed."));
                    }

                    return repo.confirm(milestoneId, userId(user), version)
                            .compose(saved -> audit(tx, user, MILESTONE_CONFIRMED, milestoneId.toString(), personId).map(saved));
                });
            });
        })).onSuccess(saved -> changed());
    }

    /**
     * The manager rates a skill against the level guide: current level 0-5 and the level the next role
     * needs 1-5.
     */
    Future<JsonObject> rate(JsonObject user, String personId, String skill, int current, int needed, JsonObject request)
    {
        String name = skill == null ? "" : skill.strip();

        if (name.isEmpty())
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Name the skill.").field("skill"));
        }

        return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON)
                .compose(scope -> policy.refuseSelf(user, RATE_ACTION, personId, null, request))
                .compose(v -> ctx.sql().inTransaction(tx -> new RoadmapRepository(tx)
                        .rate(UUID.fromString(personId), name, current, needed, userId(user))
                        .compose(saved -> audit(tx, user, SKILL_RATED, saved.getString("id"), personId)
                                .map(saved.put("gap", PromotionReadiness.gap(current, needed))))))
                .onSuccess(saved -> changed());
    }

    /**
     * The manager removes a skill from the matrix.
     */
    Future<Void> removeSkill(JsonObject user, String personId, UUID skillId, JsonObject request)
    {
        return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON)
                .compose(scope -> policy.refuseSelf(user, RATE_ACTION, personId, null, request))
                .compose(v -> ctx.sql().inTransaction(tx -> new RoadmapRepository(tx).removeSkill(UUID.fromString(personId), skillId)
                        .compose(n -> n == 0
                                ? Future.<Void>failedFuture(ApiException.notFound("not_found", "Skill not found."))
                                : audit(tx, user, SKILL_REMOVED, skillId.toString(), personId))))
                .onSuccess(v -> changed());
    }

    // ------------------------------------------------------------------ helpers

    private static boolean self(JsonObject user, String personId)
    {
        return personId.equals(user.getString("personId"));
    }

    /**
     * A manager of the person: holds team.view or all.view and is not the person (visibility is checked
     * by the caller).
     */
    private static boolean manages(JsonObject user, String personId)
    {
        Set<String> held = PolicyService.permissions(user);

        return (held.contains("team.view") || held.contains("all.view")) && !self(user, personId);
    }

    private static UUID userId(JsonObject user)
    {
        return UUID.fromString(user.getString("id"));
    }

    private static Future<Void> audit(SqlExecutor tx, JsonObject user, String event, String targetId, String personId)
    {
        return AuditService.record(tx, AuditService.entry(user, event, "roadmap", targetId, new JsonObject().put("personId", personId)));
    }

    private void changed()
    {
        ctx.calc().invalidate(RoadmapFormulas.ROADMAP);

        CalcService.publish(ctx.vertx(), RoadmapFormulas.ROADMAP);
    }
}
