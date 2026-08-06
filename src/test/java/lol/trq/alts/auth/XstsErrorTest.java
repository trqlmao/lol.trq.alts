package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The XSTS error codes map to what a user must fix. The library used to read only whether a token came
 * back, so every one of these surfaced as "Xbox auth failed" — useless to someone whose account simply
 * needs a one-time Xbox sign-in.
 */
class XstsErrorTest {

    @Test
    void eachKnownCodeMapsToItsCause() {
        assertEquals(XstsError.NO_XBOX_ACCOUNT, XstsError.fromCode(2148916233L));
        assertEquals(XstsError.REGION_BLOCKED, XstsError.fromCode(2148916235L));
        assertEquals(XstsError.ADULT_VERIFICATION_REQUIRED, XstsError.fromCode(2148916236L));
        assertEquals(XstsError.ADULT_VERIFICATION_REQUIRED_ALT, XstsError.fromCode(2148916237L));
        assertEquals(XstsError.CHILD_ACCOUNT, XstsError.fromCode(2148916238L));
    }

    @Test
    void anUnrecognisedCodeIsUnknown() {
        assertEquals(XstsError.UNKNOWN, XstsError.fromCode(9999L));
        assertEquals(XstsError.UNKNOWN, XstsError.fromCode(0L));
    }

    @Test
    void theCodeRoundTrips() {
        assertEquals(2148916238L, XstsError.CHILD_ACCOUNT.code());
    }
}
