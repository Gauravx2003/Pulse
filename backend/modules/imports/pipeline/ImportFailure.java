package com.pulse.modules.imports.pipeline;

import io.vertx.core.json.JsonArray;

/**
 * A refused import request with the HTTP status the routes pass to {@code ctx.fail(status, e)}
 * (design.md, Vert.x & Java Coding Standards 4), a stable {@link #code()} for the error contract and
 * optional {@link #details()} (for example the records that block an undo).
 */
public class ImportFailure extends RuntimeException
{
    public static final String UNKNOWN_TYPE = "unknown_import_type";

    public static final String FORBIDDEN = "forbidden";

    public static final String BAD_MODE = "bad_mode";

    public static final String CHECK_NOT_FOUND = "check_not_found";

    public static final String IMPORT_NOT_FOUND = "import_not_found";

    public static final String ALREADY_UNDONE = "already_undone";

    public static final String UNDO_EXPIRED = "undo_expired";

    public static final String UNDO_CONFLICT = "undo_conflict";

    public static final String BAD_CHANGE = "bad_change";

    private final int status;

    private final String code;

    private final JsonArray details;

    public ImportFailure(int status, String code, String message)
    {
        this(status, code, message, new JsonArray());
    }

    public ImportFailure(int status, String code, String message, JsonArray details)
    {
        super(message);

        this.status = status;

        this.code = code;

        this.details = details;
    }

    public int status()
    {
        return status;
    }

    public String code()
    {
        return code;
    }

    public JsonArray details()
    {
        return details;
    }
}
