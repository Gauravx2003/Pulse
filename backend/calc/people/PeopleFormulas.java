package com.pulse.calc.people;

import com.pulse.calc.core.Appraisal;
import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code calc/people} formula module (task 4.11), owned by Track 3: visit points and visit stats,
 * 360° results aggregation (anonymity merge, hidden strengths, blind spots), and the attendance,
 * training and review inputs of the core formulas (design.md decision 5). {@link CalcService}
 * discovers this class; add formulas to {@link #formulas()} (or more {@code *Formulas} classes in this
 * package) without editing {@code calc/core}.
 *
 * <p>Names start with {@code people.}. The core formulas read these input ports when they exist (see
 * docs/calc.md): {@code people.cultureInputs}, {@code people.attendanceDiscipline},
 * {@code people.disciplineOverride}, {@code people.trainingPlans} and {@code people.reviewCycles}.
 *
 * <p>Example: {@code people.serviceMonths {person}}, whole months of service from the joining date to
 * today (the appraisal eligibility count); null without a joining date.
 */
public final class PeopleFormulas implements FormulaModule
{
    /** {@code people.thresholds {}}: Track 3's fixed thresholds ({@link PeopleThresholds#json}). */
    public static final String THRESHOLDS = "people.thresholds";

    @Override
    public String id()
    {
        return "people";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(
                Formula.of("people.serviceMonths", Set.of("person"), Set.of(CalcService.PEOPLE), PeopleFormulas::serviceMonths),
                Formula.of(THRESHOLDS, Set.of(), Set.of(), (ctx, params) -> Future.succeededFuture((Object) PeopleThresholds.json())));
    }

    private static Future<Object> serviceMonths(CalcContext ctx, JsonObject params)
    {
        return ctx.sql().queryOne("SELECT joined_on AS \"joinedOn\" FROM people WHERE id = $1",
                        Tuple.of(UUID.fromString(params.getString("person"))))
                .map(row -> row == null || row.getString("joinedOn") == null ? null
                        : (Object) Appraisal.monthsBetween(LocalDate.parse(row.getString("joinedOn")), ctx.today()));
    }
}
