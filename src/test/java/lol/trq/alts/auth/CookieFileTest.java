package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CookieFileTest {

    private static final String NETSCAPE = ".login.live.com\tTRUE\t/\tTRUE\t0\tMSPOK\tabc123\n";

    @TempDir
    Path dir;

    private Path write(String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    @Test
    void readsPlainUtf8() throws IOException {
        Path file = write("cookies.txt", NETSCAPE.getBytes(StandardCharsets.UTF_8));

        assertEquals(NETSCAPE, CookieFile.read(file));
    }

    @Test
    void stripsUtf8ByteOrderMark() throws IOException {
        byte[] body = NETSCAPE.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        String read = CookieFile.read(write("bom.txt", withBom));

        assertEquals(NETSCAPE, read);
        assertFalse(read.startsWith("﻿"), "byte-order mark must not survive into the parsed text");
    }

    @Test
    void decodesUtf16AsWrittenByPowerShellRedirection() throws IOException {
        // A UTF-16LE export decoded as UTF-8 keeps every character but interleaves NULs, so assert the
        // decoded text equals the original rather than merely containing the cookie name.
        byte[] utf16 = NETSCAPE.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[utf16.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(utf16, 0, withBom, 2, utf16.length);

        String read = CookieFile.read(write("utf16.txt", withBom));

        assertEquals(NETSCAPE, read);
        assertFalse(read.contains("\0"), "UTF-16 must not be decoded as UTF-8");
    }

    @Test
    void decodesBigEndianUtf16() throws IOException {
        byte[] utf16 = NETSCAPE.getBytes(StandardCharsets.UTF_16BE);
        byte[] withBom = new byte[utf16.length + 2];
        withBom[0] = (byte) 0xFE;
        withBom[1] = (byte) 0xFF;
        System.arraycopy(utf16, 0, withBom, 2, utf16.length);

        assertEquals(NETSCAPE, CookieFile.read(write("utf16be.txt", withBom)));
    }

    @Test
    void rejectsMissingFile() {
        IOException thrown = assertThrows(IOException.class, () -> CookieFile.read(dir.resolve("absent.txt")));

        assertTrue(thrown.getMessage().contains("absent.txt"), thrown.getMessage());
    }

    @Test
    void rejectsDirectory() {
        assertThrows(IOException.class, () -> CookieFile.read(dir));
    }

    @Test
    void rejectsBlankFile() throws IOException {
        Path file = write("blank.txt", "   \n\t\n".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> CookieFile.read(file));
    }

    @Test
    void rejectsOversizedFile() throws IOException {
        Path file = write("huge.txt", new byte[(int) CookieFile.MAX_BYTES + 1]);

        IOException thrown = assertThrows(IOException.class, () -> CookieFile.read(file));

        assertTrue(thrown.getMessage().contains("too large"), thrown.getMessage());
    }
}
