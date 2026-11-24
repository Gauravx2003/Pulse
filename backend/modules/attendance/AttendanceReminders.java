package com.pulse.modules.attendance;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * The month-end attendance reminder (job {@code attendance.month-end-reminder}, last day of the month at
 * 10:00; spec time/attendance "Reminders"): when this month is not uploaded yet, every holder of
 * {@code attendance.upload} is told that the HR export is due by the 5th. Safe to run twice: a user who
 * already has this month's notice gets nothing new.
 */
final class AttendanceReminders
{
    static final String UPLOAD_DUE = "attendance.upload_due";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private AttendanceReminders()
    {
    }

    /** Resolves to {@code {month, uploaded, notified}}. */
    static Future<JsonObject> monthEnd(ModuleContext ctx)
    {
        LocalDate today = ctx.calc() == null ? LocalDate.now(PolicyService.ZONE) : ctx.calc().clock().today();

        YearMonth month = YearMonth.from(today);

        AttendanceRepository repo = new AttendanceRepository(ctx.sql());

        // Nothing to do while the attendance tables are not installed (a schema with other modules only).
        return ctx.sql().queryOne("SELECT to_regclass('attendance_uploads') IS NOT NULL AS installed", Tuple.tuple())
                .compose(t -> !t.getBoolean("installed")
                        ? Future.succeededFuture(new JsonObject().put("month", month.toString()).put("notified", 0))
                        : remind(ctx, repo, month));
    }

    private static Future<JsonObject> remind(ModuleContext ctx, AttendanceRepository repo, YearMonth month)
    {
        return repo.uploads().compose(uploads ->
        {
            JsonObject out = new JsonObject().put("month", month.toString());

            if (AttendanceService.uploadedMonths(uploads).contains(month.toString()))
            {
                return Future.succeededFuture(out.put("uploaded", true).put("notified", 0));
            }

            String link = "/imports?type=attendance&month=" + month;

            String text = MONTH.format(month) + " attendance is due after month end — upload the HR export by the 5th";

            return repo.uploaders().compose(users ->
            {
                Future<Integer> chain = Future.succeededFuture(0);

                for (Object o : users)
                {
                    UUID user = UUID.fromString(((JsonObject) o).getString("id"));

                    chain = chain.compose(n -> repo.noticeSent(user, UPLOAD_DUE, link).compose(sent -> sent ? Future.succeededFuture(n)
                            : ctx.notifications().notify(ctx.sql(), new JsonObject().put("userId", user.toString()).put("kind", UPLOAD_DUE)
                                            .put("text", text).put("link", link))
                                    .map(made -> n + (made == null ? 0 : 1))));
                }

                return chain;
            }).map(n -> out.put("uploaded", false).put("notified", n));
        });
    }
}
