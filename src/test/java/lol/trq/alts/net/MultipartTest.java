package lol.trq.alts.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The multipart body the skin upload sends: a text field and a file part, closed by the boundary. */
class MultipartTest {

    @Test
    void buildsAFieldAndFilePartClosedByTheBoundary() {
        Multipart form = new Multipart("BOUND")
                .field("variant", "slim")
                .file("file", "skin.png", "image/png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});

        String body = new String(form.body(), StandardCharsets.ISO_8859_1);

        assertEquals("multipart/form-data; boundary=BOUND", form.contentType());
        assertTrue(body.contains("--BOUND\r\n"), "each part opens with the boundary");
        assertTrue(body.contains("name=\"variant\""), "the text field is present");
        assertTrue(body.contains("slim"), "with its value");
        assertTrue(body.contains("filename=\"skin.png\""), "the file part declares its name");
        assertTrue(body.contains("Content-Type: image/png"), "and its content type");
        assertTrue(body.endsWith("--BOUND--\r\n"), "the body closes with the terminating boundary");
    }
}
