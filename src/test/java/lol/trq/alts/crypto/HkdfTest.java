package lol.trq.alts.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** RFC 5869 Appendix A test vectors for HKDF-SHA256. */
class HkdfTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void matchesRfc5869TestCase1() throws Exception {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");

        byte[] prk = Hkdf.extract(salt, ikm);
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", HEX.formatHex(prk));

        byte[] okm = Hkdf.expand(prk, info, 42);
        assertEquals(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                HEX.formatHex(okm));
    }

    @Test
    void matchesRfc5869TestCase3ZeroLengthSaltAndInfo() throws Exception {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");

        byte[] prk = Hkdf.extract(new byte[0], ikm);
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", HEX.formatHex(prk));

        byte[] okm = Hkdf.expand(prk, new byte[0], 42);
        assertEquals(
                "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
                HEX.formatHex(okm));
    }

    @Test
    void deriveKeyProducesRequestedLength() throws Exception {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        var key = Hkdf.deriveKey(ikm, new byte[0], "ctx".getBytes(), 32);
        assertEquals(32, key.getEncoded().length);
        // Deterministic for the same inputs.
        var again = Hkdf.deriveKey(ikm, new byte[0], "ctx".getBytes(), 32);
        assertArrayEquals(key.getEncoded(), again.getEncoded());
    }
}
