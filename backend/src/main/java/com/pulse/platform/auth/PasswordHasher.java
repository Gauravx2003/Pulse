package com.pulse.platform.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * bcrypt with cost 12 (spec platform/auth-session "Password storage"). Hashing and verification
 * are CPU-heavy, so they always run in {@code executeBlocking}, never on the event loop.
 */
public final class PasswordHasher
{
    public static final int COST = 12;

    /** Verified against when the account does not exist, so timing does not reveal existence. */
    private static volatile String dummyHash;

    private PasswordHasher()
    {
    }

    /** True only when {@code hash} exists and matches. A null hash still costs one bcrypt run. */
    public static Future<Boolean> verify(Vertx vertx, String password, String hash)
    {
        return vertx.executeBlocking(() ->
        {
            String against = hash != null ? hash : dummy();

            boolean ok;

            try
            {
                ok = BCrypt.verifyer().verify(password.toCharArray(), against).verified;
            }
            catch (IllegalArgumentException tooLongOrMalformed)
            {
                ok = false;
            }

            return ok && hash != null;
        }, false);
    }

    /** bcrypt hash with cost 12 for a new password (used by the invite and reset flows, task 3.3). */
    public static Future<String> hash(Vertx vertx, String password)
    {
        return vertx.executeBlocking(() -> BCrypt.withDefaults().hashToString(COST, password.toCharArray()), false);
    }

    private static String dummy()
    {
        String d = dummyHash;

        if (d == null)
        {
            d = BCrypt.withDefaults().hashToString(COST, "pulse-dummy-password".toCharArray());
            dummyHash = d;
        }

        return d;
    }
}
