package com.pulse.modules.training;

import com.pulse.calc.core.WorkingDays;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.notify.NotificationTriggers;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The daily "assessment due" reminder (job {@code training.assessment-due}, 09:00; spec
 * growth/training-mentoring "Notifications"): once a plan's training period has ended without an
 * assessment, its mentor and the trainee's manager are told, with the due date (the end of the period plus
 * the assignment's assessment window in working days, prototype {@code trAssessDue}). Safe to run twice:
 * a recipient who already has the notice for that assignment gets nothing new.
 */
final class TrainingReminders
{
    private TrainingReminders()
    {
    }

    /** Resolves to {@code {due, notified}}: assignments in the Assessment stage and notices sent. */
    static Future<JsonObject> assessmentDue(ModuleContext ctx)
    {
        LocalDate today = ctx.calc() == null ? LocalDate.now(PolicyService.ZONE) : ctx.calc().clock().today();

        AssignmentRepository repo = new AssignmentRepository(ctx.sql());

        // Nothing to do while the training tables are not installed (a schema with other modules only).
        return ctx.sql().queryOne("SELECT to_regclass('training_assignments') IS NOT NULL AS installed", Tuple.tuple())
                .compose(t -> !t.getBoolean("installed") ? Future.succeededFuture(new JsonObject().put("due", 0).put("notified", 0))
                        : remind(ctx, repo, today));
    }

    private static Future<JsonObject> remind(ModuleContext ctx, AssignmentRepository repo, LocalDate today)
    {
        return Future.all(repo.awaitingAssessment(), repo.holidays()).compose(all ->
        {
            Set<LocalDate> holidays = WorkingDays.holidays(all.resultAt(1), null);

            List<JsonObject> due = new ArrayList<>();

            for (Object o : all.<JsonArray>resultAt(0))
            {
                JsonObject a = (JsonObject) o;

                if (TrainingLifecycle.ASSESSMENT.equals(TrainingLifecycle.stage(a, a.getInteger("weeks"), today)))
                {
                    due.add(a);
                }
            }

            Future<Integer> chain = Future.succeededFuture(0);

            for (JsonObject a : due)
            {
                LocalDate by = addWorkingDays(TrainingLifecycle.end(a, a.getInteger("weeks")), a.getInteger("assessmentWindowDays", 3), holidays);

                chain = chain.compose(n -> repo.managerOf(UUID.fromString(a.getString("personId"))).compose(manager ->
                {
                    Set<String> recipients = new LinkedHashSet<>();

                    if (a.getString("mentorId") != null)
                    {
                        recipients.add(a.getString("mentorId"));
                    }

                    if (manager != null)
                    {
                        recipients.add(manager);
                    }

                    Future<Integer> sent = Future.succeededFuture(n);

                    for (String user : recipients)
                    {
                        JsonObject notice = NotificationTriggers.assessmentDue(user, a.getString("personId"), a.getString("personName"),
                                a.getString("planName"), by, a.getString("id"));

                        sent = sent.compose(k -> repo.noticeSent(user, notice.getString("kind"), notice.getString("link"))
                                .compose(already -> already ? Future.succeededFuture(k)
                                        : ctx.notifications().notify(ctx.sql(), notice).map(made -> k + (made == null ? 0 : 1))));
                    }

                    return sent;
                }));
            }

            return chain.map(n -> new JsonObject().put("due", due.size()).put("notified", n));
        });
    }

    /** The day {@code n} working days after {@code from} (weekends and holidays skipped). */
    static LocalDate addWorkingDays(LocalDate from, int n, Set<LocalDate> holidays)
    {
        LocalDate d = from;

        int left = n;

        while (left > 0)
        {
            d = d.plusDays(1);

            if (WorkingDays.isWorking(d, holidays, null))
            {
                left--;
            }
        }

        return d;
    }
}
