package com.pulse.calc.people;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.CultureParameters;
import com.pulse.calc.core.CultureScore;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.PeriodCalendar;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The traceable detail of one culture value (task 19.1, spec culture/culture-values "Traceable detail
 * view"): {@code people.cultureDetail {person, period (quarter), value}} is {@link CultureScore#detail}
 * fed with the same inputs as {@code core.culture} (task 4.4): the combined 360° rating and the per-group
 * ratings of {@code culture_ratings}, {@code core.cultureData} for the value, the extra-mile bonus of
 * {@code work.extraMileItems} and the Discipline score. Applying {@code min(5, rating360 × (1 − share) +
 * dataScore × share + bonus)} to its {@code parts} gives its {@code score}, which equals the value's score
 * in {@code core.culture}. Null when the person has no 360° rating for the quarter.
 */
public final class CultureDetailFormulas implements FormulaModule
{
    @Override
    public String id()
    {
        return "people";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(Formula.of("people.cultureDetail", Set.of("person", "period", "value"), Set.of(CalcService.PERFORMANCE),
                CultureDetailFormulas::detail));
    }

    private static Future<Object> detail(CalcContext ctx, JsonObject params)
    {
        String value = params.getString("value");

        if (!CultureParameters.VALUES.contains(value))
        {
            return Future.failedFuture(new IllegalArgumentException("Unknown culture value: " + value));
        }

        UUID person = UUID.fromString(params.getString("person"));

        return ctx.calendar().compose(cal ->
        {
            JsonObject period = cal.period(params.getString("period"));

            if (!PeriodCalendar.QUARTER.equals(period.getString("kind")))
            {
                return Future.failedFuture(new IllegalArgumentException("period: culture values are quarterly."));
            }

            String quarter = CalcService.snapshotKey(period).substring(2);

            JsonObject pp = new JsonObject().put("person", params.getString("person")).put("period", params.getString("period"));

            Future<JsonArray> ratings = ctx.sql().queryList("""
                    SELECT rater_group AS "group", rating FROM culture_ratings WHERE person_id = $1 AND quarter = $2 AND value_name = $3
                    """, Tuple.of(person, quarter, value));

            Future<Object> data = ctx.get("core.cultureData", pp);

            Future<Object> items = ctx.optional("work.extraMileItems", pp);

            Future<Object> discipline = ctx.get("core.discipline", new JsonObject().put("person", params.getString("person")));

            return Future.all(ratings, data, items, discipline).map(all ->
            {
                Double combined = null;

                JsonObject groups = new JsonObject();

                for (Object o : ratings.result())
                {
                    JsonObject r = (JsonObject) o;

                    if ("Combined".equals(r.getString("group")))
                    {
                        combined = r.getDouble("rating");
                    }
                    else
                    {
                        groups.put(r.getString("group"), r.getDouble("rating"));
                    }
                }

                if (combined == null)
                {
                    return null;
                }

                JsonObject valData = null;

                for (Object o : (JsonArray) data.result())
                {
                    if (value.equals(((JsonObject) o).getString("value")))
                    {
                        valData = (JsonObject) o;
                    }
                }

                JsonObject rules = ctx.rules().copy();

                rules.put("officeDays", rules.getJsonObject("att", new JsonObject()).getValue("officeDays", 3));

                JsonArray extraItems = items.result() instanceof JsonArray a ? a : new JsonArray();

                int i = CultureParameters.VALUES.indexOf(value);

                double bonus = CultureScore.valueBonus(extraItems, rules).getDouble(i);

                JsonArray crediting = new JsonArray();

                for (Object o : extraItems)
                {
                    JsonObject it = (JsonObject) o;

                    if ("confirmed".equals(it.getString("state")) && it.getJsonArray("values", new JsonArray()).contains(value))
                    {
                        crediting.add(it);
                    }
                }

                double disc = ((JsonObject) discipline.result()).getDouble("score");

                return (Object) CultureScore.detail(value, combined, groups, valData, bonus, disc, crediting, rules);
            });
        });
    }
}
