package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lol.trq.alts.auth.AltLoginCallback.FailureReason;
import lol.trq.alts.auth.AltLoginCallback.LoginResult;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import org.junit.jupiter.api.Test;

class LoginResultReasonTest {

    @Test
    void successCarriesNoFailureReason() {
        AltAccount account = AltAccount.of("u", "Alex", "tok", AccountType.MICROSOFT);

        LoginResult result = LoginResult.success(account);

        assertTrue(result.success());
        assertEquals(FailureReason.NONE, result.reason());
        assertEquals(account, result.account());
    }

    @Test
    void unclassifiedFailureIsUnknownRatherThanMislabelled() {
        LoginResult result = LoginResult.failure("session injection blew up");

        assertFalse(result.success());
        assertNull(result.account());
        assertEquals(FailureReason.UNKNOWN, result.reason());
        assertEquals("session injection blew up", result.message());
    }

    @Test
    void classifiedFailureKeepsItsReason() {
        LoginResult result = LoginResult.failure("refresh token rejected", FailureReason.REAUTH_REQUIRED);

        assertFalse(result.success());
        assertEquals(FailureReason.REAUTH_REQUIRED, result.reason());
    }
}
