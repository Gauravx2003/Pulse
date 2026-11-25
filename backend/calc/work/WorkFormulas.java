package com.pulse.calc.work;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.WorkingDays;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * The {@code calc/work} formula module (task 4.11), owned by Track 2: workload and utilization, stale
 * and sprint metrics, PR standard, effort and extra-mile, bugs and support stats and the OKR pillar
 * (design.md decision 5). {@link CalcService} discovers this class; add formulas to {@link #formulas()}
 * (or more {@code *Formulas} classes in this package) without editing {@code calc/core}.
 *
 * <p>Names start with {@code work.}. The core formulas read these input ports when they exist (see
 * docs/calc.md): {@code work.okrPillar}, {@code work.cultureInputs}, {@code work.extraMileItems} and
 * {@code work.disciplineInputs}.
 *
 * <p>Example: {@code work.workingDays {period}}, the company working days of a period (weekends and
 * holidays left out), counted to today for a live period.
 */
public final class WorkFormulas implements FormulaModule
{
    /** Holidays changed. */
    public static final String HOLIDAYS = "work.holidays";

    /** {@code work.thresholds {}}: Track 2's fixed thresholds and tone bands ({@link WorkThresholds#json}). */
    public static final String THRESHOLDS = "work.thresholds";

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(
                Formula.of("work.workingDays", Set.of("period"), Set.of(HOLIDAYS, CalcService.importTopic("holidays")),
                        WorkFormulas::workingDays),
                Formula.of(THRESHOLDS, Set.of(), Set.of(), (ctx, params) -> Future.succeededFuture((Object) WorkThresholds.json(ctx.rules()))));
    }

    private static Future<Object> workingDays(CalcContext ctx, JsonObject params)
    {
        Future<Object> period = ctx.get("core.period", params);

        Future<JsonArray> holidays = ctx.sql().queryList(
                "SELECT holiday_on AS date, name, location FROM holidays");

        return Future.all(period, holidays).map(all ->
        {
            JsonObject p = (JsonObject) period.result();

            LocalDate from = LocalDate.parse(p.getString("from"));
            LocalDate to = LocalDate.parse(p.getString("to"));

            LocalDate last = to.isAfter(ctx.today()) ? ctx.today() : to;

            return last.isBefore(from) ? 0 : WorkingDays.count(from, last, WorkingDays.holidays(holidays.result(), null), null);
        });
    }
}
