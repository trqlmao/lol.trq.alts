package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The three shapes a cookie export arrives in. The JSON one is what the common cookie-editor extensions
 * write, and it used to fall through to the mangled parser — which found the names it knows and took the
 * surrounding JSON as their values, producing a header the service rejected and a failure that read as
 * bad cookies rather than an unread export.
 */
class CookieParsingTest {

    private static final String NETSCAPE = """
            # Netscape HTTP Cookie File
            .login.live.com\tTRUE\t/\tTRUE\t0\tMSPOK\ttoken-one
            .login.live.com\tTRUE\t/\tTRUE\t0\tMSPPre\ttoken-two
            .login.live.com\tTRUE\t/\tTRUE\t0\tMSPCID\ttoken-three
            .login.live.com\tTRUE\t/\tTRUE\t0\tuaid\ttoken-four
            """;

    private static final String JSON_EXPORT = """
            [
              {"domain":".login.live.com","name":"MSPOK","value":"token-one","path":"/","secure":true},
              {"domain":".login.live.com","name":"MSPPre","value":"token-two","path":"/","secure":true}
            ]
            """;

    @Test
    void readsTheNetscapeExport() {
        String header = CookieAuthUtil.parseCookies(NETSCAPE);

        assertTrue(header.contains("MSPOK=token-one"), header);
        assertTrue(header.contains("uaid=token-four"), header);
        assertFalse(header.contains("TRUE"), "the flag columns are not cookies: " + header);
    }

    @Test
    void readsTheJsonExport() {
        String header = CookieAuthUtil.parseCookies(JSON_EXPORT);

        assertTrue(header.contains("MSPOK=token-one"), header);
        assertTrue(header.contains("MSPPre=token-two"), header);
        assertFalse(header.contains("domain"), "only name/value pairs belong in a Cookie header: " + header);
        assertFalse(header.contains("{"), "no JSON may survive into the header: " + header);
    }

    @Test
    void readsAJsonExportNestedUnderAWrapperKey() {
        String wrapped = "{\"cookies\":" + JSON_EXPORT + "}";

        String header = CookieAuthUtil.parseCookies(wrapped);

        assertTrue(header.contains("MSPOK=token-one"), header);
        assertTrue(header.contains("MSPPre=token-two"), header);
    }

    @Test
    void aJsonExportKeepsValuesWholeEvenWhenTheyLookStructured() {
        String awkward = "[{\"name\":\"MSPOK\",\"value\":\"a=b; c=d\"}]";

        String header = CookieAuthUtil.parseCookies(awkward);

        assertEquals("MSPOK=a=b; c=d;", header, "the value is passed through as the exporter wrote it");
    }

    @Test
    void textThatOnlyLooksLikeJsonFallsThroughToTheOtherParsers() {
        String mangled = "[not json at all .login.live.com MSPOK token-one";

        String header = CookieAuthUtil.parseCookies(mangled);

        assertTrue(header.startsWith("MSPOK="), header);
    }

    @Test
    void dataWithNoRecognisableCookieIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CookieAuthUtil.parseCookies("nothing useful here"));
    }
}
