package com.pulse.platform.http;

import io.vertx.core.json.JsonObject;

import java.util.Set;

/**
 * A failure with a known HTTP status, a stable machine code and a message safe to show the user.
 * Handlers pass it to {@code ctx.fail(e.status(), e)}; {@link FailureHandler} renders it.
 */
public class ApiException extends RuntimeException
{
    private final int status;

    private final String code;

    /** Members the error object always has, or that have their own setter; {@link #detail} refuses them. */
    private static final Set<String> RESERVED = Set.of("code", "message", "requestId", "field", "retryAfter",
            "currentVersion");

    private final JsonObject details = new JsonObject();

    public ApiException(int status, String code, String message)
    {
        super(message, null, false, false);

        this.status = status;
        this.code = code;
    }

    /** Names the request field the error is about; rendered as {@code error.field}. */
    public ApiException field(String name)
    {
        details.put("field", name);

        return this;
    }

    /** Seconds the client should wait (429); rendered as {@code error.retryAfter} and a Retry-After header. */
    public ApiException retryAfter(long seconds)
    {
        details.put("retryAfter", seconds);

        return this;
    }

    /** The record's version now (409 {@code version_conflict}); rendered as {@code error.currentVersion}. */
    public ApiException currentVersion(int version)
    {
        details.put("currentVersion", version);

        return this;
    }

    /**
     * Any other machine-readable member of the error object, for example the records that block an
     * import undo: {@code .detail("conflicts", jsonArray)} renders {@code error.conflicts}. The value
     * must be JSON-encodable (String, Number, Boolean, JsonObject, JsonArray). Use lowerCamelCase names;
     * never put data the caller may not see here, it is sent as is.
     *
     * @throws IllegalArgumentException for {@code code}, {@code message}, {@code requestId} and names
     *                                  that have their own setter
     */
    public ApiException detail(String name, Object value)
    {
        if (name == null || name.isBlank() || RESERVED.contains(name))
        {
            throw new IllegalArgumentException("'" + name + "' is reserved in the error object; use its own setter");
        }

        details.put(name, value);

        return this;
    }

    /** Extra members of the error object ({@code field}, {@code retryAfter}, {@code currentVersion}, {@link #detail}). */
    public JsonObject details()
    {
        return details;
    }

    public int status()
    {
        return status;
    }

    public String code()
    {
        return code;
    }

    public static ApiException badRequest(String code, String message)
    {
        return new ApiException(400, code, message);
    }

    public static ApiException unauthorized(String code, String message)
    {
        return new ApiException(401, code, message);
    }

    public static ApiException notFound(String code, String message)
    {
        return new ApiException(404, code, message);
    }

    public static ApiException forbidden(String code, String message)
    {
        return new ApiException(403, code, message);
    }

    public static ApiException conflict(String code, String message)
    {
        return new ApiException(409, code, message);
    }

    public static ApiException unprocessable(String code, String message)
    {
        return new ApiException(422, code, message);
    }

    public static ApiException tooManyRequests(String code, String message)
    {
        return new ApiException(429, code, message);
    }
}
