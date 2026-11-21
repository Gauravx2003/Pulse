package com.pulse.platform.auth;

import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Opaque session and CSRF tokens. Only the SHA-256 of a session token is stored, so a database
 * read does not give working cookies.
 */
public final class SessionTokens
{
    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionTokens()
    {
    }

    /** 256 random bits, base64url without padding. */
    public static String newToken()
    {
        byte[] bytes = new byte[32];

        RANDOM.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static Buffer hash(String token)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));

            return Buffer.buffer(digest);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison for CSRF tokens. */
    public static boolean same(String a, String b)
    {
        if (a == null || b == null)
        {
            return false;
        }

        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
