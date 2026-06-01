package lol.trq.alts.vault.federation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import lol.trq.alts.vault.MemberPublicKey;
import org.junit.jupiter.api.Test;

class FederationAddressingTest {

    @Test
    void repoAddressRoundTripsThroughUri() {
        RepoAddress address = new RepoAddress("vault.example:8443", "11111111-2222-3333-4444-555555555555");
        assertEquals("avp://vault.example:8443/11111111-2222-3333-4444-555555555555", address.toUri());
        assertEquals(address, RepoAddress.parse(address.toUri()));
    }

    @Test
    void repoAddressRejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> RepoAddress.parse("https://host/repo"));
        assertThrows(IllegalArgumentException.class, () -> RepoAddress.parse("avp://hostonly"));
        assertThrows(IllegalArgumentException.class, () -> RepoAddress.parse("avp:///repo"));
        assertThrows(IllegalArgumentException.class, () -> RepoAddress.parse("avp://host/"));
        assertThrows(IllegalArgumentException.class, () -> new RepoAddress("", "repo"));
    }

    @Test
    void inviteRequestRoundTripsAsToken() {
        MemberPublicKey member = new MemberPublicKey("ed25519-b64", "x25519-b64");
        InviteRequest invite = InviteRequest.forMember(member);

        InviteRequest decoded = InviteRequest.decode(invite.encode());

        assertEquals(InviteRequest.VERSION, decoded.version());
        assertEquals(member, decoded.toMemberPublicKey());
    }

    @Test
    void inviteRequestRejectsMalformedToken() {
        assertThrows(IllegalArgumentException.class, () -> InviteRequest.decode("!!!not-base64!!!"));
        assertThrows(
                IllegalArgumentException.class,
                () -> InviteRequest.decode(java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("{\"v\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void repoLocatorRoundTripsAndYieldsAddress() {
        RepoAddress address = new RepoAddress("vault.example", "repo-9");
        RepoLocator locator = RepoLocator.of(address, "X25519-HKDF-SHA256-AESGCM-v1", 3L, "https://idp.example/jwks");

        RepoLocator decoded = RepoLocator.decode(locator.encode());

        assertEquals(address, decoded.toRepoAddress());
        assertEquals("X25519-HKDF-SHA256-AESGCM-v1", decoded.schemeId());
        assertEquals(3L, decoded.keyEpoch());
        assertEquals("https://idp.example/jwks", decoded.issuerJwksUrl());
    }

    @Test
    void repoLocatorAllowsNullIssuer() {
        RepoLocator locator = RepoLocator.of(new RepoAddress("h", "r"), "scheme", 0L, null);
        RepoLocator decoded = RepoLocator.decode(locator.encode());
        assertNull(decoded.issuerJwksUrl());
        assertEquals("avp://h/r", decoded.toRepoAddress().toUri());
    }
}
