package com.pulse.modules.attendance;

import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Attendance under {@code /api/v1/attendance} (task 17.1), following docs/backend-guide.md. Uploads go
 * through the shared import pipeline ({@code /api/v1/imports/attendance}, {@link AttendanceImportType}).
 *
 * <pre>
 * GET /periods                              months (flagged not uploaded), sprints, quarters, default
 * GET /team?period=m:2026-08                Team tab: KPIs, people, day-by-day grid, uploads, banner
 * GET /people/:personId?period=m:2026-08    Person tab: KPIs, calendar, leave, late check-ins, months, culture
 * GET /uploads                              monthly uploads
 * GET /reminders                            the viewer's attendance Action Center items
 * </pre>
 */
public final class AttendanceRoutes
{
    private static final String[] ANYONE = {"self.view", "team.view", "all.view"};

    private final ModuleContext ctx;

    private final PolicyService policy;

    private final AttendanceService attendance;

    AttendanceRoutes(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();

        this.attendance = new AttendanceService(ctx);
    }

    void mount(Router router)
    {
        router.get("/periods").handler(policy.requiresAny(ANYONE))
                .handler(rc -> attendance.periods(PolicyService.user(rc)).onSuccess(rc::json).onFailure(rc::fail));

        router.get("/team").handler(policy.requiresAny(ANYONE)).handler(this::team);

        router.get("/people/:personId").handler(policy.requiresAny(ANYONE)).handler(this::person);

        router.get("/uploads").handler(policy.requiresAny(ANYONE))
                .handler(rc -> new AttendanceRepository(ctx.sql()).uploads()
                        .onSuccess(items -> rc.json(new JsonObject().put("items", items)))
                        .onFailure(rc::fail));

        router.get("/reminders").handler(policy.requiresAny(ANYONE)).handler(this::reminders);
    }

    private void team(RoutingContext rc)
    {
        JsonObject user = PolicyService.user(rc);

        withPeriod(rc, user).compose(period -> attendance.team(user, period)).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void person(RoutingContext rc)
    {
        JsonObject user = PolicyService.user(rc);

        String personId = rc.pathParam("personId");

        if (!isUuid(personId))
        {
            rc.fail(PolicyService.notFound());

            return;
        }

        withPeriod(rc, user).compose(period -> attendance.person(user, personId, period)).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void reminders(RoutingContext rc)
    {
        JsonObject user = PolicyService.user(rc);

        List<Future<List<JsonObject>>> parts = new ArrayList<>();

        for (ActionProvider provider : AttendanceActionProvider.all())
        {
            parts.add(provider.actionsFor(user, ctx));
        }

        Future.all(parts).map(all ->
                {
                    JsonArray items = new JsonArray();

                    for (int i = 0; i < parts.size(); i++)
                    {
                        all.<List<JsonObject>>resultAt(i).forEach(items::add);
                    }

                    return new JsonObject().put("items", items);
                })
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    /** The {@code period} query parameter, or the default period (the last uploaded month). */
    private Future<String> withPeriod(RoutingContext rc, JsonObject user)
    {
        String period = rc.queryParams().get("period");

        if (period != null && !period.isBlank())
        {
            if (period.length() > 40)
            {
                return Future.failedFuture(ApiException.badRequest("invalid_parameter", "Unknown period.").field("period"));
            }

            return Future.succeededFuture(period);
        }

        return attendance.periods(user).map(p -> p.getString("default"));
    }

    private static boolean isUuid(String value)
    {
        try
        {
            java.util.UUID.fromString(value);

            return value.length() == 36;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }
}
