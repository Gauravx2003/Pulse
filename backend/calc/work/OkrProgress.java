package com.pulse.calc.work;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * OKR progress, status and the OKR pillar (spec growth/okrs, reference section 9.1), ported from the
 * prototype's {@code krProg}, {@code okrProg}, {@code okrState} and {@code okrScore} (app.js ~579-583).
 * Pure functions: plain numbers in, plain numbers out; {@link OkrFormulas} loads the rows.
 */
public final class OkrProgress
{

    /**
     * Objective progress at or above this is On track (and is the "0.7 = on track" of the pillar).
     */
    public static final double ON_TRACK = 0.7;

    /**
     * Objective progress at or above this, and below {@link #ON_TRACK}, is At risk; below it Off track.
     */
    public static final double AT_RISK = 0.4;

    /**
     * The OKR pillar is capped at this score.
     */
    public static final double PILLAR_MAX = 5;

    public static final String STATUS_ON_TRACK = "On track";

    public static final String STATUS_AT_RISK = "At risk";

    public static final String STATUS_OFF_TRACK = "Off track";

    private OkrProgress()
    {
    }

    /**
     * {@code krProg}: (current - start) / (target - start), clamped to 0-1. Targets may fall
     * (latency 900 to 400 ms). With target equal to start it is 1 when current reached the target.
     */
    public static double keyResult(double start, double target, double current)
    {
        double span = target - start;

        if (span == 0)
        {
            return current == target ? 1 : 0;
        }

        return Math.max(0, Math.min(1, (current - start) / span));
    }

    /**
     * {@code okrProg}: the average of the key results' progress, 0 for an objective without key results.
     */
    public static double objective(List<Double> keyResults)
    {
        if (keyResults.isEmpty())
        {
            return 0;
        }

        double sum = 0;

        for (double progress : keyResults)
        {
            sum += progress;
        }

        return sum / keyResults.size();
    }

    /**
     * {@code okrState}: On track at 70% or more, At risk at 40-69%, Off track below 40%.
     */
    public static String status(double progress)
    {
        if (progress >= ON_TRACK)
        {
            return STATUS_ON_TRACK;
        }

        return progress >= AT_RISK ? STATUS_AT_RISK : STATUS_OFF_TRACK;
    }

    /**
     * {@code okrScore}: 1 + average objective progress / 0.7 x 3, capped at 5, over a person's Individual
     * objectives of the quarter; {@code null} when there are none, so the pillar falls back to the
     * manager's rating (core {@code okrP}).
     */
    public static Double pillar(List<Double> objectives)
    {
        if (objectives.isEmpty())
        {
            return null;
        }

        return Math.min(PILLAR_MAX, 1 + objective(objectives) / ON_TRACK * 3);
    }

    /**
     * Progress of one objective as stored ({@code key_results} with {@code start_value},
     * {@code target_value}, {@code current_value}): {@code {id, progress, status, keyResults: [{id,
     * progress, status, achieved}]}}.
     */
    public static JsonObject detail(JsonObject objective)
    {
        JsonArray keyResults = new JsonArray();

        List<Double> values = new ArrayList<>();

        for (Object item : objective.getJsonArray("key_results", new JsonArray()))
        {
            JsonObject kr = (JsonObject) item;

            double progress = keyResult(number(kr, "start_value"), number(kr, "target_value"), number(kr, "current_value"));

            values.add(progress);

            keyResults.add(new JsonObject()
                    .put("id", kr.getString("id"))
                    .put("progress", progress)
                    .put("status", status(progress))
                    .put("achieved", progress >= 1));
        }

        double progress = objective(values);

        return new JsonObject()
                .put("id", objective.getString("id"))
                .put("progress", progress)
                .put("status", status(progress))
                .put("keyResults", keyResults);
    }

    /**
     * The KPI row of an OKR list (screen detail "OKRs"): objective count, average progress and its
     * status, key results and how many are achieved, and every objective's {@link #detail}.
     * {@code avgProgress} and {@code status} are null for an empty list.
     */
    public static JsonObject summary(JsonArray objectives)
    {
        JsonArray details = new JsonArray();

        List<Double> progress = new ArrayList<>();

        int keyResults = 0;

        int achieved = 0;

        for (Object item : objectives)
        {
            JsonObject detail = detail((JsonObject) item);

            details.add(detail);

            progress.add(detail.getDouble("progress"));

            for (Object kr : detail.getJsonArray("keyResults"))
            {
                keyResults++;

                if (((JsonObject) kr).getBoolean("achieved"))
                {
                    achieved++;
                }
            }
        }

        Double average = progress.isEmpty() ? null : objective(progress);

        return new JsonObject()
                .put("objectives", details.size())
                .put("avgProgress", average)
                .put("status", average == null ? null : status(average))
                .put("keyResults", keyResults)
                .put("achieved", achieved)
                .put("items", details);
    }

    private static double number(JsonObject row, String key)
    {
        Object value = row.getValue(key);

        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
    }
}
