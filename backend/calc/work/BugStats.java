package com.pulse.calc.work;

import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.List;

/**
 * Bugs and support of one person (task 8.1, used by Overview and task 12.1), ported from the
 * prototype's {@code qStats} (app.js ~812): bugs attributed to their code (open, high severity, this
 * quarter, reached production), others' bugs they fixed and how many proactively, and their support
 * tickets in the period with hours and CSAT.
 *
 * <p>A bug is {@code {key, originPersonId, status, severity, environment, reportedOn, fixedById,
 * proactive}}; a ticket is {@code {key, personId, openedOn, hours, csat}}.
 */
public final class BugStats
{

    private BugStats()
    {
    }

    /**
     * Counts {@code {mine, open, high, q3, prod, fixedOthers, proactive, sup, supM}}, support hours in
     * the period {@code supH} and the average CSAT of rated tickets {@code csat} (null when none).
     */
    public static JsonObject of(String personId, List<JsonObject> bugs, List<JsonObject> tickets, LocalDate quarterStart,
                                LocalDate periodFrom, LocalDate periodTo)
    {
        int mine = 0;

        int open = 0;

        int high = 0;

        int quarter = 0;

        int production = 0;

        int fixedOthers = 0;

        int proactive = 0;

        for (JsonObject bug : bugs)
        {
            boolean resolved = "Resolved".equals(bug.getString("status"));

            if (personId.equals(bug.getString("originPersonId")))
            {
                mine++;

                if (!resolved)
                {
                    open++;

                    if ("High".equals(bug.getString("severity")))
                    {
                        high++;
                    }
                }

                if (!LocalDate.parse(bug.getString("reportedOn")).isBefore(quarterStart))
                {
                    quarter++;

                    if ("Production".equals(bug.getString("environment")))
                    {
                        production++;
                    }
                }
            }
            else if (personId.equals(bug.getString("fixedById")) && resolved)
            {
                fixedOthers++;

                if (bug.getBoolean("proactive", false))
                {
                    proactive++;
                }
            }
        }

        int support = 0;

        int inPeriod = 0;

        double hours = 0;

        double csatSum = 0;

        int rated = 0;

        for (JsonObject ticket : tickets)
        {
            if (!personId.equals(ticket.getString("personId")))
            {
                continue;
            }

            support++;

            LocalDate opened = LocalDate.parse(ticket.getString("openedOn"));

            if (!opened.isBefore(periodFrom) && !opened.isAfter(periodTo))
            {
                inPeriod++;

                hours += Workload.orZero(Workload.number(ticket, "hours"));
            }

            Double csat = Workload.number(ticket, "csat");

            if (csat != null && csat != 0)
            {
                csatSum += csat;

                rated++;
            }
        }

        return new JsonObject()
                .put("mine", mine)
                .put("open", open)
                .put("high", high)
                .put("q3", quarter)
                .put("prod", production)
                .put("fixedOthers", fixedOthers)
                .put("proactive", proactive)
                .put("sup", support)
                .put("supM", inPeriod)
                .put("supH", hours)
                .put("csat", rated == 0 ? null : csatSum / rated);
    }
}
