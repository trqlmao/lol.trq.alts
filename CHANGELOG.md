# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- AVP conformance-vector tests (`AvpConformanceVectorsTest`). The crypto primitives and envelope
  compositions are now gated byte for byte against the published Alt Vault Protocol vectors, vendored under
  `src/test/resources/avp-vectors/` from `trqlmao/avp`: HKDF, X25519, Ed25519, the AAD and
  key-binding-message constructions, the payload AEAD, the key wrap, and the MUST-reject negative bank. The
  round-trip tests proved self-consistency; these prove interoperability with every other AVP
  implementation, so a silent change to a construction is caught.

### Fixed

- Corrected the Alt Vault Protocol spec link in the README (`trqlmao/avp-spec` to `trqlmao/avp`).

## [0.5.0] - 2026-06-01

### Changed

- **Breaking:** bans are now **per-server**. `AltAccount.ban: BanInfo` is replaced by `bans: Map<serverId, BanInfo>`; `banned()` now means "banned on any server" and `banned(String serverId)` checks one server; `bannedServers()` returns the banned server ids; `withBan(BanInfo)` becomes `withBan(String serverId, BanInfo)`; and `AltStore.markBanned(uuid, ban)` becomes `markBanned(uuid, serverId, ban)`. `serverId` is an opaque, host-supplied string (the same namespace as game stats) — the library performs no server detection. Old `accounts.dat` files are migrated on load: a single legacy `ban` becomes `bans = { "unknown": ban }`, so existing badges are preserved.

## [0.4.0] - 2026-06-01

### Changed

- **Breaking (crypto):** the `X25519-HKDF-SHA256-AESGCM-v1` key-wrap scheme's HKDF `info` (also the wrap GCM AAD) changed from the library-namespaced `lol.trq.alts/rdk-wrap/v1` to the vendor-neutral **`avp/rdk-wrap/v1`**, matching the published Alt Vault Protocol scheme so the wrap interoperates with any conformant implementation. Wrapped keys produced by 0.2.0/0.3.0 cannot be unwrapped by 0.4.0 and vice versa. This is safe in practice because no shared vault has been deployed yet. The change is a single constant in `X25519HkdfAesGcmKeyWrap`; nothing else moves.

## [0.3.0] - 2026-06-01

### Changed

- **Breaking:** replaced the Hypixel-specific stats surface with a server-agnostic one. `GameStats` (an ordered list of `Stat` display chips) replaces `HypixelStats` / `BedwarsStats` / `SkywarsStats`; `GameStatsSource` (with a `serverId()`) replaces `HypixelStatsSource`; and `AltsRuntime.hypixelStats()` becomes `gameStats(String serverId)`, backed by one cache per registered source. A host registers a source per server via `Builder.gameStatsSource(...)` (a duplicate server id throws). Migrate by formatting your stats into `GameStats.Stat` chips and reading them back with `gameStats(yourServerId)`.

### Added

- `StaticGameStatsSource`, a fixed in-memory `GameStatsSource` for demos and tests.
- A compiled `examples/` source set (`ExampleNetGameStatsSource`) so examples cannot drift from the API.

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
