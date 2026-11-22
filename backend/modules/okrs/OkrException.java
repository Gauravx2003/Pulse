package com.pulse.modules.okrs;

/**
 * A refused OKR operation with the HTTP status and the stable error code that {@link OkrsRoutes} turns
 * into an {@code ApiException} for the central FailureHandler (task 3.8). {@code field} names the
 * offending input for 400 responses; {@code currentVersion} is set on a stale-version 409.
 */
public class OkrException extends RuntimeException
{

    private final int status;

    private final String code;

    private final String field;

    private final Integer currentVersion;

    private OkrException(int status, String code, String field, String message, Integer currentVersion)
    {
        super(message);

        this.status = status;

        this.code = code;

        this.field = field;

        this.currentVersion = currentVersion;
    }

    /**
     * 400 {@code invalid_field}: invalid input, shown next to {@code field}.
     */
    public static OkrException invalid(String field, String message)
    {
        return new OkrException(400, "invalid_field", field, message, null);
    }

    /**
     * 403 {@code no_access}: the caller may not change this objective. Also used when it does not exist,
     * so a write never reveals whether an objective exists (design.md decision 2).
     */
    public static OkrException forbidden()
    {
        return new OkrException(403, "no_access", null, "You can't change this objective.", null);
    }

    /**
     * 404 {@code not_found}: the objective does not exist or is not visible to the caller (same response
     * for both).
     */
    public static OkrException notFound()
    {
        return new OkrException(404, "not_found", null, "Objective not found.", null);
    }

    /**
     * 409 {@code conflict}: the operation clashes with the objective's current state.
     */
    public static OkrException conflict(String message)
    {
        return new OkrException(409, "conflict", null, message, null);
    }

    /**
     * 409 with a specific code, such as {@code okr_exists}.
     */
    public static OkrException conflict(String code, String message)
    {
        return new OkrException(409, code, null, message, null);
    }

    /**
     * 409 {@code version_conflict}: someone saved the objective after the caller loaded it.
     */
    public static OkrException versionConflict(Integer currentVersion)
    {
        return new OkrException(409, "version_conflict", null,
                "This objective was changed by someone else. Reload and try again.", currentVersion);
    }

    public int status()
    {
        return status;
    }

    public String code()
    {
        return code;
    }

    public String field()
    {
        return field;
    }

    public Integer currentVersion()
    {
        return currentVersion;
    }
}
