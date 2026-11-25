package com.pulse.platform.secrets;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Encrypts live-tool secrets at rest (task 24.2, design.md decision 2, spec platform/secrets-credentials).
 *
 * <ul>
 *   <li>AES-256-GCM, a fresh random 96-bit IV per secret and a 128-bit tag.</li>
 *   <li>The key is {@code PULSE_SECRET_KEY} (config {@code secretKey} first, for tests): 32 bytes,
 *       base64. It is decoded on every call and never logged or returned.</li>
 *   <li>The ciphertext is bound to its {@code context} (the tool id) as additional authenticated
 *       data, so a ciphertext copied to another tool's row does not decrypt.</li>
 *   <li>Key decoding and the cipher run in {@code executeBlocking}, never on the event loop.</li>
 * </ul>
 *
 * Sealed values are a {@link JsonObject} {@code {ciphertext, iv, last4}}: ciphertext and IV in base64,
 * last4 the only part of the secret ever shown again (null for secrets shorter than
 * {@value #LAST4_MIN_LENGTH} characters, so a short password is never half revealed).
 */
public final class SecretBox
{
    public static final String ENV = "PULSE_SECRET_KEY";

    public static final String CONFIG_KEY = "secretKey";

    /** A secret shorter than this keeps no last4 (as the Phase 1 masked placeholder). */
    public static final int LAST4_MIN_LENGTH = 12;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int KEY_BYTES = 32;

    private static final int IV_BYTES = 12;

    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Vertx vertx;

    private final String encodedKey;

    private SecretBox(Vertx vertx, String encodedKey)
    {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.encodedKey = encodedKey == null || encodedKey.isBlank() ? null : encodedKey.strip();
    }

    /** The box over {@code config.secretKey}, else the {@code PULSE_SECRET_KEY} environment variable. */
    public static SecretBox from(Vertx vertx, JsonObject config)
    {
        String fromConfig = config == null ? null : config.getString(CONFIG_KEY);

        return new SecretBox(vertx, fromConfig != null ? fromConfig : System.getenv(ENV));
    }

    /** True when a key is set (it may still be malformed; {@link #seal} reports that). */
    public boolean configured()
    {
        return encodedKey != null;
    }

    /**
     * Encrypts {@code plain} for {@code context}. Fails with {@link SecretKeyException} (422) when the
     * key is missing or malformed; nothing is produced in that case.
     */
    public Future<JsonObject> seal(String context, String plain)
    {
        Objects.requireNonNull(context, "context");

        if (plain == null || plain.isEmpty())
        {
            return Future.failedFuture(new IllegalArgumentException("Nothing to encrypt"));
        }

        if (encodedKey == null)
        {
            return Future.failedFuture(SecretKeyException.missing());
        }

        return vertx.executeBlocking(() ->
        {
            byte[] key = decodeKey();

            if (key == null)
            {
                throw SecretKeyException.invalid();
            }

            byte[] iv = new byte[IV_BYTES];

            RANDOM.nextBytes(iv);

            try
            {
                byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, key, iv, context)
                        .doFinal(plain.getBytes(StandardCharsets.UTF_8));

                return new JsonObject()
                        .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
                        .put("iv", Base64.getEncoder().encodeToString(iv))
                        .put("last4", last4(plain));
            }
            finally
            {
                Arrays.fill(key, (byte) 0);
            }
        }, false);
    }

    /**
     * Decrypts a sealed secret of {@code context}. Fails with {@link SecretKeyException#UNREADABLE}
     * when the key is missing, malformed or different, or the ciphertext, IV or context was altered.
     */
    public Future<String> open(String context, String ciphertext, String iv)
    {
        Objects.requireNonNull(context, "context");

        if (encodedKey == null || ciphertext == null || iv == null)
        {
            return Future.failedFuture(SecretKeyException.unreadable());
        }

        return vertx.executeBlocking(() ->
        {
            byte[] key = decodeKey();

            if (key == null)
            {
                throw SecretKeyException.unreadable();
            }

            try
            {
                byte[] ivBytes = Base64.getDecoder().decode(iv);

                if (ivBytes.length != IV_BYTES)
                {
                    throw SecretKeyException.unreadable();
                }

                byte[] plain = cipher(Cipher.DECRYPT_MODE, key, ivBytes, context)
                        .doFinal(Base64.getDecoder().decode(ciphertext));

                return new String(plain, StandardCharsets.UTF_8);
            }
            catch (AEADBadTagException | IllegalArgumentException e)
            {
                // wrong key, altered ciphertext / IV / context, or not base64: never say which
                throw SecretKeyException.unreadable();
            }
            finally
            {
                Arrays.fill(key, (byte) 0);
            }
        }, false);
    }

    /** The last 4 characters, or null when the secret is shorter than {@value #LAST4_MIN_LENGTH}. */
    public static String last4(String secret)
    {
        if (secret == null || secret.length() < LAST4_MIN_LENGTH)
        {
            return null;
        }

        return secret.substring(secret.length() - 4);
    }

    /** A new random key in the {@code PULSE_SECRET_KEY} format (for setup docs and tests). */
    public static String generateKey()
    {
        byte[] key = new byte[KEY_BYTES];

        RANDOM.nextBytes(key);

        return Base64.getEncoder().encodeToString(key);
    }

    private byte[] decodeKey()
    {
        byte[] key;

        try
        {
            key = Base64.getDecoder().decode(encodedKey);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }

        if (key.length != KEY_BYTES)
        {
            Arrays.fill(key, (byte) 0);

            return null;
        }

        return key;
    }

    private static Cipher cipher(int mode, byte[] key, byte[] iv, String context) throws GeneralSecurityException
    {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);

        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(("pulse/integration_connections/" + context).getBytes(StandardCharsets.UTF_8));

        return cipher;
    }

    @Override
    public String toString()
    {
        return "SecretBox[" + (configured() ? "key set" : "no key") + "]";
    }
}
