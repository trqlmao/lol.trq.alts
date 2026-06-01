package lol.trq.alts.crypto;

import java.security.SecureRandom;
import javax.crypto.SecretKey;

/**
 * Offline recovery codes for a {@link VaultIdentity}. A high-entropy code is shown to the user once;
 * it derives (via PBKDF2) a key that seals a second copy of the identity's private material. There is
 * no server-side escrow — losing both the passphrase and the code means the identity is unrecoverable,
 * by design.
 *
 * @author trq
 * @since 0.2.0
 */
public final class RecoveryCode {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Entropy of a generated code, in bytes (256-bit). */
    private static final int CODE_BYTES = 32;

    /** Display grouping size, in characters. */
    private static final int GROUP = 8;

    private static final java.util.Base64.Encoder URL_B64 =
            java.util.Base64.getUrlEncoder().withoutPadding();

    private RecoveryCode() {}

    /**
     * Generates a fresh 256-bit recovery code, formatted in dash-separated groups for legibility. Show
     * it to the user exactly once; it is never stored in plaintext.
     *
     * @return the formatted recovery code
     */
    public static String newCode() {
        byte[] bytes = new byte[CODE_BYTES];
        RANDOM.nextBytes(bytes);
        String raw = URL_B64.encodeToString(bytes);
        StringBuilder grouped = new StringBuilder(raw.length() + raw.length() / GROUP);
        for (int i = 0; i < raw.length(); i++) {
            if (i > 0 && i % GROUP == 0) {
                grouped.append('-');
            }
            grouped.append(raw.charAt(i));
        }
        return grouped.toString();
    }

    /**
     * Derives the recovery key from a code and salt. The code is normalized (dashes and whitespace
     * stripped) so the formatted and unformatted forms derive identically.
     *
     * @param code the recovery code as entered by the user
     * @param salt the PBKDF2 salt stored alongside the recovery sealing
     * @return the derived AES key
     * @throws CryptoException if derivation fails
     */
    public static SecretKey deriveKey(String code, byte[] salt) throws CryptoException {
        char[] normalized = normalize(code);
        try {
            return Pbkdf2.deriveKey(normalized, salt, Pbkdf2.VAULT_ITERATIONS);
        } finally {
            java.util.Arrays.fill(normalized, '\0');
        }
    }

    private static char[] normalize(String code) {
        StringBuilder sb = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c != '-' && !Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        char[] out = new char[sb.length()];
        sb.getChars(0, sb.length(), out, 0);
        return out;
    }
}
