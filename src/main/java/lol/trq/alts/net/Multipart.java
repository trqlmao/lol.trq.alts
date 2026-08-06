package lol.trq.alts.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds a {@code multipart/form-data} body — the one request shape the skin upload needs and the
 * form/JSON helpers do not cover.
 *
 * <p>Deliberately tiny: the two field kinds the upload uses (a text field and one file part), a fixed
 * boundary chosen at construction, and the content type to pair with it. Not a general multipart
 * library, and not trying to be.
 *
 * @author trq
 * @since 1.0.0
 */
public final class Multipart {

    private final String boundary;
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    /**
     * Creates a builder with a fixed boundary. The boundary must not occur in any part's bytes; for the
     * skin upload — a text token and a PNG — a long constant is safe, so a caller passes one rather than
     * the library reaching for randomness it cannot get deterministically.
     *
     * @param boundary the multipart boundary
     * @since 1.0.0
     */
    public Multipart(String boundary) {
        this.boundary = boundary;
    }

    /**
     * Appends a text field.
     *
     * @param name the field name
     * @param value the field value
     * @return this builder
     * @since 1.0.0
     */
    public Multipart field(String name, String value) {
        write("--" + boundary + "\r\n");
        write("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        write(value);
        write("\r\n");
        return this;
    }

    /**
     * Appends a file part.
     *
     * @param name the field name
     * @param filename the file name to declare
     * @param contentType the part's content type
     * @param content the file bytes
     * @return this builder
     * @since 1.0.0
     */
    public Multipart file(String name, String filename, String contentType, byte[] content) {
        write("--" + boundary + "\r\n");
        write("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n");
        write("Content-Type: " + contentType + "\r\n\r\n");
        body.writeBytes(content);
        write("\r\n");
        return this;
    }

    /**
     * Returns the assembled body, closing the multipart.
     *
     * @return the body bytes
     * @since 1.0.0
     */
    public byte[] body() {
        ByteArrayOutputStream complete = new ByteArrayOutputStream();
        complete.writeBytes(body.toByteArray());
        complete.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return complete.toByteArray();
    }

    /**
     * Returns the {@code Content-Type} header value this body must be sent with, boundary included.
     *
     * @return the content type
     * @since 1.0.0
     */
    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    private void write(String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
