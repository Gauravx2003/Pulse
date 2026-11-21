package com.pulse.calc.work;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.PeriodCalendar;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only descriptor (task 4.11): a second {@code *Formulas} class in {@code calc/work}, added
 * without touching {@code calc/core}, that CalcService must discover. {@code work.probeSprint} is
 * frozen per sprint; its value is the number of times it ran, so a test can tell a snapshot read from
 * a fresh computation.
 */
public final class ProbeFormulas implements FormulaModule
{
    public static final String PROBE_TOPIC = "work.probe";

    public static final AtomicInteger RUNS = new AtomicInteger();

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(
                Formula.of("work.probe", Set.of(), Set.of(PROBE_TOPIC), ProbeFormulas::probe),
                new Formula("work.probeSprint", Set.of("period"), Set.of(PROBE_TOPIC), Set.of(PeriodCalendar.SPRINT),
                        ProbeFormulas::probe));
    }

    private static Future<Object> probe(CalcContext ctx, JsonObject params)
    {
        return Future.succeededFuture(RUNS.incrementAndGet());
    }
}
