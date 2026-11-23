package com.pulse.calc.work;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;

/**
 * Time worked in a period (task 8.1; reference section 12 "Closed periods: utilization = hours logged
 * / (working days x 8 h)"), ported from the time part of the prototype's {@code monthStats} (app.js
 * ~430): working days to date and in the whole period, hours logged, active, code-only, leave and
 * weekend days, days worked and utilization. Extra-mile items and points are task 11.1.
 *
 * <p>Input: one fact per calendar day of the period, {@code {date, weekday, holiday, leave, visit,
 * beforeJoining, hours, commits}}: a weekday (Mon-Fri), a company holiday, a leave or absence day, a
 * customer-visit day, a day before joining, the hours logged that day (worklogs, or on-site hours of a
 * visit) and whether the person committed code.
 */
public final class PeriodWork
{

    private PeriodWork()
    {
    }

    /**
     * {@code {work, workAll, logged, active, codeOnly, leave, weekend, daysWorked, cover}}: working days
     * up to today and in the whole period, hours logged, days with work, code-only days (commits, no
     * worklog), leave days, days worked on a weekend, hours / {@code dayHours} and utilization %.
     */
    public static JsonObject stats(JsonArray days, LocalDate today, JsonObject rules)
    {
        double dayHours = rules.getDouble("dayHours", 8.0);

        int work = 0;

        int workAll = 0;

        double logged = 0;

        int active = 0;

        int codeOnly = 0;

        int leave = 0;

        int weekend = 0;

        for (Object item : days)
        {
            JsonObject day = (JsonObject) item;

            LocalDate date = LocalDate.parse(day.getString("date"));

            boolean past = !date.isAfter(today);

            boolean weekday = day.getBoolean("weekday", false);

            boolean holiday = day.getBoolean("holiday", false);

            boolean onLeave = day.getBoolean("leave", false);

            boolean visit = day.getBoolean("visit", false);

            boolean beforeJoining = past && day.getBoolean("beforeJoining", false);

            boolean counted = past && !beforeJoining && !holiday;

            // A holiday, a leave day or a day before joining logs nothing (the prototype's dayInfo).
            boolean open = counted && !onLeave;

            double hours = open ? day.getDouble("hours", 0.0) : 0;

            boolean commits = open && !visit && day.getBoolean("commits", false);

            if (weekday && !holiday && !onLeave && !beforeJoining)
            {
                workAll++;

                if (past)
                {
                    work++;
                }
            }

            if (counted && onLeave)
            {
                leave++;
            }

            boolean weekendWork = open && !weekday && (visit || hours > 0);

            if (weekendWork)
            {
                weekend++;
            }

            logged += hours;

            if ((hours > 0 || commits) && !weekendWork)
            {
                active++;
            }

            if (open && !visit && weekday && date.isBefore(today) && hours == 0 && commits)
            {
                codeOnly++;
            }
        }

        return new JsonObject()
                .put("work", work)
                .put("workAll", workAll)
                .put("logged", logged)
                .put("active", active)
                .put("codeOnly", codeOnly)
                .put("leave", leave)
                .put("weekend", weekend)
                .put("daysWorked", logged / dayHours)
                .put("cover", work == 0 ? 0 : (int) Math.round(logged / (work * dayHours) * 100));
    }
}
