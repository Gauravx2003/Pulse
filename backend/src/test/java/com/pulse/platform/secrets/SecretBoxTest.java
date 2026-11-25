package com.pulse.platform.secrets;

import com.pulse.testsupport.TestApi;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 24.2: AES-256-GCM under PULSE_SECRET_KEY, random IV, refusals without a usable key. */
class SecretBoxTest
{
    private static final String PAT = "ghp_Abc123Def456Ghi789Jkl";

    private static Vertx vertx;

    @BeforeAll
    static void start()
    {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() throws Exception
    {
        TestApi.await(vertx.close());
    }

    private static SecretBox box(String key)
    {
        return SecretBox.from(vertx, new JsonObject().put(SecretBox.CONFIG_KEY, key));
    }

    private static SecretKeyException keyFailure(io.vertx.core.Future<?> future)
    {
        ExecutionException e = assertThrows(ExecutionException.class, () -> TestApi.await(future));

        return assertInstanceOf(SecretKeyException.class, e.getCause());
    }

    @Test
    void roundTripsWithAFreshIvEachTime() throws Exception
    {
        SecretBox box = box(SecretBox.generateKey());

        JsonObject a = TestApi.await(box.seal("jira", PAT));
        JsonObject b = TestApi.await(box.seal("jira", PAT));

        assertEquals(12, Base64.getDecoder().decode(a.getString("iv")).length, "96-bit IV");
        assertNotEquals(a.getString("iv"), b.getString("iv"));
        assertNotEquals(a.getString("ciphertext"), b.getString("ciphertext"));
        assertFalse(a.encode().contains(PAT));
        assertEquals("9Jkl", a.getString("last4"));

        assertEquals(PAT, TestApi.await(box.open("jira", a.getString("ciphertext"), a.getString("iv"))));
    }

    @Test
    void shortSecretsKeepNoLast4() throws Exception
    {
        JsonObject sealed = TestApi.await(box(SecretBox.generateKey()).seal("jira", "hunter22"));

        assertNull(sealed.getString("last4"));
    }

    @Test
    void sealIsRefusedWithoutAKey()
    {
        SecretBox none = SecretBox.from(vertx, new JsonObject().put(SecretBox.CONFIG_KEY, ""));

        assertFalse(none.configured());

        SecretKeyException e = keyFailure(none.seal("jira", PAT));

        assertEquals(422, e.status());
        assertEquals(SecretKeyException.MISSING, e.code());
        assertTrue(e.getMessage().contains(SecretBox.ENV));
    }

    @Test
    void sealIsRefusedWithAMalformedKey()
    {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertEquals(SecretKeyException.INVALID, keyFailure(box(shortKey).seal("jira", PAT)).code());
        assertEquals(SecretKeyException.INVALID, keyFailure(box("not base64 !").seal("jira", PAT)).code());
    }

    @Test
    void tamperedCiphertextIvOrContextDoesNotDecrypt() throws Exception
    {
        SecretBox box = box(SecretBox.generateKey());

        JsonObject sealed = TestApi.await(box.seal("jira", PAT));

        byte[] ct = Base64.getDecoder().decode(sealed.getString("ciphertext"));

        ct[0] ^= 1;

        String tampered = Base64.getEncoder().encodeToString(ct);

        SecretKeyException e = keyFailure(box.open("jira", tampered, sealed.getString("iv")));

        assertEquals(SecretKeyException.UNREADABLE, e.code());
        assertEquals("key", SecretKeyException.SYNC_ERROR);

        byte[] iv = Base64.getDecoder().decode(sealed.getString("iv"));

        iv[0] ^= 1;

        keyFailure(box.open("jira", sealed.getString("ciphertext"), Base64.getEncoder().encodeToString(iv)));

        // copied to another tool's row
        keyFailure(box.open("gh", sealed.getString("ciphertext"), sealed.getString("iv")));
    }

    @Test
    void changedOrMissingKeyDoesNotDecrypt() throws Exception
    {
        JsonObject sealed = TestApi.await(box(SecretBox.generateKey()).seal("jira", PAT));

        assertEquals(SecretKeyException.UNREADABLE, keyFailure(box(SecretBox.generateKey())
                .open("jira", sealed.getString("ciphertext"), sealed.getString("iv"))).code());

        assertEquals(SecretKeyException.UNREADABLE, keyFailure(box("")
                .open("jira", sealed.getString("ciphertext"), sealed.getString("iv"))).code());
    }

    @Test
    void neverPrintsTheKey()
    {
        String key = SecretBox.generateKey();

        assertFalse(box(key).toString().contains(key));
    }
}
