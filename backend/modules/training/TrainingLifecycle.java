package com.pulse.modules.training;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;

/**
 * The assignment lifecycle (spec growth/training-mentoring "Lifecycle order"), following the
 * prototype's {@code trEnd} and {@code trStage} (app.js ~1626–1631). Pure.
 *
 * <p>Stages in order: Not started, In training, Assessment, Final review, Trainee sign-off, then
 * Completed or Not completed. The stage follows from the dates and the parts recorded: the outcome is
 * set when the trainee signs off, from the manager's decision.
 */
public final class TrainingLifecycle
{
    public static final String NOT_STARTED = "Not started";

    public static final String IN_TRAINING = "In training";

    public static final String ASSESSMENT = "Assessment";

    public static final String FINAL_REVIEW = "Final review";

    public static final String SIGN_OFF = "Trainee sign-off";

    public static final String COMPLETED = "Completed";

    public static final String NOT_COMPLETED = "Not completed";

    /** About 16 hours a week: above it, assigning warns about the load (spec "Assignment"). */
    static final double WEEKLY_LOAD_WARNING = 16;

    private TrainingLifecycle()
    {
    }

    /** The last day of the training period: the stored end, else start + weeks × 7 − 1. */
    public static LocalDate end(JsonObject assignment, int weeks)
    {
        String end = assignment.getString("endOn");

        return end != null ? LocalDate.parse(end) : LocalDate.parse(assignment.getString("startOn")).plusDays(weeks * 7L - 1);
    }

    public static String stage(JsonObject assignment, int weeks, LocalDate today)
    {
        String outcome = assignment.getString("outcome");

        if ("completed".equals(outcome))
        {
            return COMPLETED;
        }

        if ("not_completed".equals(outcome))
        {
            return NOT_COMPLETED;
        }

        if (LocalDate.parse(assignment.getString("startOn")).isAfter(today))
        {
            return NOT_STARTED;
        }

        if (!today.isAfter(end(assignment, weeks)))
        {
            return IN_TRAINING;
        }

        if (assignment.getValue("assessment") == null)
        {
            return ASSESSMENT;
        }

        JsonObject finals = assignment.getJsonObject("finalReviews", new JsonObject());

        if (finals.getValue("mentor") == null || finals.getValue("manager") == null)
        {
            return FINAL_REVIEW;
        }

        return SIGN_OFF;
    }

    public static boolean finished(String stage)
    {
        return COMPLETED.equals(stage) || NOT_COMPLETED.equals(stage);
    }

    /** A plan version's average hours per week (all module hours over its weeks). */
    public static double weeklyHours(JsonObject plan)
    {
        double hours = 0;

        for (Object module : plan.getJsonArray("modules", new JsonArray()))
        {
            Number h = ((JsonObject) module).getNumber("hours");

            hours += h == null ? 0 : h.doubleValue();
        }

        return hours / Math.max(1, plan.getInteger("weeks", 1));
    }

    /**
     * The feedback items a trainee acknowledges (prototype {@code feedbackFor} without checkpoint
     * reviews, which come with the checkpoint cycles): check-in comments ({@code ci:<period>}),
     * assessments ({@code as:<attempt>}) and the mentor's and manager's final reviews
     * ({@code fm:<attempt>}, {@code fg:<attempt>}), across earlier attempts too.
     */
    public static JsonArray feedbackKeys(JsonObject assignment, JsonArray checkIns)
    {
        JsonArray keys = new JsonArray();

        for (Object item : checkIns)
        {
            JsonObject c = (JsonObject) item;

            if (!c.getString("comment", "").isEmpty() && !"Submitted".equals(c.getString("status")))
            {
                keys.add("ci:" + c.getString("periodStart"));
            }
        }

        JsonArray attempts = new JsonArray();

        for (Object h : assignment.getJsonArray("history", new JsonArray()))
        {
            JsonObject past = (JsonObject) h;

            attempts.add(new JsonObject().put("attempt", past.getInteger("attempt", 1))
                    .put("assessment", past.getValue("assess") != null ? past.getValue("assess") : past.getValue("assessment"))
                    .put("finalReviews", past.getValue("final") != null ? past.getValue("final") : past.getValue("finalReviews")));
        }

        attempts.add(new JsonObject().put("attempt", assignment.getInteger("attempt", 1))
                .put("assessment", assignment.getValue("assessment"))
                .put("finalReviews", assignment.getValue("finalReviews")));

        for (Object item : attempts)
        {
            JsonObject a = (JsonObject) item;

            int attempt = a.getInteger("attempt");

            if (a.getValue("assessment") != null)
            {
                keys.add("as:" + attempt);
            }

            JsonObject finals = a.getValue("finalReviews") instanceof JsonObject f ? f : new JsonObject();

            if (finals.getValue("mentor") != null)
            {
                keys.add("fm:" + attempt);
            }

            if (finals.getValue("manager") != null)
            {
                keys.add("fg:" + attempt);
            }
        }

        return keys;
    }
}
