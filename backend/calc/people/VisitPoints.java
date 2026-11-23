package com.pulse.calc.people;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Customer-visit points, values and stats (spec time/customer-visits "Verification and points",
 * reference spec section 8.1; task 18.1). Pure: plain data in, plain data out. Verified against the
 * prototype's {@code visitPts} (app.js ~1288), {@code visitValues} and {@code vStats} (~1306), and the
 * monthly cap {@code cp(..., S.rules.visitCap)} (~440).
 *
 * <p>A visit is {@code {from, to, purpose, reportedOn, onsiteHours, travelHours, csat, askAgain,
 * verification}} as the visits module stores it. {@code pts} is the rule {@code pts}
 * ({@code visit, visitDay, visitWeekend, visitEsc, visitCsat}).
 */
public final class VisitPoints
{
    /** Values credited by purpose (reference spec 8.1); weekend or holiday days add Resilience. */
    static final Map<String, List<String>> VALUES_BY_PURPOSE = Map.of(
            "Deployment / installation", List.of("Outcome Ownership", "Adaptability"),
            "Troubleshooting", List.of("Problem Solving", "Outcome Ownership"),
            "Escalation", List.of("Resilience", "Problem Solving", "Outcome Ownership"),
            "Training / workshop", List.of("Collaboration", "Adaptability"),
            "Demo / POC", List.of("Collaboration", "Risk Appetite"),
            "Health check", List.of("Outcome Ownership", "Collaboration"));

    private VisitPoints()
    {
    }

    /** Every day of the visit, first to last. */
    static List<LocalDate> days(JsonObject visit)
    {
        List<LocalDate> days = new ArrayList<>();

        LocalDate to = LocalDate.parse(visit.getString("to"));

        for (LocalDate d = LocalDate.parse(visit.getString("from")); !d.isAfter(to); d = d.plusDays(1))
        {
            days.add(d);
        }

        return days;
    }

    /** Days of the visit on a weekend or an organisation holiday. */
    static int offDays(JsonObject visit, Set<LocalDate> holidays)
    {
        int off = 0;

        for (LocalDate d : days(visit))
        {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(d))
            {
                off++;
            }
        }

        return off;
    }

    /**
     * The points breakdown {@code {parts: [{label, points}], total}}; no points before the report.
     * Spec example: a 3-day escalation with 1 weekend day and CSAT 4.8 is 1.5 + 1.0 + 1 + 0.5 + 1 = 5.
     */
    public static JsonObject points(JsonObject visit, Set<LocalDate> holidays, JsonObject pts)
    {
        JsonArray parts = new JsonArray();

        if (visit.getValue("reportedOn") == null)
        {
            return new JsonObject().put("parts", parts).put("total", 0);
        }

        int days = days(visit).size();

        int off = offDays(visit, holidays);

        add(parts, "Customer visit", number(pts, "visit"));

        if (days > 1)
        {
            add(parts, (days - 1) + " extra day" + (days > 2 ? "s" : "") + " on site", (days - 1) * number(pts, "visitDay"));
        }

        if (off > 0)
        {
            add(parts, off + " weekend / holiday day" + (off > 1 ? "s" : ""), off * number(pts, "visitWeekend"));
        }

        if ("Escalation".equals(visit.getString("purpose")))
        {
            add(parts, "Customer escalation", number(pts, "visitEsc"));
        }

        Number csat = visit.getNumber("csat");

        if (csat != null && csat.doubleValue() >= 4.5)
        {
            add(parts, "Customer rated " + plain(csat) + " / 5", number(pts, "visitCsat"));
        }

        double total = 0;

        for (Object part : parts)
        {
            total += ((JsonObject) part).getDouble("points");
        }

        return new JsonObject().put("parts", parts).put("total", round2(total));
    }

    /** The values a visit credits: by purpose, plus Resilience when it includes a weekend or holiday day. */
    public static JsonArray values(JsonObject visit, Set<LocalDate> holidays)
    {
        List<String> values = new ArrayList<>(VALUES_BY_PURPOSE.getOrDefault(visit.getString("purpose"), List.of("Outcome Ownership")));

        if (offDays(visit, holidays) > 0 && !values.contains("Resilience"))
        {
            values.add("Resilience");
        }

        return new JsonArray(values);
    }

    /**
     * A person's visit stats for the window [from, to] (visits starting in it): visits, reported,
     * planned (not reported and not ended by today, any window), days on site, weekend or holiday days,
     * travel and on-site hours, average CSAT (null without feedback), feedback count, "would ask again"
     * count, points of reported visits not rejected, and confirmed points.
     */
    public static JsonObject stats(JsonArray visits, LocalDate from, LocalDate to, LocalDate today, Set<LocalDate> holidays,
                                   JsonObject pts)
    {
        int all = 0;

        int done = 0;

        int planned = 0;

        int days = 0;

        int off = 0;

        double travel = 0;

        double onsite = 0;

        double csatSum = 0;

        int feedback = 0;

        int again = 0;

        double points = 0;

        double confirmed = 0;

        for (Object item : visits)
        {
            JsonObject v = (JsonObject) item;

            boolean reported = v.getValue("reportedOn") != null;

            if (!reported && !LocalDate.parse(v.getString("to")).isBefore(today))
            {
                planned++;
            }

            LocalDate start = LocalDate.parse(v.getString("from"));

            if (start.isBefore(from) || start.isAfter(to))
            {
                continue;
            }

            all++;

            if (!reported)
            {
                continue;
            }

            done++;

            days += days(v).size();

            off += offDays(v, holidays);

            travel += number(v, "travelHours");

            onsite += number(v, "onsiteHours");

            if (v.getValue("csat") != null)
            {
                feedback++;

                csatSum += v.getNumber("csat").doubleValue();

                again += Boolean.TRUE.equals(v.getBoolean("askAgain")) ? 1 : 0;
            }

            double total = points(v, holidays, pts).getDouble("total");

            if (!"rejected".equals(v.getString("verification")))
            {
                points += total;
            }

            if ("confirmed".equals(v.getString("verification")))
            {
                confirmed += total;
            }
        }

        return new JsonObject()
                .put("visits", all)
                .put("reported", done)
                .put("planned", planned)
                .put("days", days)
                .put("weekendDays", off)
                .put("travelHours", travel)
                .put("onsiteHours", onsite)
                .put("csat", feedback == 0 ? null : csatSum / feedback)
                .put("feedback", feedback)
                .put("askAgain", again)
                .put("points", round2(points))
                .put("pointsConfirmed", round2(confirmed));
    }

    /**
     * Visit points of one month under the monthly cap ({@code visitCap}, 8 by default, separate from
     * the extra-mile cap): confirmed points up to the cap, pending points only in the headroom left,
     * and whether the cap cut anything (prototype {@code cp}).
     */
    public static JsonObject month(JsonArray visits, Set<LocalDate> holidays, JsonObject pts, double cap)
    {
        double confirmed = 0;

        double pending = 0;

        for (Object item : visits)
        {
            JsonObject v = (JsonObject) item;

            if (v.getValue("reportedOn") == null)
            {
                continue;
            }

            double total = points(v, holidays, pts).getDouble("total");

            if ("confirmed".equals(v.getString("verification")))
            {
                confirmed += total;
            }
            else if ("pending".equals(v.getString("verification")))
            {
                pending += total;
            }
        }

        double counted = Math.min(confirmed, cap);

        return new JsonObject()
                .put("confirmed", round2(counted))
                .put("pending", round2(Math.max(0, Math.min(confirmed + pending, cap) - counted)))
                .put("capped", confirmed + pending > cap)
                .put("cap", cap);
    }

    private static void add(JsonArray parts, String label, double points)
    {
        if (points > 0)
        {
            parts.add(new JsonObject().put("label", label).put("points", points));
        }
    }

    private static double number(JsonObject object, String key)
    {
        Number n = object.getNumber(key);

        return n == null ? 0 : n.doubleValue();
    }

    private static double round2(double value)
    {
        return Math.round(value * 100) / 100.0;
    }

    /** 4.8 → "4.8", 5.0 → "5" (as the prototype prints numbers). */
    private static String plain(Number n)
    {
        return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
    }
}
