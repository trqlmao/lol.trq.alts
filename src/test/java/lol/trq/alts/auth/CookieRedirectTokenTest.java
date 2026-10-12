package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The last redirect of the cookie flow carries the token bundle as percent-encoded Base64 JSON. Reading
 * it used to be a chain of text splits over the undecoded value, which broke on an encoded padding
 * character and on any change to the order of the bundle's fields.
 */
class CookieRedirectTokenTest {

    private static final String LANDING = "https://www.minecraft.net/en-us/login#state=login&accessToken=";

    /** Two relying parties, the Minecraft one second, with a user hash whose run of tildes Base64-encodes to a '+'. */
    private static final String BUNDLE = """
            [{"Item1":"http://xboxlive.com","Item2":{"Token":"xbl-token","DisplayClaims":{"xui":[{"uhs":"other"}]}}},
             {"Item1":"rp://api.minecraftservices.com/","Item2":{"DisplayClaims":{"xui":[{"uhs":"hash~~~"}]},"Token":"mc-token"}}]
            """;

    private static String base64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsTheMinecraftTokenOutOfThePercentEncodedBundle() throws Exception {
        String encoded = URLEncoder.encode(base64(BUNDLE), StandardCharsets.UTF_8);

        CookieAuthUtil.XstsTokenData data = CookieAuthUtil.extractXstsFromRedirect(LANDING + encoded);

        assertEquals("mc-token", data.token());
        assertEquals("hash~~~", data.uhs());
    }

    @Test
    void aBundleThatWasNotPercentEncodedKeepsItsPlusSigns() throws Exception {
        String plain = base64(BUNDLE);
        assertTrue(plain.contains("+"), "the fixture must exercise a literal plus sign");

        CookieAuthUtil.XstsTokenData data = CookieAuthUtil.extractXstsFromRedirect(LANDING + plain);

        assertEquals("mc-token", data.token());
        assertEquals("hash~~~", data.uhs());
    }

    @Test
    void readsAUrlSafeBundle() throws Exception {
        String urlSafe = Base64.getUrlEncoder().encodeToString(BUNDLE.getBytes(StandardCharsets.UTF_8));

        assertEquals(
                "mc-token",
                CookieAuthUtil.extractXstsFromRedirect(LANDING + urlSafe).token());
    }

    @Test
    void stopsAtTheNextParameterOrFragment() throws Exception {
        String encoded = URLEncoder.encode(base64(BUNDLE), StandardCharsets.UTF_8);

        assertEquals(
                "mc-token",
                CookieAuthUtil.extractXstsFromRedirect(LANDING + encoded + "&lc=1033")
                        .token());
        assertEquals(
                "mc-token",
                CookieAuthUtil.extractXstsFromRedirect("https://example.invalid/?accessToken=" + encoded + "#top")
                        .token());
    }

    @Test
    void aRedirectThatStoppedOneHopShortIsRejected() {
        // What the chain used to hand over: the code for SISU, not the token SISU trades it for.
        String callback = "https://sisu.xboxlive.com/connect/oauth/XboxLive?code=M.C123&state=abc";

        assertThrows(Exception.class, () -> CookieAuthUtil.extractXstsFromRedirect(callback));
    }

    @Test
    void aBundleWithNoMinecraftTokenIsRejected() {
        String other = base64("[{\"Item1\":\"http://xboxlive.com\",\"Item2\":{\"Token\":\"t\"}}]");

        assertThrows(Exception.class, () -> CookieAuthUtil.extractXstsFromRedirect(LANDING + other));
    }

    @Test
    void garbageInPlaceOfTheBundleIsRejectedRatherThanThrownRaw() {
        assertThrows(Exception.class, () -> CookieAuthUtil.extractXstsFromRedirect(LANDING + "%zz"));
        assertThrows(Exception.class, () -> CookieAuthUtil.extractXstsFromRedirect(LANDING + "not base64!"));
        assertThrows(Exception.class, () -> CookieAuthUtil.extractXstsFromRedirect(LANDING + base64("{}")));
    }
}
