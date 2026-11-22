package com.pulse.platform.ingest;

/**
 * A tool answered a sync with an error (timeout, 401, 429, 5xx). {@link #display()} is what the tile
 * shows after "Sync failed · ", e.g. {@code "401 Unauthorized"}; it never contains a secret.
 */
public class ConnectorException extends RuntimeException
{
    private final int status;

    public ConnectorException(int status, String reason)
    {
        super(status + " " + reason);
        this.status = status;
    }

    /** HTTP status of the tool's answer, or 0 for a timeout or a connection error. */
    public int status()
    {
        return status;
    }

    public String display()
    {
        return getMessage();
    }
}
