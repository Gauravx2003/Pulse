package com.pulse.platform.live;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Base URL rules shared by every live connector (task 24.3, design.md decision 3): {@code http} or
 * {@code https} only; no user info, query or fragment; a trailing slash is dropped; a path is kept as
 * the context path and may not contain dot segments or encoded separators; an {@code http} URL is
 * accepted with a warning. Unspecified, link-local and metadata addresses are refused; loopback only
 * with {@code allowLoopback} (the mock server's test-only flag, task 24.4).
 */
public final class BaseUrls
{
    /** Warning code for an {@code http} base URL. */
    public static final String INSECURE_HTTP = "insecure_http";

    public static final String INSECURE_HTTP_MESSAGE =
            "http sends credentials and data unencrypted on the network. Use https where the server offers it.";

    private BaseUrls()
    {
    }

    /**
     * Validates {@code url}. Resolves (never throws) to
     * {@code {valid: true, url, origin: {scheme, host, port, pathPrefix}, warnings: [{code, message}]}}
     * or {@code {valid: false, code, message}}. Never performs DNS.
     */
    public static JsonObject validate(String url, boolean allowLoopback)
    {
        JsonObject parsed;

        try
        {
            parsed = parse(url);
        }
        catch (IllegalArgumentException e)
        {
            return invalid("invalid_url", e.getMessage());
        }

        String host = parsed.getString("host");

        InetAddress literal = AddressRules.literal(host);

        if (AddressRules.isLiteral(host) && literal == null)
        {
            return invalid("invalid_url", "The host is not a valid IP address.");
        }

        if (literal != null && AddressRules.never(literal))
        {
            return invalid("blocked_address", "This address can't be used: it is a link-local, metadata or "
                    + "unspecified address.");
        }

        boolean loopback = (literal != null && AddressRules.loopback(literal)) || AddressRules.loopbackName(host);

        if (loopback && !allowLoopback)
        {
            return invalid("blocked_address", "A loopback address (this server itself) can't be used.");
        }

        JsonArray warnings = new JsonArray();

        if ("http".equals(parsed.getString("scheme")))
        {
            warnings.add(new JsonObject().put("code", INSECURE_HTTP).put("message", INSECURE_HTTP_MESSAGE));
        }

        return new JsonObject()
                .put("valid", true)
                .put("url", parsed.getString("url"))
                .put("origin", origin(parsed))
                .put("warnings", warnings);
    }

    /**
     * The origin {@code {scheme, host, port, pathPrefix}} of a base URL that passed {@link #validate};
     * what a connector returns from {@code allowedOrigins}. Throws {@link IllegalArgumentException} for
     * a malformed URL.
     */
    public static JsonObject origin(String url)
    {
        return origin(parse(url));
    }

    /** The normalized base URL ({@code scheme://host[:port][/context]}, no trailing slash). */
    public static String normalize(String url)
    {
        return parse(url).getString("url");
    }

    private static JsonObject origin(JsonObject parsed)
    {
        return new JsonObject()
                .put("scheme", parsed.getString("scheme"))
                .put("host", parsed.getString("host"))
                .put("port", parsed.getInteger("port"))
                .put("pathPrefix", parsed.getString("path"));
    }

    /** {@code {scheme, host, port, path, url}}; throws {@link IllegalArgumentException} with a user message. */
    static JsonObject parse(String url)
    {
        if (url == null || url.isBlank())
        {
            throw new IllegalArgumentException("Enter the base URL.");
        }

        if (url.length() > 2_000)
        {
            throw new IllegalArgumentException("The base URL is too long.");
        }

        String trimmed = url.strip();

        if (trimmed.chars().anyMatch(c -> c <= 0x20 || c == '\\' || c >= 0x7f))
        {
            throw new IllegalArgumentException("The base URL contains spaces or characters that are not allowed.");
        }

        URI uri;

        try
        {
            uri = new URI(trimmed);
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException("The base URL is not a valid URL.");
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);

        if (!"http".equals(scheme) && !"https".equals(scheme))
        {
            throw new IllegalArgumentException("The base URL must start with http:// or https://.");
        }

        if (uri.getRawUserInfo() != null)
        {
            throw new IllegalArgumentException("Remove the user name or password from the URL; enter them in "
                    + "their own fields.");
        }

        if (uri.getRawQuery() != null)
        {
            throw new IllegalArgumentException("The base URL can't have a query (?...).");
        }

        if (uri.getRawFragment() != null)
        {
            throw new IllegalArgumentException("The base URL can't have a fragment (#...).");
        }

        if (uri.getHost() == null || uri.getHost().isBlank())
        {
            throw new IllegalArgumentException("The base URL has no valid host name.");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);

        int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();

        if (port < 1 || port > 65_535)
        {
            throw new IllegalArgumentException("The port must be between 1 and 65535.");
        }

        String path = contextPath(uri.getRawPath());

        boolean defaultPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);

        String normalized = scheme + "://" + host + (defaultPort ? "" : ":" + port) + path;

        return new JsonObject()
                .put("scheme", scheme)
                .put("host", host)
                .put("port", port)
                .put("path", path)
                .put("url", normalized);
    }

    /**
     * The context path without trailing slashes ({@code ""} for none). Refuses dot segments, empty
     * segments and encoded dots, slashes or backslashes, which could climb out of it.
     */
    static String contextPath(String rawPath)
    {
        String path = rawPath == null ? "" : rawPath;

        while (path.endsWith("/"))
        {
            path = path.substring(0, path.length() - 1);
        }

        if (path.isEmpty())
        {
            return "";
        }

        if (!safePath(path))
        {
            throw new IllegalArgumentException("The base URL path can't contain '.', '..', '//' or encoded "
                    + "separators.");
        }

        return path;
    }

    /** True for a path of plain segments: no {@code .}/{@code ..}, no empty segment, no %2e, %2f or %5c. */
    static boolean safePath(String path)
    {
        if (!path.startsWith("/"))
        {
            return false;
        }

        String lower = path.toLowerCase(Locale.ROOT);

        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("\\"))
        {
            return false;
        }

        String[] segments = path.substring(1).split("/", -1);

        for (int i = 0; i < segments.length; i++)
        {
            String s = segments[i];

            boolean last = i == segments.length - 1;

            if (s.equals(".") || s.equals("..") || (s.isEmpty() && !last))
            {
                return false;
            }
        }

        return true;
    }

    private static JsonObject invalid(String code, String message)
    {
        return new JsonObject().put("valid", false).put("code", code).put("message", message);
    }
}
