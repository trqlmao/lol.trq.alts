package lol.trq.alts.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
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

    /**
     * The extensions cookie exports normally carry, without a leading dot, for a host building a file
     * picker's filter. Advisory only — {@link #read(Path)} accepts any path, because a user who renamed
     * their export is not wrong. Ordered most to least common.
     *
     * @since 0.7.0
     */
    public static final List<String> EXTENSIONS = List.of("txt", "json", "cookies");

    /** Label used when a path has no file name of its own, so no absolute path reaches a message. */
    private static final String UNNAMED = "cookie file";

    private CookieFile() {}

    /**
     * Reads {@code file} as cookie text.
     *
     * <p>Every failure is reported against the file's <em>name</em> only. The JDK's own I/O messages are
     * the absolute path, and this message travels into a {@code LoginResult} a host shows in its UI and
     * writes to its log, so an access-denied on a file under a home directory would otherwise publish
     * that directory.
     *
     * @param file the file to read
     * @return the file's text content, byte-order mark stripped
     * @throws IOException if the file is missing, is not a regular file, is larger than
     *     {@link #MAX_BYTES}, holds no text, or cannot be read
     */
    public static String read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");

        String name = displayName(file);

        // isRegularFile answers both questions at once, and answers them false for a directory, a device,
        // a named pipe, and anything else that would otherwise be opened and read until it felt like
        // stopping. A symlink to a regular file is followed, which is what a user picking one intends.
        if (!Files.isRegularFile(file)) {
            throw new IOException(name + (Files.exists(file) ? ": not a file" : ": no such file"));
        }

        String text = decode(readCapped(file, name));
        if (text.isBlank()) {
            throw new IOException(name + ": file is empty");
        }
        return text;
    }

    /**
     * Reads at most one byte past the cap, so the limit binds the read itself. Checking the size first
     * and reading afterwards would only bind a file that holds still between the two.
     *
     * @param file the file to read
     * @param name the file's display name, for the failure message
     * @return the file's bytes
     * @throws IOException if the file is over the cap or cannot be read
     */
    private static byte[] readCapped(Path file, String name) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes((int) MAX_BYTES + 1);
        } catch (IOException unreadable) {
            throw new IOException(name + ": " + reason(unreadable), unreadable);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException(name + ": too large (max " + (MAX_BYTES / 1024) + " KiB)");
        }
        return bytes;
    }

    /**
     * Describes a read failure without naming a path.
     *
     * @param failure the failure to describe
     * @return a short, path-free reason
     */
    private static String reason(IOException failure) {
        if (failure instanceof AccessDeniedException) {
            return "access denied";
        }
        if (failure instanceof NoSuchFileException) {
            return "no such file";
        }
        return "could not be read";
    }

    /**
     * Returns the file's own name, never a path. A path with no file name — a bare root — has no name to
     * show, and printing the path instead would be exactly the leak this avoids.
     *
     * @param file the file being read
     * @return the file name, or a generic label
     */
    private static String displayName(Path file) {
        Path name = file.getFileName();
        return name == null ? UNNAMED : name.toString();
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
