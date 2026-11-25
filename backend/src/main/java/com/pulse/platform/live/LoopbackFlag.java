package com.pulse.platform.live;

import com.pulse.platform.config.RuntimeProfile;
import io.vertx.core.json.JsonObject;

import java.util.Locale;
import java.util.Set;

/**
 * The test-only switch that lets a live connector's base URL name a loopback address (task 24.4,
 * design.md decision 8). The mock server listens on {@code http://127.0.0.1:<port>}; base URL
 * validation ({@link BaseUrls#validate}) refuses loopback unless this switch is on.
 *
 * <ul>
 *   <li>{@code PULSE_LIVE_ALLOW_LOOPBACK} (config {@code liveAllowLoopback}): off by default.</li>
 *   <li>Refused under the production profile: {@link #guard} fails startup, and {@link #allowed}
 *       never answers true there.</li>
 * </ul>
 */
public final class LoopbackFlag
{
    public static final String CONFIG_KEY = "liveAllowLoopback";

    public static final String ENV = "PULSE_LIVE_ALLOW_LOOPBACK";

    private static final Set<String> TRUE = Set.of("true", "1", "yes", "on");

    private static final Set<String> FALSE = Set.of("false", "0", "no", "off", "");

    private LoopbackFlag()
    {
    }

    /** True when the switch is on; reads {@code config} first, then the environment. */
    public static boolean requested(JsonObject config)
    {
        Object fromConfig = config == null ? null : config.getValue(CONFIG_KEY);

        String raw = fromConfig != null ? fromConfig.toString() : System.getenv(ENV);

        if (raw == null)
        {
            return false;
        }

        String v = raw.strip().toLowerCase(Locale.ROOT);

        if (TRUE.contains(v))
        {
            return true;
        }

        if (FALSE.contains(v))
        {
            return false;
        }

        throw new IllegalStateException(ENV + " must be true or false, not '" + raw + "'");
    }

    /** Fails before anything starts when the switch is on under the production profile. */
    public static void guard(JsonObject config, RuntimeProfile profile)
    {
        if (profile.production() && requested(config))
        {
            throw new IllegalStateException("Refusing to start: " + ENV + " is a test-only switch and is not "
                    + "allowed under the production profile; set " + ENV + "=false.");
        }
    }

    /** Whether loopback base URLs are accepted: the switch is on and the profile is not production. */
    public static boolean allowed(JsonObject config, RuntimeProfile profile)
    {
        return !profile.production() && requested(config);
    }
}
