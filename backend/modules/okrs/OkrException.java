package com.pulse.modules.okrs;

/**
 * A refused OKR operation with the HTTP status the route passes to {@code ctx.fail(status, e)}, so the
 * central FailureHandler (task 3.8) renders the standard error contract. {@code field} names the
 * offending input for 400 responses.
 */
public class OkrException extends RuntimeException
{

    private final int status;

    private final String field;

    private OkrException(int status, String field, String message)
    {
        super(message);

        this.status = status;

        this.field = field;
    }

    /**
     * 400: invalid input, shown next to {@code field}.
     */
    public static OkrException invalid(String field, String message)
    {
        return new OkrException(400, field, message);
    }

    /**
     * 403: the caller may not change this objective. Also used when it does not exist, so a write
     * never reveals whether an objective exists (design.md decision 2).
     */
    public static OkrException forbidden()
    {
        return new OkrException(403, null, "You can't change this objective.");
    }

    /**
     * 404: the objective does not exist or is not visible to the caller (same response for both).
     */
    public static OkrException notFound()
    {
        return new OkrException(404, null, "Objective not found.");
    }

    /**
     * 409: someone else saved the objective first, or the same objective already exists.
     */
    public static OkrException conflict(String message)
    {
        return new OkrException(409, null, message);
    }

    public int status()
    {
        return status;
    }

    public String field()
    {
        return field;
    }
}
