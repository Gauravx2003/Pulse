package com.pulse.platform.http;

import io.vertx.core.json.JsonObject;

/**
 * A failure with a known HTTP status, a stable machine code and a message safe to show the user.
 * Handlers pass it to {@code ctx.fail(e.status(), e)}; {@link FailureHandler} renders it.
 */
public class ApiException extends RuntimeException
{
    private final int status;

    private final String code;

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

    /** Extra members of the error object ({@code field}, {@code retryAfter}). */
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

    public static ApiException unprocessable(String code, String message)
    {
        return new ApiException(422, code, message);
    }

    public static ApiException tooManyRequests(String code, String message)
    {
        return new ApiException(429, code, message);
    }
}
