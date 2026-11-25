package com.pulse.platform.live;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.LocalMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Proof that a live connector's Test connection passed (task 24.5, spec secrets-credentials "Save
 * bound to a passing test"). Save accepts only a proof that:
 * <ul>
 *   <li>was issued for the same tool,</li>
 *   <li>was issued for the same values: config, secret and expiry, compared through an HMAC with a
 *       random key held only in memory, so nothing here can be used to guess the secret,</li>
 *   <li>is at most 10 minutes old,</li>
 *   <li>has not been used before. It is consumed by the first Save attempt, whether or not that Save
 *       succeeds.</li>
 * </ul>
 *
 * Proofs live in {@code vertx.sharedData()}, so every HTTP verticle instance sees the same ones.
 * Removal is atomic, so two concurrent saves cannot both use one proof. A restart forgets every
 * proof; the Admin then runs the test again.
 */
public final class TestProofs
{
    public static final long TTL_MS = 10L * 60 * 1000;

    static final String MAP = "pulse.live.test-proofs";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Per-process HMAC key; never stored or logged. */
    private static final byte[] KEY = random(32);

    private final LocalMap<String, JsonObject> proofs;

    private final LongSupplier clock;

    public TestProofs(Vertx vertx)
    {
        this(vertx, System::currentTimeMillis);
    }

    /** As above with a clock in epoch milliseconds (tests). */
    public TestProofs(Vertx vertx, LongSupplier clock)
    {
        this.proofs = Objects.requireNonNull(vertx, "vertx").sharedData().getLocalMap(MAP);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Issues a proof for a passing test of {@code tool} with these values. {@code testedExpiresAt} is
     * the expiry the tool reported, if any; Save uses it when the Admin entered none. Returns
     * {@code {proof, expiresAt}}, where {@code expiresAt} is when the proof stops being accepted (epoch ms).
     */
    public JsonObject issue(String tool, JsonObject config, String secret, String expiresAt, String testedExpiresAt)
    {
        sweep();

        String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));

        long until = clock.getAsLong() + TTL_MS;

        proofs.put(proof, new JsonObject()
                .put("tool", tool)
                .put("hash", hash(tool, config, secret, expiresAt))
                .put("until", until)
                .put("testedExpiresAt", testedExpiresAt));

        return new JsonObject().put("proof", proof).put("expiresAt", until);
    }

    /**
     * Uses up {@code proof}. Returns the proof's details ({@code {testedExpiresAt}}) when it
     * matches the tool and values and has not expired, else null. The proof is removed either way.
     */
    public JsonObject consume(String proof, String tool, JsonObject config, String secret, String expiresAt)
    {
        if (proof == null || proof.isEmpty())
        {
            return null;
        }

        JsonObject entry = proofs.remove(proof);

        if (entry == null)
        {
            return null;
        }

        boolean fresh = clock.getAsLong() <= entry.getLong("until");

        boolean same = tool.equals(entry.getString("tool"))
                && entry.getString("hash").equals(hash(tool, config, secret, expiresAt));

        return fresh && same ? new JsonObject().put("testedExpiresAt", entry.getString("testedExpiresAt")) : null;
    }

    /** Drops expired proofs; the map stays small (one or two per Admin test). */
    private void sweep()
    {
        long now = clock.getAsLong();

        proofs.keySet().forEach(k ->
        {
            JsonObject e = proofs.get(k);

            if (e != null && e.getLong("until") < now)
            {
                proofs.remove(k);
            }
        });
    }

    /** HMAC-SHA256 over the length-prefixed tool, config (normalized JSON), expiry and secret. */
    static String hash(String tool, JsonObject config, String secret, String expiresAt)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");

            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));

            for (String part : new String[] {tool, config == null ? "{}" : config.encode(),
                    expiresAt == null ? "" : expiresAt, secret == null ? "" : secret})
            {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);

                mac.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                mac.update((byte) ':');
                mac.update(bytes);
            }

            return HexFormat.of().formatHex(mac.doFinal());
        }
        catch (GeneralSecurityException e)
        {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static byte[] random(int n)
    {
        byte[] out = new byte[n];

        RANDOM.nextBytes(out);

        return out;
    }
}
