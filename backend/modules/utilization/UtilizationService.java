package com.pulse.modules.utilization;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.work.TeamFormulas;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Team utilization (task 9.1, spec work/team-utilization): the visible team for a period. The people
 * come from the policy scope; every number comes from the {@code work.teamLive} and
 * {@code work.teamPeriod} formulas, which are given only those people.
 */
public final class UtilizationService
{

    private final ModuleContext ctx;

    private final PolicyService policy;

    private final CalcService calc;

    UtilizationService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();

        this.calc = ctx.calc();
    }

    /**
     * {@code {period, live, team: {name, people}, kpis, people, ...}}: for the active sprint also
     * {@code suggestion} and {@code heatmap}, for a closed period {@code release} and {@code sprints}.
     * Without {@code period} the active sprint, else the current month; a bad key answers 400.
     */
    Future<JsonObject> team(JsonObject user, String periodKey)
    {
        UUID viewer = UUID.fromString(user.getString("id"));

        return policy.scope(user, PolicyService.DOMAIN_PERSON).compose(scope ->
        {
            Future<JsonArray> members = new TeamRepository(ctx.sql()).members(scope, viewer);

            Future<JsonObject> period = resolve(periodKey);

            return Future.all(members, period).compose(all ->
            {
                JsonObject p = period.result();

                boolean live = "sprint".equals(p.getString("kind")) && p.getBoolean("live", false);

                StringBuilder ids = new StringBuilder();

                Set<String> teams = new LinkedHashSet<>();

                for (Object item : members.result())
                {
                    JsonObject member = (JsonObject) item;

                    ids.append(ids.isEmpty() ? "" : ",").append(member.getString("id"));

                    if (member.getString("team") != null)
                    {
                        teams.add(member.getString("team"));
                    }
                }

                JsonObject params = new JsonObject().put("people", ids.toString());

                Future<Object> body = live
                        ? calc.get(TeamFormulas.TEAM_LIVE, params)
                        : calc.get(TeamFormulas.TEAM_PERIOD, params.put("period", p.getString("key")));

                return body.map(result -> (ResultGuard.isNotApplicable(result) ? new JsonObject() : ((JsonObject) result).copy())
                        .put("period", p)
                        .put("live", live)
                        .put("team", new JsonObject()
                                .put("name", String.join(", ", teams))
                                .put("people", members.result().size())));
            });
        });
    }

    private Future<JsonObject> resolve(String key)
    {
        Future<String> chosen = key == null || key.isBlank()
                ? calc.get("core.periods", new JsonObject()).map(periods ->
                {
                    Object active = ((JsonObject) periods).getValue("activeSprint");

                    return active instanceof String s ? s : "m:" + YearMonth.from(calc.clock().today());
                })
                : Future.succeededFuture(key);

        return chosen.compose(k -> calc.get("core.period", new JsonObject().put("period", k)))
                .map(period -> (JsonObject) period)
                .recover(error -> error instanceof IllegalArgumentException
                        ? Future.failedFuture(ApiException.badRequest("invalid_parameter", "Unknown period.").field("period"))
                        : Future.failedFuture(error));
    }
}
