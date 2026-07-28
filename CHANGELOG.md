# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.6.0] - 2026-07-28

Refresh tokens become a first-class credential: importable as a login route, redeemable to renew a stored
session silently, and shareable into a repository only when that repository opts in.

### Added

- **Refresh-token login route** — `AltLoginService.loginRefreshToken(refreshToken, mode)` redeems a stored
  OAuth refresh token for a live session, skipping the interactive browser step entirely.
- **Silent renewal in `loginAccount`** — a stored Microsoft account no longer dies with its access token.
  It renews from its refresh token proactively (when the expiry says the token is spent) and once
  reactively (when a token that looked live is refused, as after a password change), persisting the
  rotated credential before installing the session. Renewal never loops.
- `MicrosoftAuthUtil.authenticateWithRefreshToken(config, refreshToken)` — starts the existing Xbox Live /
  XSTS / Minecraft services chain from a `grant_type=refresh_token` exchange instead of an authorization
  code. Failures surface as `MicrosoftAuthUtil.RefreshRejectedException`, whose `permanent()` separates a
  token that will never work again (4xx) from a failure worth retrying (5xx or transport), so an outage
  never costs a user a working credential.
- `AltLoginCallback.FailureReason` (`NONE`, `UNKNOWN`, `REAUTH_REQUIRED`, `INVALID_TOKEN`, `NETWORK`,
  `NOT_CONFIGURED`) with a matching `LoginResult.failure(String, FailureReason)` factory, so a host can
  branch on the outcome instead of matching a human-readable message that obfuscation and localization
  both change.
- `TokenExpiry` — expiry arithmetic for access tokens: `SKEW_MILLIS`, `jwtExpiryMillis(String)`, and
  `isExpired(AltAccount, Clock)`. The account's stored expiry wins when known, the token's own `exp` claim
  is the fallback, and a wholly unknown expiry reads as expired, since renewing a live session is cheap and
  installing a dead one is not.
- `AltAccount.withTokens(accessToken, refreshToken, expiresAt)` and `AltAccount.hasRefreshToken()`.
- `AltStore.updateCredentials(AltAccount)` and `AltStore.clearRefreshToken(String uuid)` — persisting
  mutators, so a rotated token reaches disk and a spent one does not stay at rest.
- `HttpUtil.HttpResponse` (status plus parsed body, with `successful()`) and `HttpUtil.postFormForStatus`,
  which keeps the error body so a caller can classify a rejection rather than collapsing every non-2xx to
  `null`.
- `AccountNetworkUtil.fetchProfileFromToken(String token, String profileUrl)` — a host fronting Minecraft
  services with its own proxy now validates through the same route it authenticates through. The
  one-argument overload is unchanged and delegates to the public default.
- **Per-repository refresh-token sharing policy** — `SharedVault.createRepo(identity, alts,
  shareRefreshTokens)` alongside the existing two-argument form, backed by `shareRefreshTokens` on
  `VaultManifest` and `RepoContext`. Repositories withhold refresh tokens unless the manifest opts in, and
  the strip runs at the single encrypt/decrypt choke point on **both** write and read — on write so the
  credential never reaches the server, and on read so a peer running a modified build cannot push tokens
  into a repository whose policy forbids them.
- AVP conformance-vector tests (`AvpConformanceVectorsTest`). The crypto primitives and envelope
  compositions are now gated byte for byte against the published Alt Vault Protocol vectors, vendored under
  `src/test/resources/avp-vectors/` from `trqlmao/avp`: HKDF, X25519, Ed25519, the AAD and
  key-binding-message constructions, the payload AEAD, the key wrap, and the MUST-reject negative bank. The
  round-trip tests proved self-consistency; these prove interoperability with every other AVP
  implementation, so a silent change to a construction is caught.

### Fixed

- **Logging into a stored account no longer strips it.** `loginAccount` routed any account it could not
  renew — one carrying no refresh token, or any account at all when Microsoft login is unconfigured —
  through the session route, which rebuilt the record as `AccountType.SESSION` with no refresh token and
  then replaced the stored entry with that poorer copy. A non-offline account that is already stored is
  now installed as it stands, so its type, refresh token, bans, and provenance survive the login.
- **A rate-limited renewal no longer destroys the refresh token.** Redemption failures were classified as
  permanent on any 4xx, so an HTTP 429 (throttling) or 408 (request timeout) deleted a perfectly good
  credential, as did `invalid_client` / `invalid_scope`, which report a misconfigured host rather than a
  spent token. Only a stated `invalid_grant` outside those statuses is permanent now; everything else is
  transient and the credential is kept.
- **Re-authenticating a stored alt no longer resets what the store knows about it.** `AltStore.addAccount`
  replaced an existing same-UUID entry outright, so importing a refresh token for an alt the user already
  had wiped its observed bans, provenance, and shared attribution. The incoming credentials are now merged
  onto the stored record instead, via the new `AltAccount.mergedOnto(AltAccount)`.
- **A host-side failure no longer spends a refresh-token rotation.** `loginAccount`'s reactive renewal
  fired on any unsuccessful outcome from the stored-token attempt, including a `SessionInjector` that
  threw and an unreachable service. Since the token endpoint rotates the refresh token on every
  redemption, a broken injector cost the user a rotation on every attempt. Renewal is now gated on the
  failure being `INVALID_TOKEN`.
- **Records carrying credentials no longer print them.** `AltAccount`, `SessionData`, and
  `MinecraftProfile` are records, so their generated `toString` emitted the access and refresh tokens
  verbatim — and `LoginResult` embeds an `AltAccount`, so a host logging a login outcome wrote a durable
  credential to its log file. All three now redact the token fields and keep the identity fields
  readable.
- **Every HTTP request now has a deadline.** Neither executor set a connect or read timeout, so an
  endpoint that accepted a connection and then went quiet held a common-pool thread for the life of the
  process and left the login future to never complete. Connections time out after 10s connecting and 30s
  reading.
- **Redirects are no longer followed.** `HttpURLConnection` replays the caller's request properties to
  the redirect target, so a `3xx` from a profile or token endpoint sent the `Authorization: Bearer`
  header to whatever host the `Location` named. Redirect following is off on both executors; a `3xx` is
  now reported to the caller like any other non-2xx.
- **The Microsoft client id is URL-encoded** in both the authorization-code and refresh-token exchanges.
  It was interpolated raw while every other parameter was encoded, so a client id containing `&` or `=`
  injected extra form parameters into the token request.
- **Expired tokens no longer report a successful login.** The fast JWT path read a token's name and UUID
  claims without ever inspecting `exp`, so an expired token reported success, installed a dead session, and
  failed later at the server with no useful diagnostic. It now falls through to validation or renewal.
- Corrected the Alt Vault Protocol spec link in the README (`trqlmao/avp-spec` to `trqlmao/avp`).

### Security

- **Known limitation: the refresh-token sharing policy binds members, not the repository host.**
  `shareRefreshTokens` is plain manifest metadata the server serves, with no signature over it and no
  binding into the payload AAD, so a malicious or compromised host can serve `true` for a repository
  created withholding and every member will honour it. The docs previously claimed the policy held
  unconditionally; they now state the boundary. Authenticating the manifest is follow-up work.
- **Opting a repository into refresh-token sharing is effectively irreversible.** `removeMember` and
  `rotateKey` re-key future payloads, but a refresh token that already reached a member's disk cannot be
  clawed back by any protocol message — only by revoking the account at the identity provider. Now
  documented in `docs/GETTING_STARTED.md`.

### Changed

- **Breaking:** `AltAccount` gains two trailing components, `refreshToken` and `expiresAt`, so positional
  construction of the record must supply them (`AltAccount.of` is unchanged). Both are absent on accounts
  written by earlier versions, which reads as "no refresh token, expiry unknown" and therefore renews on
  first use — existing `accounts.dat` files load unchanged.
- **Breaking:** `AltLoginCallback.LoginResult` gains a trailing `FailureReason` component. The existing
  `LoginResult.failure(String)` is retained and maps to `UNKNOWN`, so only direct positional construction
  of the record breaks.
- **Breaking:** `MinecraftProfile` gains `refreshToken` and `expiresAt` components. The stamped expiry
  comes from the Minecraft services token (roughly a day), not the Microsoft OAuth token (roughly an
  hour), so a renewed account is not marked expired twenty-three hours early.
- **Breaking:** `AltLoginService` gains an abstract `loginRefreshToken` method, so any host implementing
  the interface directly must add it. Hosts using `AltsRuntime` are unaffected.
- **Breaking:** `VaultManifest` and `RepoContext` each gain a trailing `shareRefreshTokens` component.
  `SharedVault.createRepo` keeps its two-argument form, which withholds. A manifest written by an earlier
  version carries no such key and deserializes to `false`, which is the safe reading.

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
