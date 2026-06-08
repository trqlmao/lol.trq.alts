package lol.trq.alts.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lol.trq.alts.vault.transport.IssuerSignedKeyBindingVerifier;
import org.junit.jupiter.api.Test;

/**
 * Gates this library's crypto against the published Alt Vault Protocol conformance vectors (vendored from
 * {@code trqlmao/avp} under {@code src/test/resources/avp-vectors}). Round-trip tests prove the library is
 * self-consistent; these prove it is byte-for-byte interoperable with every other AVP implementation, so a
 * silent change to a construction (an AAD field order, a base64 variant, an HKDF salt) is caught here.
 */
class AvpConformanceVectorsTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] WRAP_INFO = "avp/rdk-wrap/v1".getBytes(StandardCharsets.UTF_8);

    private static JsonArray cases(String file) throws Exception {
        try (InputStream in = AvpConformanceVectorsTest.class.getResourceAsStream("/avp-vectors/" + file)) {
            Objects.requireNonNull(in, "missing vendored vector resource: " + file);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return root.getAsJsonArray("cases");
        }
    }

    private static byte[] b64(String s) {
        return Base64.getDecoder().decode(s);
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static SecretKey aesKey(byte[] raw) {
        return new SecretKeySpec(raw, "AES");
    }

    // --- RFC-anchored primitives -------------------------------------------------------------------

    @Test
    void hkdf() throws Exception {
        for (JsonObject c : objects(cases("hkdf.json"))) {
            String name = c.get("name").getAsString();
            byte[] ikm = HEX.parseHex(c.get("ikmHex").getAsString());
            byte[] salt = HEX.parseHex(c.get("saltHex").getAsString());
            byte[] info = HEX.parseHex(c.get("infoHex").getAsString());
            int length = c.get("length").getAsInt();

            byte[] prk = Hkdf.extract(salt, ikm);
            assertEquals(c.get("prkHex").getAsString(), HEX.formatHex(prk), name + " prk");
            byte[] okm = Hkdf.expand(prk, info, length);
            assertEquals(c.get("okmHex").getAsString(), HEX.formatHex(okm), name + " okm");
        }
    }

    @Test
    void x25519() throws Exception {
        for (JsonObject c : objects(cases("x25519.json"))) {
            String name = c.get("name").getAsString();
            PrivateKey scalar =
                    X25519.decodePrivateKey(HEX.parseHex(c.get("scalarHex").getAsString()));
            PublicKey peer =
                    X25519.decodePublicKey(HEX.parseHex(c.get("uCoordinateHex").getAsString()));
            byte[] output = X25519.agree(scalar, peer);
            assertEquals(c.get("outputHex").getAsString(), HEX.formatHex(output), name + " shared secret");
        }
    }

    @Test
    void ed25519() throws Exception {
        for (JsonObject c : objects(cases("ed25519.json"))) {
            String name = c.get("name").getAsString();
            PrivateKey priv =
                    Ed25519.decodePrivateKey(HEX.parseHex(c.get("seedHex").getAsString()));
            PublicKey pub =
                    Ed25519.decodePublicKey(HEX.parseHex(c.get("publicKeyHex").getAsString()));
            byte[] message = HEX.parseHex(c.get("messageHex").getAsString());
            String expectedSig = c.get("signatureHex").getAsString();

            // Ed25519 is deterministic: the signature must reproduce the published vector byte for byte.
            assertEquals(expectedSig, HEX.formatHex(Ed25519.sign(priv, message)), name + " signature");
            assertTrue(Ed25519.verify(pub, message, HEX.parseHex(expectedSig)), name + " verify");
        }
    }

    // --- Deterministic constructions ---------------------------------------------------------------

    @Test
    void aad() throws Exception {
        for (JsonObject c : objects(cases("aad.json"))) {
            byte[] aad = PayloadCipher.aad(
                    c.get("repoId").getAsString(),
                    c.get("payloadVersion").getAsLong(),
                    c.get("keyEpoch").getAsLong());
            assertEquals(c.get("expectedAadHex").getAsString(), HEX.formatHex(aad), "aad");
        }
    }

    @Test
    void keyBindingMessage() throws Exception {
        for (JsonObject c : objects(cases("key-binding-message.json"))) {
            byte[] message = IssuerSignedKeyBindingVerifier.bindingMessage(
                    c.get("ed25519PublicKey").getAsString(),
                    c.get("x25519PublicKey").getAsString());
            assertEquals(
                    c.get("expectedMessageUtf8").getAsString(), new String(message, StandardCharsets.UTF_8), "binding");
        }
    }

    // --- Envelope compositions ---------------------------------------------------------------------

    @Test
    void payloadAead() throws Exception {
        for (JsonObject c : objects(cases("payload-aead.json"))) {
            String name = c.get("name").getAsString();
            SecretKey key = aesKey(b64(c.get("keyB64").getAsString()));
            byte[] iv = b64(c.get("ivB64").getAsString());
            byte[] aad = PayloadCipher.aad(
                    c.get("repoId").getAsString(),
                    c.get("payloadVersion").getAsLong(),
                    c.get("keyEpoch").getAsLong());
            assertEquals(c.get("aadHex").getAsString(), HEX.formatHex(aad), name + " aad cross-check");

            byte[] plaintext = c.get("plaintextUtf8").getAsString().getBytes(StandardCharsets.UTF_8);
            String expectedCt = c.get("ciphertextB64").getAsString();

            // (a) encrypt reproduces the vector; (b) decrypt recovers the plaintext.
            assertEquals(expectedCt, b64(PayloadCipher.encrypt(key, iv, aad, plaintext)), name + " encrypt");
            assertArrayEquals(plaintext, PayloadCipher.decrypt(key, iv, aad, b64(expectedCt)), name + " decrypt");

            // (c) a changed epoch in the AAD must fail authentication (rotation replay protection).
            byte[] tamperedAad = PayloadCipher.aad(
                    c.get("repoId").getAsString(),
                    c.get("payloadVersion").getAsLong(),
                    c.get("tamperEpoch").getAsLong());
            assertThrows(
                    CryptoException.class,
                    () -> PayloadCipher.decrypt(key, iv, tamperedAad, b64(expectedCt)),
                    name + " tampered epoch must reject");
        }
    }

    @Test
    void keyWrap() throws Exception {
        X25519HkdfAesGcmKeyWrap scheme = new X25519HkdfAesGcmKeyWrap();
        for (JsonObject c : objects(cases("key-wrap.json"))) {
            String name = c.get("name").getAsString();
            PrivateKey recipientPriv =
                    X25519.decodePrivateKey(b64(c.get("recipientPrivateKeyB64").getAsString()));
            JsonObject wk = c.getAsJsonObject("wrappedKey");
            byte[] ephemeralRaw = b64(wk.get("ephemeralPublicKey").getAsString());

            // Intermediates: the shared secret and KEK must match the published bytes.
            byte[] shared = X25519.agree(recipientPriv, X25519.decodePublicKey(ephemeralRaw));
            assertEquals(c.get("sharedSecretHex").getAsString(), HEX.formatHex(shared), name + " shared secret");
            SecretKey kek = Hkdf.deriveKey(shared, ephemeralRaw, WRAP_INFO, 32);
            assertEquals(c.get("kekHex").getAsString(), HEX.formatHex(kek.getEncoded()), name + " kek");

            // The wrap ciphertext reproduces byte for byte under the pinned KEK + IV.
            byte[] dataKey = b64(c.get("dataKeyB64").getAsString());
            byte[] iv = b64(wk.get("iv").getAsString());
            assertEquals(
                    wk.get("ciphertext").getAsString(),
                    b64(PayloadCipher.encrypt(kek, iv, WRAP_INFO, dataKey)),
                    name + " wrap ciphertext");

            // And the library unwraps the WrappedKey back to the data key.
            WrappedKey blob = new WrappedKey(
                    wk.get("schemeId").getAsString(),
                    wk.get("ephemeralPublicKey").getAsString(),
                    wk.get("iv").getAsString(),
                    wk.get("ciphertext").getAsString());
            assertArrayEquals(
                    dataKey, scheme.unwrap(blob, recipientPriv).getEncoded(), name + " unwrap recovers data key");
        }
    }

    // --- Negative (MUST-reject) bank ---------------------------------------------------------------

    @Test
    void negative() throws Exception {
        X25519HkdfAesGcmKeyWrap scheme = new X25519HkdfAesGcmKeyWrap();
        for (JsonObject c : objects(cases("negative.json"))) {
            String name = c.get("name").getAsString();
            switch (c.get("op").getAsString()) {
                case "payload-decrypt" -> {
                    SecretKey key = aesKey(b64(c.get("keyB64").getAsString()));
                    byte[] iv = b64(c.get("ivB64").getAsString());
                    byte[] aad = PayloadCipher.aad(
                            c.get("repoId").getAsString(),
                            c.get("payloadVersion").getAsLong(),
                            c.get("keyEpoch").getAsLong());
                    byte[] ct = b64(c.get("ciphertextB64").getAsString());
                    assertThrows(CryptoException.class, () -> PayloadCipher.decrypt(key, iv, aad, ct), name);
                }
                case "key-unwrap" -> {
                    PrivateKey recipientPriv = X25519.decodePrivateKey(
                            b64(c.get("recipientPrivateKeyB64").getAsString()));
                    WrappedKey blob = new WrappedKey(
                            X25519HkdfAesGcmKeyWrap.SCHEME_ID,
                            c.get("ephemeralPublicKeyB64").getAsString(),
                            c.get("ivB64").getAsString(),
                            c.get("ciphertextB64").getAsString());
                    assertThrows(CryptoException.class, () -> scheme.unwrap(blob, recipientPriv), name);
                }
                case "ed25519-verify" -> {
                    PublicKey pub = Ed25519.decodePublicKey(
                            HEX.parseHex(c.get("publicKeyHex").getAsString()));
                    byte[] message = HEX.parseHex(c.get("messageHex").getAsString());
                    byte[] signature = HEX.parseHex(c.get("signatureHex").getAsString());
                    assertFalse(Ed25519.verify(pub, message, signature), name);
                }
                default ->
                    throw new IllegalStateException(
                            "unknown negative op: " + c.get("op").getAsString());
            }
        }
    }

    private static Iterable<JsonObject> objects(JsonArray array) {
        return () -> array.asList().stream().map(JsonObject.class::cast).iterator();
    }
}
