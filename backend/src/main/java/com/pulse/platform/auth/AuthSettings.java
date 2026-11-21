package com.pulse.platform.auth;

import io.vertx.core.json.JsonObject;

/**
 * Sign-in and session limits (spec platform/auth-session, design.md decision 3). Defaults follow
 * the spec; tests and deployments may override them through the verticle config.
 *
 * @param maxFailures          consecutive failed sign-ins that lock an email ({@code auth.maxFailures}, 5)
 * @param failureWindowSeconds window in which failures count as consecutive ({@code auth.failureWindowSeconds}, 900)
 * @param lockSeconds          lockout length ({@code auth.lockSeconds}, 900 = 15 min)
 * @param ipLimit              sign-in attempts per IP per window before 429 ({@code auth.ipLimit}, 50)
 * @param ipWindowSeconds      IP throttle window ({@code auth.ipWindowSeconds}, 900)
 * @param sessionTtlSeconds    session lifetime from sign-in ({@code auth.sessionTtlSeconds}, 43200 = 12 h)
 * @param trustProxy           take the client IP from {@code X-Real-IP} set by our nginx
 *                             ({@code http.trustProxy} / {@code PULSE_TRUST_PROXY}, false)
 * @param inviteTtlSeconds     lifetime of an invite (set-password) link ({@code auth.inviteTtlSeconds}, 604800 = 7 days)
 * @param resetTtlSeconds      lifetime of a password-reset link ({@code auth.resetTtlSeconds}, 3600 = 1 h)
 * @param appUrl               browser origin the links point to ({@code app.url} / {@code PULSE_APP_URL},
 *                             http://localhost:5173)
 */
public record AuthSettings(int maxFailures, int failureWindowSeconds, int lockSeconds, int ipLimit,
                           int ipWindowSeconds, int sessionTtlSeconds, boolean trustProxy,
                           int inviteTtlSeconds, int resetTtlSeconds, String appUrl)
{
    public static AuthSettings from(JsonObject config)
    {
        JsonObject c = config == null ? new JsonObject() : config;

        String trust = c.getValue("http.trustProxy") != null
                ? String.valueOf(c.getValue("http.trustProxy"))
                : System.getenv().getOrDefault("PULSE_TRUST_PROXY", "false");

        String appUrl = c.getString("app.url", System.getenv().getOrDefault("PULSE_APP_URL", "http://localhost:5173"));

        return new AuthSettings(
                c.getInteger("auth.maxFailures", 5),
                c.getInteger("auth.failureWindowSeconds", 900),
                c.getInteger("auth.lockSeconds", 900),
                c.getInteger("auth.ipLimit", 50),
                c.getInteger("auth.ipWindowSeconds", 900),
                c.getInteger("auth.sessionTtlSeconds", 12 * 60 * 60),
                Boolean.parseBoolean(trust.strip()),
                c.getInteger("auth.inviteTtlSeconds", 7 * 24 * 60 * 60),
                c.getInteger("auth.resetTtlSeconds", 60 * 60),
                appUrl.strip().replaceAll("/+$", ""));
    }
}
