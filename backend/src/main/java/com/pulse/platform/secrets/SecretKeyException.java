package com.pulse.platform.secrets;

import com.pulse.platform.http.ApiException;

/**
 * The secret key cannot be used (task 24.2, spec platform/secrets-credentials): it is missing or
 * malformed, or a stored secret does not decrypt under it (key changed, ciphertext or IV altered).
 * Always a 422 naming the rule; the message never contains the key or the secret.
 *
 * <p>A sync that meets this exception sends no request and records {@link #SYNC_ERROR}, so the tile
 * shows "Sync failed · key" and Fix opens Change credentials.
 */
public final class SecretKeyException extends ApiException
{
    /** The tile's error code after "Sync failed · " for every reason below. */
    public static final String SYNC_ERROR = "key";

    /** No {@code PULSE_SECRET_KEY}: live credentials can't be saved and live tools can't sync. */
    public static final String MISSING = "secret_key_missing";

    /** {@code PULSE_SECRET_KEY} is set but is not 32 bytes of base64. */
    public static final String INVALID = "secret_key_invalid";

    /** A stored secret failed to decrypt: key changed or data altered. */
    public static final String UNREADABLE = "secret_unreadable";

    private SecretKeyException(String code, String message)
    {
        super(422, code, message);
    }

    public static SecretKeyException missing()
    {
        return new SecretKeyException(MISSING, "Live credentials can't be saved: the server has no secret key. "
                + "Set " + SecretBox.ENV + " (32 bytes, base64) and restart Pulse.");
    }

    public static SecretKeyException invalid()
    {
        return new SecretKeyException(INVALID, "Live credentials can't be saved: " + SecretBox.ENV
                + " must be 32 bytes encoded as base64.");
    }

    public static SecretKeyException unreadable()
    {
        return new SecretKeyException(UNREADABLE, "The stored credentials can't be read: the secret key is "
                + "missing or has changed, or the stored data was altered. Enter the credentials again.");
    }
}
