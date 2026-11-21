package com.pulse.platform.config;

import io.vertx.core.json.JsonObject;

import java.util.Locale;
import java.util.Set;

/**
 * Deployment profile and the demo-seed switch (task 2.4, spec platform/auth-session "Demo accounts
 * are flag-gated", design.md decision 3).
 *
 * <ul>
 *   <li>{@code PULSE_PROFILE} (config {@code profile}): {@code development} (default) or
 *       {@code production}.</li>
 *   <li>{@code PULSE_DEMO_SEED} (config {@code demoSeed}): off by default. When on, demo seed files
 *       ({@code S<n>__demo_*.sql}) are loaded: the demo accounts with password {@code demo123} and
 *       the demo organisation.</li>
 * </ul>
 *
 * <p>Infrastructure value, not a domain DTO.
 */
public record RuntimeProfile(String profile, boolean demoSeed) {

    public static final String DEVELOPMENT = "development";
    public static final String PRODUCTION = "production";

    /** Domain of the demo accounts; such an account must never exist under the production profile. */
    public static final String DEMO_EMAIL_DOMAIN = "@pulse.demo";

    private static final Set<String> TRUE = Set.of("true", "1", "yes", "on");
    private static final Set<String> FALSE = Set.of("false", "0", "no", "off", "");

    public static RuntimeProfile from(JsonObject config) {
        String profile = setting(config, "profile", "PULSE_PROFILE", DEVELOPMENT).strip().toLowerCase(Locale.ROOT);
        if (profile.equals("prod")) {
            profile = PRODUCTION;
        }
        if (!profile.equals(DEVELOPMENT) && !profile.equals(PRODUCTION)) {
            throw new IllegalStateException("PULSE_PROFILE must be 'development' or 'production', not '" + profile + "'");
        }
        return new RuntimeProfile(profile, flag(setting(config, "demoSeed", "PULSE_DEMO_SEED", "false")));
    }

    public boolean production() {
        return PRODUCTION.equals(profile);
    }

    /** Fails before anything starts when the production profile runs with the demo seed on. */
    public RuntimeProfile guard() {
        if (production() && demoSeed) {
            throw new IllegalStateException("Refusing to start: PULSE_DEMO_SEED is on under the production profile. "
                    + "Demo accounts use the password demo123; set PULSE_DEMO_SEED=false.");
        }
        return this;
    }

    private static boolean flag(String raw) {
        String v = raw.strip().toLowerCase(Locale.ROOT);
        if (TRUE.contains(v)) {
            return true;
        }
        if (FALSE.contains(v)) {
            return false;
        }
        throw new IllegalStateException("PULSE_DEMO_SEED must be true or false, not '" + raw + "'");
    }

    private static String setting(JsonObject config, String key, String env, String fallback) {
        Object fromConfig = config == null ? null : config.getValue(key);
        if (fromConfig != null) {
            return fromConfig.toString();
        }
        String fromEnv = System.getenv(env);
        return fromEnv != null ? fromEnv : fallback;
    }
}
