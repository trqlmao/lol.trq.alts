# Refresh-token authentication

Status: shipped in 0.6.0
Date: 2026-07-28
Release: 0.6.0 (breaking)

The design record for the refresh-token work, kept for its rationale: what each decision traded away
and which alternatives were rejected. It describes the shape as designed, not as an API reference — for
the shipped surface see [CHANGELOG.md](../../CHANGELOG.md) and
[GETTING_STARTED.md](../GETTING_STARTED.md), and for the boundary the sharing policy does *not* cover,
[ARCHITECTURE.md](../ARCHITECTURE.md).

## Problem

The library requests the `XboxLive.offline_access` OAuth scope
(`MicrosoftAuthConfig.DEFAULT_SCOPE`), Microsoft grants a refresh token, and
`MicrosoftAuthUtil.exchangeCodeForToken` parses it into `MsTokens` — where it is
never read again. The token is discarded at the end of step 1.

Two consequences:

1. **No refresh-token login route.** `AltLoginService` exposes Microsoft, cookie,
   session, and offline. A user who holds a refresh token from elsewhere cannot
   import it.
2. **Stored Microsoft accounts expire.** `AltLoginServiceImpl.loginAccount`
   replays the stored Minecraft access token through `loginSession`. Those tokens
   live roughly 24 hours. After that every saved alt is dead and the only recovery
   is a full browser OAuth round per account.

A third, related defect: `attemptFastJwtLogin` decodes the JWT payload for name
and UUID but never checks `exp`. An expired token therefore reports success,
installs a dead session, and fails later at the server with no useful diagnostic.

## Goals

- Add refresh-token login as a first-class route.
- Renew stored Microsoft sessions silently instead of failing at 24 hours.
- Let a shared repository opt in to sharing refresh tokens, defaulting to off.
- Report re-authentication as a machine-readable outcome, not a string.
- Fix the expired-JWT false success.

## Non-goals

- Extracting Microsoft/Xbox authentication into a separate library.
- Device-code flow, or any OAuth grant other than `authorization_code` and
  `refresh_token`.
- Server-side token storage or brokering. The library stays offline-capable and
  network-agnostic outside the flows it already performs.

## Design

### Model

`AltAccount` gains two components:

```java
@SerializedName("refreshToken") String refreshToken,   // null when not applicable
@SerializedName("expiresAt") long expiresAt            // epoch millis; 0 = unknown
```

This changes the record's arity and is therefore breaking. `AltAccount.of(...)`
keeps its existing four-argument shape and fills the new pair with `null` and
`0`. A new `withTokens(String accessToken, String refreshToken, long expiresAt)`
copy method serves the renew path, matching the existing `withBan` / `withSource`
style.

No migration pass is needed in `AltStore.load()`. Accounts written by an earlier
version simply lack both keys, so Gson yields `null` and `0` — which is exactly
the intended reading of "no refresh token, expiry unknown". `normalizeLegacyBans`
remains the precedent if a future field ever does need rewriting.

`AccountType` is unchanged. A refresh token is a property of an account, not a
protocol: an account created through the browser flow acquires one too, so `type`
cannot discriminate. `AccountType.MICROSOFT` covers both entry paths, and
`refreshToken != null` is what enables renewal.

### Authentication

`MicrosoftAuthUtil` currently chains five steps, of which steps 2 through 5
(Xbox Live, XSTS, Minecraft login, profile) are pure functions of the config and
the previous step's output. Those are extracted into one private chain with two
public entry points:

```java
public static CompletableFuture<MinecraftProfile> authenticate(MicrosoftAuthConfig config);
public static CompletableFuture<MinecraftProfile> authenticateWithRefreshToken(
        MicrosoftAuthConfig config, String refreshToken);
```

The new step 1 posts to the same `config.tokenUrl()`:

```
grant_type=refresh_token&refresh_token=<token>&client_id=<id>&scope=<scope>
```

`MsTokens` gains `expiresIn`; `MinecraftProfile` gains `refreshToken` and
`expiresAt` so the rotated token survives into the store.

**Microsoft rotates the refresh token on every redemption.** The response's
`refresh_token` replaces the stored one on success, unconditionally. Persisting
the old value instead leaves the account working once and dead on the second
renewal, which is the failure mode this design exists to remove.

### Login routes

`AltLoginService` gains a fifth method:

```java
CompletableFuture<AltLoginCallback.LoginResult> loginRefreshToken(String refreshToken, LoginMode mode);
```

It fails with `NOT_CONFIGURED` when `microsoftAuth` is `null`, mirroring
`loginMicrosoft`.

`loginAccount` becomes:

| Condition | Behaviour |
| --- | --- |
| `type == OFFLINE` | `loginOffline` (unchanged) |
| `refreshToken == null` | `loginSession` (unchanged; cookie, session, legacy accounts) |
| stored expiry passed, or JWT `exp` passed | renew first, then inject |
| access token rejected downstream | renew once, retry once, then fail |

Expiry is read from `expiresAt` when known and from the token's `exp` claim
otherwise — the fast path already decodes that payload. A 60-second skew margin
is applied so a token about to expire mid-handshake is renewed rather than used.

`attemptFastJwtLogin` gains an `exp` check. An expired JWT no longer short-circuits
to success; it falls through to renewal, or to the existing network validation when
no refresh token is present.

Time is injected as a `java.time.Clock`, package-private with a system default, so
expiry behaviour is testable without sleeping.

### Failure reporting

`LoginResult` gains a typed reason:

```java
enum FailureReason { NONE, UNKNOWN, REAUTH_REQUIRED, INVALID_TOKEN, NETWORK, NOT_CONFIGURED }

record LoginResult(boolean success, String message, AltAccount account, FailureReason reason)
```

`success(AltAccount)` sets `NONE`. The existing `failure(String)` factory is kept
and maps to `UNKNOWN`, so existing call sites keep compiling without being
mislabelled; a `failure(String, FailureReason)` overload is added, and each
existing failure site is then given its correct reason (`NOT_CONFIGURED` for the
unconfigured Microsoft path, `INVALID_TOKEN` for empty or unparseable tokens, and
so on). `UNKNOWN` is the honest default for a failure whose cause the library
cannot classify, such as a host `SessionInjector` throwing. The record's arity changes,
which is breaking, but lands inside the same 0.6.0 major as the model change.

Hosts need this to distinguish "this alt needs a fresh browser login" from "the
network is down". Matching on the message string would be the alternative, and it
breaks under both obfuscation and localization.

### Transient failures must not destroy credentials

`HttpUtil.executeRequest` returns `null` for every non-2xx status and discards the
error body. Wiping the refresh token on any failed renewal would therefore also
wipe it on a transient 503, permanently costing the user an account for a momentary
outage.

The renewal path distinguishes the two:

| Outcome | Stored refresh token | Reason |
| --- | --- | --- |
| 4xx carrying an `invalid_grant` error body | cleared, persisted | `REAUTH_REQUIRED` |
| 408, 429, or any other 4xx | kept | `NETWORK` |
| 5xx, timeout, or connection failure | kept | `NETWORK` |

This requires status visibility. `HttpUtil` gains an additive surface — a
`HttpResponse(int status, JsonObject body)` record and a status-returning form
post — leaving the three existing methods untouched.

Treating every 4xx as permanent was the first shape of this rule, and it was wrong
in two ways that both cost the user their account: Microsoft answers 429 when it
throttles, and 400 `invalid_client` or `invalid_scope` when the *host* is
misconfigured. Neither means the token is spent. Only `invalid_grant` does, so only
`invalid_grant` destroys a credential.

### Shared repositories

`VaultManifest` gains one component:

```java
@SerializedName("shareRefreshTokens") boolean shareRefreshTokens   // default false
```

It is non-secret repository metadata, so it belongs in the manifest alongside
`schemeId` and `keyEpoch`, and the server may hold it. The value flows into
`RepoContext` when the repository is opened.

`SharedVault` strips `refreshToken` and `expiresAt` from every `AltAccount` in the
payload when the flag is false. The strip happens in the private `encrypt(...)`
helper, which both `createRepo` and `encryptPayload` already funnel through — one
choke point, one place to test.

`decryptPayload` strips as well. A member running a modified build could otherwise
push refresh tokens into a repository whose policy forbids them, and every other
member would silently accept and store them. Stripping on read makes the policy
hold regardless of what any peer sends.

**It does not hold against the manifest host.** The flag is served by the sync
server, unsigned and unbound to the payload AAD, so a compromised server can flip
it from false to true; clients that open the repository afterwards build a
permissive context and upload refresh tokens on their next push, and the owner who
deliberately left it off gets no signal. Closing that requires an authenticated
manifest — an owner signature over `repoId`, `keyEpoch`, and the policy — plus
local pinning so a false-to-true transition needs explicit confirmation. That is a
protocol change, tracked with the AVP bump below and deliberately out of scope
here. Until it lands, the policy binds members, not the host.

Default false is deliberate. A Minecraft access token grants roughly a day of
access; a refresh token grants durable access to the Microsoft account until it is
revoked. Sharing one is a materially larger decision than sharing the other, and
it should be made explicitly.

### Protocol

The wire contract is the Alt Vault Protocol, so this needs a spec bump in that
repository:

- `VaultManifest` gains optional `shareRefreshTokens` (boolean, default false).
- The account payload gains optional `refreshToken` (string) and `expiresAt`
  (integer, epoch millis).
- Manifest authentication, so the sharing policy cannot be rewritten by the host
  that serves it. This one is a genuine addition to the threat model rather than a
  field, and it is what the section above defers.

Both are additive and optional, so an implementation that ignores them stays
conformant — a minor version bump. The AVP conformance vectors under
`src/test/resources/avp-vectors/` cover crypto constructions, not payload field
sets, so they are unaffected. That spec change belongs to the AVP repository and
is independent of this library, precisely because neither field is required to
interoperate.

## Testing

Every behaviour the design introduces is exercisable without network access, which is why the clock is
injected and every endpoint is configurable. The cases that matter:

- **Refresh exchange.** A local `com.sun.net.httpserver.HttpServer` bound to a
  loopback port, with `MicrosoftAuthConfig.withEndpoints` pointing at it. Asserts
  the `grant_type=refresh_token` body, that a rotated `refresh_token` in the
  response is the one persisted, and that `expires_in` becomes `expiresAt`.
- **Renewal triggers.** With an injected fixed `Clock`: expired stored expiry
  renews; live expiry does not; expiry inside the skew margin renews.
- **Expired-JWT regression.** A JWT whose `exp` has passed must not produce a
  successful fast-path login.
- **Retry-once.** A rejected access token renews and retries exactly once; a
  second rejection fails rather than looping.
- **Failure classification.** 4xx clears the stored refresh token and reports
  `REAUTH_REQUIRED`; 5xx and connection failure keep it and report `NETWORK`.
- **Serialization.** Round-trip of the widened `AltAccount`, plus a stored
  document written without the new keys deserializing to `null` and `0`.
- **Vault strip.** With the flag false, an encrypted payload contains neither new
  field, and a payload that does contain them is stripped on decrypt. With the
  flag true, both survive a round trip.

## Rejected alternatives

**Extract Microsoft auth into its own library.** Reusable across other hosts, but
it adds a repository, a release cadence, and a dependency edge to solve a problem
entirely contained in this one. Revisit if a second consumer appears.

**Keep refresh tokens local only, in `SecretStore`.** Smallest blast radius and no
protocol change, but a shared alt would still die at 24 hours for every member
except whoever added it, which leaves the main complaint unfixed for the shared
case.

**Per-account share flag instead of per-repository.** Finer-grained, but the
repository cannot enforce a policy, the flag itself travels in the payload, and the
strip logic spreads across accounts instead of sitting at one boundary.

**Reactive renewal only.** No expiry field and no clock, but every stored account
pays a guaranteed-to-fail round trip after 24 hours, and the expired-JWT defect
still needs an independent `exp` check.
