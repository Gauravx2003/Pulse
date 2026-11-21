package com.pulse.calc.core;

import com.pulse.platform.config.RuntimeProfile;
import io.vertx.core.json.JsonObject;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

/**
 * The one source of "today" and "now" (task 4.1, spec platform/period-calendar "Clock and time zone").
 *
 * <p>"Today" is the server clock's date in the company time zone, {@link #ZONE} (Asia/Kolkata).
 * Instants are stored in UTC; only display and day boundaries use the company zone.
 *
 * <p>{@code PULSE_TODAY} (config {@code today}, format {@code yyyy-MM-dd}) pins "today" for demos
 * and tests, so seeded data matches the prototype (2026-09-28). It is ignored under the production
 * profile. While pinned, {@link #now()} is the pinned date at the current company time of day.
 *
 * <p>Immutable and stateless apart from the underlying {@link Clock}; safe to share across
 * HTTP verticle instances.
 */
public final class PulseClock
{
    /** The company time zone. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final Clock clock;

    private final LocalDate pinned;

    private PulseClock(Clock clock, LocalDate pinned)
    {
        this.clock = clock;
        this.pinned = pinned;
    }

    /**
     * Reads {@code today} from config or {@code PULSE_TODAY} from the environment. Fails at start-up
     * on a malformed value, rather than silently using the real date.
     */
    public static PulseClock from(JsonObject config, RuntimeProfile profile)
    {
        Object fromConfig = config == null ? null : config.getValue("today");

        String raw = fromConfig != null ? fromConfig.toString() : System.getenv("PULSE_TODAY");

        return of(Clock.system(ZONE), raw, profile.production());
    }

    /**
     * A clock over {@code clock}, pinned to {@code today} unless it is blank or {@code production}
     * is true. Visible for tests.
     */
    public static PulseClock of(Clock clock, String today, boolean production)
    {
        if (production || today == null || today.isBlank())
        {
            return new PulseClock(clock, null);
        }

        try
        {
            return new PulseClock(clock, LocalDate.parse(today.strip()));
        }
        catch (DateTimeParseException e)
        {
            throw new IllegalStateException("PULSE_TODAY must be a date such as 2026-09-28, not '" + today + "'", e);
        }
    }

    /** "Today" in the company time zone. */
    public LocalDate today()
    {
        return pinned != null ? pinned : LocalDate.now(clock.withZone(ZONE));
    }

    /** "Now" in the company time zone; on a pinned clock, the pinned date at the current time of day. */
    public ZonedDateTime now()
    {
        ZonedDateTime real = ZonedDateTime.now(clock.withZone(ZONE));

        return pinned == null ? real : real.with(pinned);
    }

    /** The current instant (UTC), for storage. */
    public Instant instant()
    {
        return now().toInstant();
    }

    /** True when "today" comes from {@code PULSE_TODAY}. */
    public boolean pinned()
    {
        return pinned != null;
    }
}
