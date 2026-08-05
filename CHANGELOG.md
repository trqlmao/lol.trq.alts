# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.7.0] - 2026-08-05

A login route, a second Microsoft dialect, and a durability pass over the local stores after a full audit
of the library.

### Added

- `AltLoginService.loginCookieFile(Path, LoginMode)`, for authenticating from an exported cookie file
  rather than pasted text. Browser extensions produce cookie exports as files, and the Netscape format
  they use is line-oriented, so pasting one through a single-line input mangles it. Reading is
  byte-order-mark aware: a file redirected out of PowerShell is UTF-16LE, which decoded as UTF-8 yields
  text interleaved with NUL bytes that no parser recognises, and the resulting failure reads as "my
  cookies are bad" rather than "my file is in another encoding". The read runs off the calling thread
  and an unreadable file fails as an `INVALID_TOKEN` result rather than throwing, so a host can hand
  over a path straight from a file picker on its render thread.
- `CookieFile`, the reader behind that route. Public so a host that collects cookie text its own way
  can reuse the size cap and the encoding handling.
- **JSON cookie exports are parsed.** The common cookie-editor extensions write an array of
  `{name, value, domain, ...}` objects rather than the Netscape format, and some nest it under a wrapper
  key. That shape previously fell through to the mangled-text parser, which found the cookie names it
  knows and took the surrounding JSON as their values — producing a header the service rejected and a
  failure that read as bad cookies rather than an unread export. The tree is now walked (to a bounded
  depth), so any wrapper shape works.
- `CookieFile.EXTENSIONS`, the extensions a cookie export normally carries, so a host's file picker and
  this parser agree on what to offer. Advisory only: `read` accepts any path.
- `MicrosoftAuthConfig.legacyMsa(String)`, for authenticating against a *legacy MSA* application
  rather than an Azure OAuth one. The two are different dialects of the same flow and the library
  previously spoke only the OAuth one, so a refresh token issued to a legacy app could not be
  redeemed at all: the token endpoint answered the grant with a flat `400`. Three things differ, and
  all three are load-bearing — the grant must declare the desktop redirect the app registered, it
  must request the `MBI_SSL` scope instead of the delegated Xbox scopes, and the access token that
  comes back is an RPS ticket that Xbox Live wants prefixed `t=` rather than `d=`. A host holding a
  credential minted by a legacy application now has a way to redeem it.
- `MicrosoftAuthConfig.withClientId(String)`, to retarget a config at another application while
  keeping its dialect and endpoints.
- `AltStore.loadError()` and `SecretStore.loadError()` — why the last load could not read an existing
  file, so a host can tell the user their accounts did not load instead of showing them an empty list.
  The two states were previously indistinguishable from the outside.

### Fixed

- **A store that fails to load is no longer overwritten with an empty one.** `load()` swallowed every
  failure and left an empty list, and the next `save()` — which any login triggers — wrote that empty
  list straight over the file. The encryption key is derived from machine properties, so a renamed OS
  user or a moved home directory was enough to reach this, and every stored account was then gone with
  no way back. A failed load now leaves the in-memory collection alone, reports itself on `loadError()`,
  and the file is copied aside to `<name>.unreadable` before any save can replace it. The copy is made
  once, so a second failure cannot overwrite the backup that holds the data, and a save that cannot make
  the copy is skipped rather than allowed to destroy the original. Applies to both `AltStore` and
  `SecretStore`.
- **The stores are safe to touch off-thread.** The account list was a plain `ArrayList` and the secret
  map a `LinkedHashMap`, both mutated from whichever pool thread a login resolved on while the host reads
  them from its render thread. They are now copy-on-write and concurrent respectively, so iterating
  `accounts()` during a login sees a snapshot instead of failing.
- **`AltStore.removeAccount` keys on the UUID** like every other mutator, rather than on whole-record
  equality plus a reference comparison for the current account. Since `AltAccount` is a record, its
  equality covers the credentials too, so a caller holding a copy read before a renewal rotated its
  tokens — the normal case — silently removed nothing.
- **The session route separates a refused token from an unreachable service.** `loginSession` collapsed
  every failure onto `INVALID_TOKEN`, so an outage was reported as a spent credential and a host
  following the documented advice to branch on the reason sent the user through a fresh interactive
  login to fix a network problem. It now reports `NETWORK` for a transport failure, matching what
  `loginAccount` already did.
- **The authorization code is decoded before it is redeemed.** The callback server matches the raw query
  string, so the captured code and state were still percent-encoded; the token exchange then encoded
  every parameter it sent, escaping those escapes and redeeming a value the service never issued.
  Current codes happen to be URL-safe, so this had not yet bitten.
- **A rejected HTTP response has its body drained.** An unread error body keeps its connection out of the
  keep-alive pool, so a run of rejections — a rate limit, a service having a bad minute — opened a fresh
  socket every time.
- **The cookie file's size cap binds the read itself**, rather than a size check taken before it. Reading
  a file to find out it was too big is the thing the cap exists to prevent, and a file that grew between
  the check and the read slipped it entirely. The read now stops one byte past the limit.
- **A cookie-file failure names the file, never the path to it.** The JDK's I/O messages are the absolute
  path, and this message travels into a `LoginResult` a host shows in its UI and writes to its log, so an
  access-denied on an export under a home directory published that directory.
- A null username handed to `loginOffline` reports `INVALID_TOKEN` rather than `UNKNOWN`, and
  `cleanToken` has its Javadoc back after it was orphaned by the refresh-token cleaner landing above it.

### Security

- **The OAuth state can no longer fall back to a guessable value.** `generateState` answered a
  `SecureRandom.getInstanceStrong()` failure with `"fallback-state-" + currentTimeMillis()`, which
  defeats the CSRF check the state exists to make: an attacker who can guess the state can feed the
  loopback callback a code of their choosing. `getInstanceStrong` also maps to a blocking entropy source
  on some platforms, so the failure it guards against is real rather than theoretical. The default
  `SecureRandom` is used instead — cryptographically strong, non-blocking, and unable to fail to
  construct, so there is nothing to fall back from.
- The browser fallback passes its URL as a discrete argument rather than as one command line, which
  `Runtime.exec` splits on whitespace.
- `SECURITY.md` now states the threat model explicitly. The local store's PBKDF2 password is derived from
  machine properties, not from a user secret: it stops a copied file being read on another machine, and
  does nothing against code already running as the same user. That was true before and is now written
  down, along with the shared vault's two documented boundaries.

### Changed

- `MicrosoftAuthConfig` gains a `redirectUri` and an `rpsTicketPrefix` component. Both default to the
  previous behaviour — no redirect on the refresh grant, a `d=` ticket — so an OAuth host sees no
  change on the wire. Hosts calling the canonical constructor rather than `of(...)` must pass the two
  new arguments.
- **`AltsRuntime.Builder.build()` now loads both stores.** It bound the directory provider and configured
  the filename but never read the file, so a host that followed the getting-started guide wired
  everything correctly and still saw an empty account list — the extra call it needed was documented
  nowhere. A host already calling `AltStore.load()` itself is unaffected, since a load clears its
  in-memory state before refilling it.

## [0.6.2] - 2026-07-28

### Fixed

- `loginRefreshToken` now sanitizes the token it is given. Refresh tokens are pasted, and they are
  routinely pasted out of account lists that prefix them with a name (`name:token`), out of an
  `Authorization` header, or out of a quoted config value. `loginSession` has always stripped that
  surrounding text; the refresh route did not, so it went to the token endpoint as part of the
  credential and came back as an invalid grant. In a password-style input the stray prefix is
  invisible, so the failure was indistinguishable from a genuinely dead token. Note that
  `loginSession`'s existing cleaner could not have covered this: it selects the `eyJ`-prefixed segment
  of a JWT and cannot recognise a refresh token.

## [0.6.1] - 2026-07-28

### Added

- `AltsRuntime.Builder.vaultTransportResolver(VaultTransportResolver)` and the matching
  `AltsRuntime.vaultTransportResolver()` accessor. The `VaultTransportResolver` SPI interface shipped in
  an earlier release with no way to install one, so the seam was unreachable: a host could implement the
  interface but never hand it to the runtime, leaving federated repositories (`avp://host/repoId`)
  resolvable in principle and unusable in practice. The wiring existed only on an unmerged branch, which
  consumers were pinning to directly. It now ships on the release line.

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
- `AltAccount.mergedOnto(AltAccount existing)` — applies this account's identity and credentials onto a
  record the store already holds, so re-authenticating an alt refreshes what it logs in with while its
  bans, provenance, and shared attribution survive. A route that issues no refresh token leaves the
  stored one in place rather than clearing it.
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
