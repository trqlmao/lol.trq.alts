package lol.trq.alts.auth;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads exported browser cookies off disk into the text {@link CookieAuthUtil} parses.
 *
 * <p>Cookie exports arrive as files far more often than as pasted text — every browser extension that
 * produces them writes a {@code cookies.txt}, and the Netscape format they use is line-oriented, so a
 * paste through a single-line input mangles it. The parsing itself already lives in this package and
 * is format-tolerant; only getting the bytes to it was missing.
 *
 * <p>Decoding is byte-order-mark aware. A file redirected out of PowerShell is UTF-16LE, which decoded
 * as UTF-8 yields text interleaved with NUL bytes — the parser then finds no cookie it recognises and
 * the failure reads as "my cookies are bad" rather than "my file is in another encoding".
 *
 * @author trq
 * @since 0.7.0
 */
public final class CookieFile {

    /**
     * Largest cookie export accepted. Netscape exports of a full browser profile run to tens of
     * kilobytes; a megabyte is far past any real one and keeps a mistakenly picked archive or disk
     * image from being read into memory.
     */
    public static final long MAX_BYTES = 1L << 20;

    private CookieFile() {}

    /**
     * Reads {@code file} as cookie text.
     *
     * @param file the file to read
     * @return the file's text content, byte-order mark stripped
     * @throws IOException if the file is missing, is not a regular file, is larger than
     *     {@link #MAX_BYTES}, holds no text, or cannot be read
     */
    public static String read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");

        String name = file.getFileName() == null
                ? file.toString()
                : file.getFileName().toString();

        if (!Files.exists(file)) {
            throw new IOException(name + ": no such file");
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException(name + ": not a file");
        }

        long size = Files.size(file);
        if (size > MAX_BYTES) {
            throw new IOException(name + ": too large (max " + (MAX_BYTES / 1024) + " KiB)");
        }

        String text = decode(Files.readAllBytes(file));
        if (text.isBlank()) {
            throw new IOException(name + ": file is empty");
        }
        return text;
    }

    /**
     * Decodes cookie bytes to text, honouring a leading byte-order mark and defaulting to UTF-8.
     *
     * @param bytes the raw file content
     * @return the decoded text with any byte-order mark removed
     */
    static String decode(byte[] bytes) {
        if (startsWith(bytes, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, (byte) 0xFF, (byte) 0xFE)) {
            return decodeFrom(bytes, 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, (byte) 0xFE, (byte) 0xFF)) {
            return decodeFrom(bytes, 2, StandardCharsets.UTF_16BE);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String decodeFrom(byte[] bytes, int offset, Charset charset) {
        return new String(bytes, offset, bytes.length - offset, charset);
    }

    private static boolean startsWith(byte[] bytes, byte... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
