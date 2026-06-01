package lol.trq.alts.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EncryptionUtilTest {

    @Test
    void roundTripsUnderSameKey() throws Exception {
        String key = "test-hardware-key";
        String plaintext = "{\"accounts\":[{\"username\":\"Steve\"}]}";

        String encrypted = EncryptionUtil.encrypt(plaintext, key);
        assertNotEquals(plaintext, encrypted);
        assertEquals(plaintext, EncryptionUtil.decrypt(encrypted, key));
    }

    @Test
    void failsUnderWrongKey() throws Exception {
        String encrypted = EncryptionUtil.encrypt("secret", "right-key");
        assertThrows(Exception.class, () -> EncryptionUtil.decrypt(encrypted, "wrong-key"));
    }

    @Test
    void freshSaltProducesDistinctCiphertext() throws Exception {
        String key = "k";
        assertNotEquals(EncryptionUtil.encrypt("same", key), EncryptionUtil.encrypt("same", key));
    }
}
