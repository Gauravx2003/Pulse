package com.pulse.platform.db;

/**
 * A list request used a parameter that is not allowed (unknown filter or sort, bad page number).
 * The central failure handler (task 3.8) maps it to 400 with the offending field.
 */
public class QueryValidationException extends IllegalArgumentException {

    private final String field;

    public QueryValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
