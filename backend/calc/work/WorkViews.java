package com.pulse.calc.work;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The numbers of the Overview and Work items screens (task 8.2, spec work/work-items, reference section
 * 7.1-7.2) that are not sprint metrics: the daily load of the active sprint, utilization by sprint, the
 * overview alert and the share of a release. Ported from the prototype's {@code pSprint} daily load,
 * {@code utilTrendCard}, {@code pOverview} alerts and {@code releaseCard} (app.js ~241-296, ~2022-2031).
 * Pure functions over plain data.
 */
public final class WorkViews
{

    public static final String OVERLOADED = "overloaded";

    public static final String UNDER = "under";

    public static final String ONBOARDING = "onboarding";

    private WorkViews()
    {
    }

    /**
     * Daily load: one row per company working day of the active sprint; before today the hours logged,
     * from today the remaining hours spread over the remaining capacity (0 on a day the person does not
     * work). A leave or visit day is labelled and shows no hours.
     */
    public static JsonArray dailyLoad(List<LocalDate> sprintDays, LocalDate today, Map<String, Double> logged, double remaining,
                                      int capacity, Predicate<LocalDate> working, Set<String> leave, Set<String> visits)
    {
        JsonArray out = new JsonArray();

        double perDay = remaining / Math.max(capacity, 1);

        for (LocalDate day : sprintDays)
        {
            String key = day.toString();

            boolean past = day.isBefore(today);

            String note = leave.contains(key) ? "Leave" : visits.contains(key) ? "Customer visit" : null;

            double hours = past ? logged.getOrDefault(key, 0.0) : working.test(day) ? perDay : 0;

            out.add(new JsonObject()
                    .put("date", key)
                    .put("kind", past ? "logged" : "planned")
                    .put("hours", note == null ? hours : null)
                    .put("note", note)
                    .put("today", day.equals(today)));
        }

        return out;
    }

    /**
     * Utilization by sprint: each sprint's utilization (the active one is a forecast) with its band, and
     * the overall average of the finished sprints, rounded, with its band; {@code overall} is null with no
     * finished sprint.
     */
    public static JsonObject utilizationTrend(JsonArray sprints, JsonObject rules)
    {
        double sum = 0;

        int finished = 0;

        JsonArray banded = new JsonArray();

        for (Object item : sprints)
        {
            JsonObject sprint = (JsonObject) item;

            Double util = Workload.number(sprint, "util");

            banded.add(sprint.copy().put("band", util == null ? null : Workload.band(util, rules)));

            if (!sprint.getBoolean("live", false))
            {
                sum += Workload.orZero(Workload.number(sprint, "util"));

                finished++;
            }
        }

        Integer overall = finished == 0 ? null : (int) Math.round(sum / finished);

        return new JsonObject()
                .put("sprints", banded)
                .put("overall", overall)
                .put("band", overall == null ? null : Workload.band(overall, rules))
                .put("finished", finished);
    }

    /**
     * The Overview alert of the active sprint: On onboarding when on an onboarding plan (utilization is
     * not judged, so no overload alert: the spec wins over the prototype, which checks overload first),
     * otherwise Overloaded above 100% (the days of work beyond capacity, upcoming leave and the biggest To
     * Do issue to move), otherwise Under below the {@code under} rule (the spare days); null otherwise.
     */
    public static JsonObject alert(JsonObject live, JsonObject onboarding, List<String> upcomingLeave, JsonObject biggestToDo,
                                   JsonObject rules)
    {
        int utilization = live.getInteger("util");

        double work = live.getDouble("work");

        int capacity = live.getInteger("cap");

        // A joiner on onboarding is not judged on utilization: no overload alert (spec work/work-items).
        if (onboarding != null)
        {
            return new JsonObject().put("kind", ONBOARDING).put("plan", onboarding);
        }

        if (utilization > 100)
        {
            return new JsonObject()
                    .put("kind", OVERLOADED)
                    .put("band", live.getJsonArray("band"))
                    .put("critical", utilization > rules.getDouble("over", 120.0))
                    .put("workDays", work)
                    .put("capacity", capacity)
                    .put("daysOver", work - capacity)
                    .put("leave", new JsonArray(List.copyOf(upcomingLeave)))
                    .put("suggestMove", biggestToDo);
        }

        if (utilization < rules.getDouble("under", 60.0))
        {
            return new JsonObject()
                    .put("kind", UNDER)
                    .put("band", live.getJsonArray("band"))
                    .put("spareDays", capacity - work);
        }

        return null;
    }

    /**
     * Share of a release: the person's story points done as % of the team's, 0 when the team did none.
     */
    public static int releaseShare(double personDone, double teamDone)
    {
        return teamDone == 0 ? 0 : (int) Math.round(personDone / teamDone * 100);
    }
}
