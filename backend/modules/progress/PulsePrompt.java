package com.pulse.modules.progress;

import com.pulse.calc.core.CalendarFormat;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;

/**
 * The Friday weekly-pulse prompt (spec platform/jobs-scheduler "Friday pulse", insight/progress-pulse
 * "Weekly pulse"): a notice for {@link com.pulse.platform.notify.NotificationService#notify} that opens
 * Progress with the pulse form. Built from the date only, so it carries no answer or note.
 * NotificationTriggers has no builder for it yet; this one follows its shape.
 */
final class PulsePrompt
{

    static final String KIND = "pulse.prompt";

    private PulsePrompt()
    {
    }

    /**
     * "Weekly pulse · week of 28 Sep — two questions, 20 seconds" to the user, linking to the form.
     */
    static JsonObject notice(String userId, LocalDate weekStart)
    {
        return new JsonObject()
                .put("userId", userId)
                .put("kind", KIND)
                .put("text", "Weekly pulse · week of " + CalendarFormat.date(weekStart) + " — two questions, 20 seconds")
                .put("link", "/progress?pulse=open");
    }
}
