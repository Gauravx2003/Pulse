package com.pulse.platform.http;

import io.vertx.core.json.JsonObject;

/**
 * Request hardening limits (task 3.6, spec security-baseline). Defaults follow the spec; tests and
 * deployments override them through the verticle config.
 *
 * @param appOrigin           the browser origin allowed by CORS and the Origin check ({@code app.url} /
 *                            {@code PULSE_APP_URL}, http://localhost:5173)
 * @param bodyLimitBytes      JSON and form bodies ({@code http.bodyLimitBytes}, 1 MB)
 * @param uploadLimitBytes    CSV and multipart uploads ({@code http.uploadLimitBytes}, 5 MB)
 * @param ipPerMinute         requests per client IP per minute on every route ({@code http.ipPerMinute}, 600)
 * @param userPerMinute       requests per signed-in user per minute under {@code /api/v1} ({@code http.userPerMinute}, 300)
 */
public record SecuritySettings(String appOrigin, long bodyLimitBytes, long uploadLimitBytes, int ipPerMinute,
                               int userPerMinute)
{
    public static SecuritySettings from(JsonObject config)
    {
        JsonObject c = config == null ? new JsonObject() : config;

        String appUrl = c.getString("app.url", System.getenv().getOrDefault("PULSE_APP_URL", "http://localhost:5173"));

        return new SecuritySettings(
                origin(appUrl),
                c.getLong("http.bodyLimitBytes", 1024L * 1024),
                c.getLong("http.uploadLimitBytes", 5L * 1024 * 1024),
                c.getInteger("http.ipPerMinute", 600),
                c.getInteger("http.userPerMinute", 300));
    }

    /** {@code scheme://host[:port]} of a URL, without path or trailing slash. */
    static String origin(String url)
    {
        String u = url.strip();

        int scheme = u.indexOf("://");
        int path = scheme < 0 ? -1 : u.indexOf('/', scheme + 3);

        return (path < 0 ? u : u.substring(0, path)).toLowerCase();
    }
}
