package com.pulse.modules.imports.csv;

/**
 * A file-level problem that stops a Check before any row is looked at: too large, not a CSV,
 * empty, too many rows, a missing required column. Row-level problems are not exceptions; they are
 * reported per line and column in the validation result.
 *
 * <p>{@link #code()} is a stable machine-readable key for the API error contract (task 3.8); the
 * message is the plain-language text shown to the user.
 */
public class CsvImportException extends RuntimeException
{
    public static final String FILE_TOO_LARGE = "file_too_large";

    public static final String NOT_CSV = "not_csv";

    public static final String EMPTY_FILE = "empty_file";

    public static final String NO_DATA_ROWS = "no_data_rows";

    public static final String TOO_MANY_ROWS = "too_many_rows";

    public static final String UNTERMINATED_QUOTE = "unterminated_quote";

    public static final String MISSING_COLUMNS = "missing_columns";

    public static final String DUPLICATE_COLUMN = "duplicate_column";

    private final String code;

    public CsvImportException(String code, String message)
    {
        super(message);

        this.code = code;
    }

    public String code()
    {
        return code;
    }
}
