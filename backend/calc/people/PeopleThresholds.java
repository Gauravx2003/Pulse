package com.pulse.calc.people;

import io.vertx.core.json.JsonObject;

/**
 * Track 3's fixed thresholds that are not Admin-editable rules (task 23.6; design.md decision 5: no
 * threshold constant outside {@code calc/}). Modules read them through {@code people.thresholds}; the
 * new-joiner months shared by every track are {@code core.thresholds}.
 */
public final class PeopleThresholds
{

    /** About this many training hours a week: above it, assigning a plan warns about the load (spec training "Assignment"). */
    public static final double TRAINING_WEEKLY_HOURS = 16;

    private PeopleThresholds()
    {
    }

    /**
     * The {@code people.thresholds} result.
     *
     * @return {@code {trainingWeeklyHours}}
     */
    public static JsonObject json()
    {
        return new JsonObject().put("trainingWeeklyHours", TRAINING_WEEKLY_HOURS);
    }
}
