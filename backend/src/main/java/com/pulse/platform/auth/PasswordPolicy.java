package com.pulse.platform.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Rules for user-chosen passwords (spec platform/auth-session "Password policy"): at least 8
 * characters, not the user's email or name. bcrypt reads at most 72 bytes, so longer passwords are
 * refused rather than silently cut. Passwords inserted by the demo seed are exempt because they
 * never pass through here.
 */
public final class PasswordPolicy
{
    public static final int MIN_LENGTH = 8;

    public static final int MAX_BYTES = 72;

    public static final String MSG_TOO_SHORT = "Password needs at least 8 characters.";

    public static final String MSG_TOO_LONG = "Password can have at most 72 characters.";

    public static final String MSG_PERSONAL = "Password cannot be your email or name.";

    private PasswordPolicy()
    {
    }

    /** The message for the first rule {@code password} breaks, or null when it is acceptable. */
    public static String violation(String password, String email, String name)
    {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH)
        {
            return MSG_TOO_SHORT;
        }

        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
        {
            return MSG_TOO_LONG;
        }

        String typed = normalize(password);

        if (typed.equals(normalize(email)) || typed.equals(normalize(name)))
        {
            return MSG_PERSONAL;
        }

        return null;
    }

    private static String normalize(String s)
    {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }
}
