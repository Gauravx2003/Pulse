package com.pulse.calc.work;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.CultureParameters;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Road map formulas of {@code calc/work} (task 15.2): {@code work.promotionReadiness {person}} is
 * {@code {target, criteria, ready}} with the target level from {@code promotion_targets} (null when none
 * is set) and the criteria of {@link PromotionReadiness}. The index comes from {@code core.index} (the
 * current quarter) and {@code core.indexHistory}, the culture values from {@code core.culture} this
 * quarter, and the number of engineers mentored from Track 3's optional port
 * {@code people.menteeCount {person}} (an integer; Open until it exists).
 */
public final class RoadmapFormulas implements FormulaModule
{

    public static final String READINESS = "work.promotionReadiness";

    /**
     * Milestones, promotion targets or the skills matrix changed.
     */
    public static final String ROADMAP = "work.roadmap";

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(Formula.of(READINESS, Set.of("person"), Set.of(ROADMAP, CalcService.PERFORMANCE, CalcService.PEOPLE),
                WorkloadFormulas.safe(RoadmapFormulas::readiness)));
    }

    private static Future<Object> readiness(CalcContext ctx, JsonObject params)
    {
        UUID person = WorkloadFormulas.uuid(params, "person");

        LocalDate today = ctx.today();

        String quarter = "q:" + today.getYear() + "-Q" + ((today.getMonthValue() - 1) / 3 + 1);

        JsonObject withQuarter = new JsonObject().put("person", person.toString()).put("period", quarter);

        Future<JsonObject> target = WorkSql.row(ctx, """
                SELECT target_level AS level, target_title AS title, to_char(target_on, 'YYYY-MM-DD') AS "targetOn",
                       cross_team_epic_key AS "epicKey"
                FROM promotion_targets WHERE person_id = $1""", Tuple.of(person));

        Future<Object> current = ctx.optional("core.index", withQuarter);

        Future<Object> history = ctx.optional("core.indexHistory", new JsonObject().put("person", person.toString()));

        Future<Object> culture = ctx.optional("core.culture", withQuarter);

        Future<Object> mentees = ctx.optional("people.menteeCount", new JsonObject().put("person", person.toString()));

        return Future.all(target, current, history, culture, mentees).map(all ->
        {
            List<Double> indexes = new ArrayList<>();

            if (history.result() instanceof JsonArray past)
            {
                past.forEach(v -> indexes.add(v instanceof Number n ? n.doubleValue() : null));
            }

            indexes.add(current.result() instanceof Number n ? n.doubleValue() : null);

            JsonObject t = target.result();

            JsonArray criteria = PromotionReadiness.criteria(indexes, t == null ? null : t.getString("epicKey"),
                    mentees.result() instanceof Number n ? n.intValue() : null,
                    culture.result() instanceof JsonArray c ? c : null, CultureParameters.VALUES);

            boolean ready = criteria.stream().allMatch(c -> ((JsonObject) c).getBoolean("met"));

            return (Object) new JsonObject()
                    .put("target", t == null ? null : new JsonObject()
                            .put("level", t.getString("level"))
                            .put("title", t.getString("title"))
                            .put("targetOn", t.getString("targetOn")))
                    .put("criteria", criteria)
                    .put("ready", ready);
        });
    }
}
