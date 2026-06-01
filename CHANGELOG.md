# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-06-01

### Added

- **Shared vault** (`lol.trq.alts.vault`, `lol.trq.alts.crypto`) — a zero-knowledge shared alt repository: Ed25519 identity + X25519 key-agreement (`VaultIdentity`), AES-256-GCM payloads with replay-binding AAD (`PayloadCipher`), a pluggable `KeyWrapScheme` (default `X25519HkdfAesGcmKeyWrap`), and `SharedVault` — pure client-side crypto for create / open / encrypt / decrypt / rotate-on-removal over plain transport DTOs. The server (a host-supplied `VaultTransport`) sees only ciphertext, wrapped keys, public keys, and counters.
- **Federation** (`lol.trq.alts.vault.federation`) — `RepoAddress` (`avp://host/repoId`), `InviteRequest`, and `RepoLocator` make repositories reachable across independently-hosted servers using one portable identity. New `VaultTransportResolver` SPI seam maps a host authority to a transport, so a member pointed at one server can join a repository hosted on another.
- **Provenance** — `AltAccount` gains `sourceClient` / `sourceUser`: which client a shared alt was added from and the user within it. Carried inside the encrypted payload only (the server never sees them). `AltAccount.withSource(client, user)` stamps them.
- **Anti-MITM** — `MemberEntry` gains an optional `keyBindingSig`, and `IssuerSignedKeyBindingVerifier` checks a member's served public keys against an identity-provider signature before wrapping a data key to them — for joining repositories on servers you do not operate.
- `MicrosoftAuthConfig` — the Microsoft client id, scope, redirect path, and every service endpoint are now host-supplied; wire it via `AltsRuntime.Builder.microsoftAuth(...)`.

### Changed

- **Breaking (pre-1.0):** `MicrosoftAuthUtil.authenticate()` now takes a `MicrosoftAuthConfig`. There is no built-in Microsoft client id any more — a host must supply its own Azure app id to enable Microsoft login (offline / cookie / session login are unaffected). Hosts using `AltsRuntime` only need to add `.microsoftAuth(MicrosoftAuthConfig.of(yourClientId))`.

## [0.1.0] - 2026-05-29

### Added

- Initial release.
- **Auth** (`lol.trq.alts.auth`) — Microsoft OAuth 2.0, browser-cookie, session-token, and offline login behind `AltLoginService`, all `CompletableFuture`-based.
- **Store** (`lol.trq.alts.store`) — `AltStore`, an AES-256-GCM + PBKDF2 encrypted local account file; filename and key-binding are host-configurable.
- **Cache** (`lol.trq.alts.cache`) — `AsyncCache<K,V>`, a lazy non-blocking value cache with stale-while-revalidate TTL.
- **Skin** (`lol.trq.alts.skin`) — `SkinAvatarCache<H>`, async player-head fetch/cache generic over the host texture-handle type.
- **Model** (`lol.trq.alts.model`) — `AltAccount`, `AccountType`, `LoginMode`, `SessionData`, `HypixelStats`, `BanInfo`.
- **SPI** (`lol.trq.alts.spi`) — host seams: `SessionInjector`, `VaultDirectoryProvider`, `TextureUploader<H>`, `MainThreadExecutor`, `ToastSink`, `HypixelStatsSource`.
- `AltsRuntime` wiring root with an accumulating builder.
- Spotless / palantir-java-format toolchain, JUnit 5 tests, GitHub Actions CI, and JitPack publishing manifest.
