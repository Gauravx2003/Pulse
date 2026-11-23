package com.pulse.modules.progress;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.Ids;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.JobSpec;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import java.util.List;
import java.util.UUID;

/**
 * Track 2 Progress and weekly pulse module (task group 13, spec insight/progress-pulse). Its table
 * {@code weekly_pulse} is part of the Track 2 data layer ({@code work} module, V200); it seeds the
 * prototype's pulse answers in {@code seed/S230__demo_weekly_pulse.sql} (task 7.2).
 *
 * <p>Under {@code /api/v1/progress} (task 13.3): {@code GET /people/:personId} (Progress),
 * {@code GET /people/:personId/pulse} (answers: the person, their manager and all.view only),
 * {@code POST /pulse} (this week's answer of the signed-in person) and {@code GET /wins} (Team wins).
 * The Friday prompt is a scheduled job ({@value #PROMPT_JOB}) run by the task 5.5 scheduler.
 */
public class ProgressModule implements PulseModule
{

    /**
     * The Friday weekly-pulse prompt.
     */
    public static final String PROMPT_JOB = "progress.friday-pulse";

    /**
     * Fridays at 16:00 (minute hour day-of-month month day-of-week).
     */
    public static final String PROMPT_SCHEDULE = "0 16 * * 5";

    private static final String[] VIEW = {"self.view", "team.view", "all.view"};

    static final BodySchema PULSE = BodySchema.object()
            .integer("mood", 1, 5)
            .integer("workload", 1, 5)
            .string("note", ProgressService.NOTE_MAX);

    @Override
    public String id()
    {
        return "progress";
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        PolicyService policy = ctx.policy();

        ProgressService progress = new ProgressService(ctx);

        // GET /api/v1/progress/people/:personId
        router.get("/people/:personId").handler(policy.requiresAny(VIEW)).handler(rc ->
                progress.progress(PolicyService.user(rc), Ids.path(rc, "personId"))
                        .onSuccess(rc::json)
                        .onFailure(rc::fail));

        // GET /api/v1/progress/people/:personId/pulse
        router.get("/people/:personId/pulse").handler(policy.requiresAny(VIEW)).handler(rc ->
                progress.pulse(PolicyService.user(rc), Ids.path(rc, "personId"))
                        .onSuccess(rc::json)
                        .onFailure(rc::fail));

        // POST /api/v1/progress/pulse  {"mood": 4, "workload": 3, "note": "…"}
        router.post("/pulse").handler(policy.requiresAny(VIEW)).handler(PULSE.handler()).handler(rc ->
                progress.submit(PolicyService.user(rc), BodySchema.body(rc))
                        .onSuccess(saved ->
                        {
                            rc.response().setStatusCode(201);

                            rc.json(saved);
                        })
                        .onFailure(rc::fail));

        // GET /api/v1/progress/wins
        router.get("/wins").handler(policy.requiresAny(VIEW)).handler(rc ->
                progress.wins(PolicyService.user(rc))
                        .onSuccess(rc::json)
                        .onFailure(rc::fail));
    }

    @Override
    public List<ActionProvider> actionProviders()
    {
        return List.of(new PulseActionProvider());
    }

    /**
     * The Friday prompt (task 13.3) and the Monday digest, run by the task 5.5 scheduler.
     * TODO(13.x): Monday — send each manager the weekly digest by email and Slack (the preview is
     * {@code GET /action_center/digest}); it still resolves to {@code {pending}}.
     */
    @Override
    public List<JobSpec> jobs()
    {
        return List.of(
                JobSpec.of(PROMPT_JOB, PROMPT_SCHEDULE, "Asks everyone without an answer for the weekly pulse",
                        ctx -> new ProgressService(ctx).prompt().map(n -> new JsonObject().put("prompted", n))),
                JobSpec.of("progress.monday-digest", "0 9 * * 1", "Sends the weekly digest",
                        ctx -> Future.succeededFuture(new JsonObject().put("pending", "weekly digest delivery"))));
    }

    @Override
    public Future<Void> erasePersonalData(SqlExecutor tx, JsonObject subject)
    {
        String personId = subject.getString("personId");

        return personId == null ? Future.succeededFuture() : new PulseRepository(tx).eraseNotes(UUID.fromString(personId));
    }
}
