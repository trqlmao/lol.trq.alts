# Alt-management surface

Status: §A, §B, §D shipped in 0.8.0; §C, §E, §F proposed
Date: 2026-08-05
Releases: 0.8.0 (breaking), 0.9.0 (breaking), 0.10.0

**Two things changed on the way into 0.8.0, both recorded here rather than quietly.** `AccountStatus.State`
gained `EXPIRED`: writing `check` showed that a read-only check on a refused token cannot report
`REAUTH_REQUIRED`, because an account still holding a refresh token needs renewing rather than a fresh
browser login, and those are different user actions. And the rate-limit backoff described under §A moved
to 0.9.0, where the bulk operations that drive it land — a public utility with no caller is worse than
one shipped alongside its consumer. `HttpResponse.retryAfter`, the input it needs, shipped now.

The design record for closing the gap between "a library that logs an account in" and "a library a full
alt manager can be built on". Five pieces, specced together because three of them share a dependency
edge: the transport work underpins avatars and bulk, and the account-operations work underpins bulk.

As with [the refresh-token record](2026-07-28-refresh-token-auth.md), this describes the shape as
designed and the reasoning behind it, not an API reference.

## Problem

The library authenticates well and manages accounts poorly. Everything it does assumes one account, one
login, one machine, one moment.

1. **No proxy support.** `HttpUtil` opens direct connections and nothing can change that. Microsoft and
   Mojang rate-limit by source address, so validating fifty alts from one IP gets throttled, and an alt
   manager that assigns a proxy per account — the normal arrangement — cannot be built on this at all.
   `SkinAvatarCache` and `CookieAuthUtil` compound it by opening their own connections, bypassing even
   the timeouts and the redirect policy that `HttpUtil` enforces.
2. **Logging in is the only way to touch an account.** `loginAccount` always ends at
   `SessionInjector.inject`. A "check all my alts" button, a background refresh, or a bulk import
   therefore cannot exist without hijacking the live session on every account it touches.
3. **Identity goes stale and avatars are keyed on it.** Nothing refreshes a stored username after a name
   change, and `SkinAvatarCache` is keyed by username — so a renamed alt shows the wrong name *and* a
   permanently broken head. The avatar URL is a hardcoded third-party service, which also means every
   alt's username is disclosed to that service, and a failed fetch is cached as `FAILED` forever.
4. **An unentitled account fails as a mystery.** An account without Minecraft passes `login_with_xbox`
   and then 404s at the profile call, which the library reports as "Profile fetch failed". A host cannot
   tell "this account does not own the game" from "the service is unwell".
5. **Nothing is bulk-aware.** No import of a credential list, no concurrency control, no backoff on 429,
   no progress, no cancellation. A host writing its own loop over `loginAccount` gets rate-limited on
   account nine and has no way to tell why.

A sixth, adjacent: the interactive flow needs a loopback socket. Headless hosts, locked-down firewalls,
and legacy MSA applications (whose registered redirect the callback server cannot serve) have no
interactive route at all.

## Goals

- Let a host route any request through a proxy it chooses, per account.
- Separate *operating on* an account from *logging into* one.
- Keep stored identity current, and key avatars on something that does not change.
- Report a missing entitlement as a machine-readable outcome.
- Make bulk import and bulk validation first-class, with throttling, progress, and cancellation.
- Add an interactive route that needs no inbound socket.

## Non-goals

- **A file picker, or any UI.** Choosing a file, showing a progress bar, and rendering a device code are
  host concerns; the library takes a `Path`, a callback, and an interface. This is settled and not
  revisited below.
- Proxy *acquisition*, rotation policy, or health checking. The library asks the host for a route and
  uses it. Which proxy, and whether it is alive, is the host's business.
- A scheduler. Bulk operations run when called; the library never wakes itself up.
- Server-side brokering, or storing anything a host has not asked it to store.

---

## A. Transport and proxies

**Release 0.8.0.** The foundation the rest sits on.

### The seam

```java
package lol.trq.alts.spi;

@FunctionalInterface
public interface ProxyProvider {
    /**
     * @param scope what is about to be fetched, and on whose behalf
     * @return the route to use; ProxyRoute.direct() for no proxy
     */
    ProxyRoute routeFor(NetworkScope scope);
}
```

```java
package lol.trq.alts.net;

public record NetworkScope(Purpose purpose, String accountUuid, String accountUsername) {
    public enum Purpose { AUTH, PROFILE, AVATAR, STATS, VAULT }
}

public record ProxyRoute(Kind kind, String host, int port, String username, char[] password) {
    public enum Kind { DIRECT, HTTP, SOCKS5 }

    public static ProxyRoute direct();
    public static ProxyRoute http(String host, int port);
    public static ProxyRoute socks5(String host, int port);
    public ProxyRoute withCredentials(String username, char[] password);
}
```

Wired as `AltsRuntime.Builder.proxyProvider(...)`, optional; absent means everything is direct, which is
today's behaviour exactly.

`NetworkScope` carries the account rather than just a purpose because per-account proxying is the
point — one IP per alt is what keeps a bulk validation from looking like one machine hammering the
service. `accountUuid` is null for the routes that have no account yet (a fresh Microsoft login, a cookie
import), and a host that proxies those assigns from its own pool.

**The proxy does not live on `AltAccount`.** That was the obvious alternative and it is wrong twice over:
a proxy is infrastructure rather than account identity, and — decisively — `AltAccount` is what travels
into a shared vault repository. Putting a paid rotating proxy's host, port, and credentials on the record
would publish them to every member of every repository the alt is shared into. The seam keeps them
host-side, where they belong.

### Threading it through

`HttpUtil`'s three public methods gain scope-carrying overloads; the existing signatures are kept and
delegate with a direct route, so nothing outside breaks:

```java
public static JsonObject get(String url, Map<String,String> headers, NetworkScope scope) throws Exception;
public static JsonObject postJson(String url, Map<String,String> headers, String body, NetworkScope scope) throws Exception;
public static HttpResponse postFormForStatus(String url, Map<String,String> headers, String body, NetworkScope scope) throws Exception;
```

Internally, `openConnection(Proxy)` replaces `openConnection()`. `HttpUtil` gains a package-private
`Transport` holding the installed `ProxyProvider`, bound by `AltsRuntime.Builder.build()` alongside the
stores.

`CookieAuthUtil.followRedirect` and `SkinAvatarCache.downloadBytes` stop opening their own connections
and move onto `HttpUtil`, which is the only way they pick up the proxy — and incidentally the only way
they pick up the finite timeouts and the no-redirect policy they currently lack. `CookieAuthUtil` needs a
raw-header GET that `HttpUtil` does not expose today, so `HttpUtil` gains a package-private
`headerOnly(...)` for it.

### Proxy authentication, and one honest limitation

For an `HTTP` route with credentials, the library sets `Proxy-Authorization: Basic …` on the connection
itself. Per-connection, no global state, works.

For a `SOCKS5` route with credentials it cannot. The JDK's SOCKS client reads its credentials from the
`java.net.socks.username`/`password` system properties or the default `java.net.Authenticator`, both of
which are process-global — and a library that installs a global authenticator has reached into its host's
JVM to do it. **SOCKS5 without authentication is supported; SOCKS5 with authentication is not**, and
`ProxyRoute.socks5(...).withCredentials(...)` throws `UnsupportedOperationException` rather than silently
connecting unauthenticated. A host needing authenticated SOCKS5 should front it with a local
unauthenticated HTTP proxy, which is what the tooling in this space does anyway.

Migrating to `java.net.http.HttpClient` would fix that — its authenticator is per-client, so one client
per route solves credentials cleanly. It does not support SOCKS proxies **at all**, so the fix would
trade authenticated SOCKS5 for no SOCKS5. Rejected for that reason; revisit if the JDK closes it.

### Rate limiting

```java
package lol.trq.alts.net;

public final class Backoff {
    public static Duration afterRateLimit(HttpResponse response, int attempt);  // honours Retry-After
    public static Duration exponential(int attempt, Duration base);             // with jitter
}
```

Buckets are keyed by `ProxyRoute`, since the limit is per source address: one throttled route must not
stall the others. Individual routes do not retry on their own — a login the user is watching should fail
fast — but bulk operations (§E) drive this.

---

## B. Operating on an account without logging into it

**Release 0.8.0.**

```java
package lol.trq.alts.auth;

public interface AltAccountService {
    /** Validates the stored token. Never renews, never rotates, never injects. */
    CompletableFuture<AccountStatus> check(AltAccount account);

    /** Validates and, if needed, renews from the refresh token, persisting the rotation. Never injects. */
    CompletableFuture<AccountStatus> refresh(AltAccount account);
}

public record AccountStatus(AltAccount account, State state, FailureReason reason, String message) {
    public enum State { VALID, RENEWED, REAUTH_REQUIRED, NOT_ENTITLED, UNREACHABLE, UNKNOWN }

    public boolean usable();   // VALID or RENEWED
}
```

Reached as `alts.accountService()`. `account()` carries the record as it now stands — renewed tokens, and
a corrected username per §C — so a caller updates its view from one place.

The point is the decomposition. `loginAccount` becomes:

```java
refresh(account).thenApply(status -> status.usable() ? inject(status.account()) : toLoginResult(status));
```

Injection stops being baked into the middle of the renewal chain and becomes the last step, which is what
makes every other feature here possible: bulk validation is `refresh` over a list, a background token
sweep is `refresh` on a timer, and "is this alt still good?" is `check`. None of them touch the session.

`check` exists separately from `refresh` because rotation is not free. Microsoft rotates the refresh token
on every redemption, so a validation sweep that renews unnecessarily spends a rotation per account per
sweep. `check` answers the read-only question; `refresh` is for when the answer needs to be "and make it
work".

---

## C. Identity that stays current

**Release 0.9.0** — breaking.

### Usernames

`check` and `refresh` already call the profile endpoint, whose response carries the current `name`. When
it differs from the stored one, the record is updated and persisted through `AltStore.updateCredentials`.
Free, and it closes the stale-name hole without a single extra request.

`AltAccount` gains `withUsername(String)`, matching the existing copy-method style.

### Avatars

Two changes, both breaking:

**Keyed by UUID.** `SkinAvatarCache.get(String username)` becomes `get(String uuid)`. A username is a
mutable display attribute; keying a cache on it means a rename produces a permanently broken head. The
UUID is the identity.

**The source becomes a seam.**

```java
package lol.trq.alts.spi;

@FunctionalInterface
public interface AvatarSource {
    byte[] avatarPng(String uuid, int sizePx) throws Exception;
}
```

Default is a new `MojangSkinAvatarSource`: fetch the profile from the session server, decode the
`textures` property, download the skin PNG, and crop the 8×8 face with its hat overlay via `ImageIO`.
No new module dependency — `java.desktop` is already pulled in by `Desktop.browse` in the browser
launcher.

This replaces a hardcoded `https://mc-heads.net/avatar/…`, which is wrong for a library that claims to be
vendor-neutral, and worse than wrong for an alt manager: it disclosed every alt's username to a third
party on every cache miss. Hosts that prefer a head-rendering service can install
`UrlTemplateAvatarSource`, which is shipped for exactly that and is opt-in rather than default.

**Failures stop being permanent.** The `FAILED` sentinel becomes `FAILED(attempts, notBefore)`, retried
with `Backoff.exponential`. A network blip currently costs the avatar for the rest of the process.

---

## D. Entitlement as an outcome

**Release 0.8.0.** Small, and it rides along with the profile work in §B.

`FailureReason` gains `NOT_ENTITLED`; `AccountStatus.State` has the matching member.

The profile lookup becomes status-aware. `AccountNetworkUtil` gains:

```java
public record ProfileLookup(int status, String username, String uuid) {
    public boolean found();
}

public static ProfileLookup fetchProfile(String token, String profileUrl, NetworkScope scope) throws Exception;
```

The existing `String[]`-returning methods are kept, delegating, and deprecated — they cannot express this,
since they collapse 401 and 404 to the same `null`.

| Status | Meaning | Reported as |
| --- | --- | --- |
| 200 with `id` + `name` | good | `VALID` |
| 401 / 403 | token refused | `INVALID_TOKEN`, then renewal |
| 404 | authenticated, but no Minecraft profile | `NOT_ENTITLED` |
| 5xx, timeout, connection failure | service | `NETWORK` / `UNREACHABLE` |

One honest ambiguity: 404 also covers an account that *owns* the game but has never chosen a username. The
message says "no Minecraft profile" rather than "does not own Minecraft" for that reason. Separating the
two needs `GET /entitlements/mcstore`, an extra request per account that only matters for a case a user
resolves by picking a name — deliberately deferred, and worth adding to `check` behind a flag if it ever
proves to matter.

---

## E. Bulk operations

**Release 0.9.0.** Depends on §B, which is what makes a bulk run possible without switching sessions
fifty times.

New package `lol.trq.alts.bulk`:

```java
public record BulkOptions(
        int concurrency,          // default 4
        Duration minSpacing,      // default 250ms between starts, per proxy route
        int maxRetries,           // default 2, on NETWORK and 429 only
        Duration retryBaseDelay,  // default 2s, exponential with jitter
        boolean stopOnRateLimit)  // default true
{
    public static BulkOptions defaults();
}

public interface BulkProgress {
    void started(int index, int total, String label);
    void completed(int index, int total, BulkEntryResult result);
    default void finished(BulkReport report) {}
}

public record BulkEntryResult(
        int index, String label, boolean success,
        AltAccount account, FailureReason reason, String message) {}

public record BulkReport(List<BulkEntryResult> results, boolean cancelled, boolean stoppedEarly) {
    public int succeeded();
    public List<BulkEntryResult> failures();
}

public interface BulkHandle {
    CompletableFuture<BulkReport> report();
    void cancel();
}
```

Two operations, from `alts.bulk()`:

```java
BulkHandle importCredentials(List<String> entries, LoginMode mode, BulkOptions options, BulkProgress progress);
BulkHandle refreshAll(List<AltAccount> accounts, BulkOptions options, BulkProgress progress);
```

Neither injects a session. `importCredentials` with `LoginMode.ADD` resolves and stores; the host decides
afterwards which account, if any, to log into.

`stopOnRateLimit` defaults to true because the alternative is worse than it looks: continuing to hammer a
throttling endpoint with forty more accounts is how a source address earns a longer ban. Stopping and
reporting `stoppedEarly` lets the host tell the user to wait, or to add proxies.

**A label, never a credential.** `BulkEntryResult.label` is the resolved username, or a positional
`"entry 12"` before one is known. It is never the input line — that line is a credential, and results are
exactly what a host logs.

### Detecting what a line is

```java
package lol.trq.alts.bulk;

public enum CredentialKind {
    REFRESH_TOKEN, SESSION_TOKEN, COOKIE_TEXT, OFFLINE_NAME, UNKNOWN;

    public static CredentialKind detect(String entry);
}
```

Ordered most to least specific: a `M.`-prefixed or `name:M.…` value is a refresh token; `eyJ` is a session
JWT; text carrying `login.live.com` or tab-separated cookie columns is cookie text; a bare string of at
most 16 word characters is an offline name; anything else is `UNKNOWN` and fails that entry rather than
guessing. Public because a host wants to preview a paste before running it, and because guessing wrong on
a credential should be visible rather than silent.

---

## F. Device-code login

**Release 0.10.0.** Independent of everything above; last because it is the least blocking.

```java
CompletableFuture<LoginResult> loginDeviceCode(DeviceCodePrompt prompt, LoginMode mode);
```

```java
package lol.trq.alts.spi;

public interface DeviceCodePrompt {
    void show(String userCode, String verificationUri, Duration expiresIn);
    default void dismiss() {}
}
```

`MicrosoftAuthConfig` gains `deviceCodeUrl`, defaulting to the consumer device-authorization endpoint.
The flow requests a device code, hands the user code and URL to the host to display, then polls the token
endpoint with `grant_type=urn:ietf:params:oauth:grant-type:device_code`, honouring the advertised
`interval` and backing off on `slow_down`, until it succeeds, the user declines, or the code expires.

It earns its place three times over: a headless host has no browser, a locked-down machine may have all
five loopback ports refused, and a legacy MSA application declares a desktop redirect the callback server
cannot serve — so `legacyMsa` configs currently have no interactive route at all, only refresh-token
import. Requires the Azure application to be registered as a public client with device-code flow enabled,
which the getting-started guide must say.

---

## Breaking changes

| Change | Release | Migration |
| --- | --- | --- |
| `FailureReason` gains `NOT_ENTITLED` | 0.8.0 | exhaustive `switch` over the enum gains a case |
| `AccountNetworkUtil.fetchProfileFromToken` deprecated | 0.8.0 | move to `fetchProfile`; removal in 1.0.0 |
| `SkinAvatarCache.get` keys on UUID | 0.9.0 | pass `account.uuid()` instead of `account.username()` |
| Default avatar source changes | 0.9.0 | none, unless you relied on the third-party service; install `UrlTemplateAvatarSource` to keep it |
| `MicrosoftAuthConfig` gains `deviceCodeUrl` | 0.10.0 | positional construction gains an argument; `of` / `legacyMsa` unchanged |

Everything else is additive. 1.0.0 follows §F, dropping the deprecated profile methods and freezing the
surface.

## Testing

The existing rig covers most of this: a loopback `HttpServer` and fully configurable endpoints, which is
why none of it needs the internet.

- **Proxy routing.** A loopback HTTP proxy that records the absolute-form request line proves the request
  went through it; a second scope with a different route proves per-account selection; a route with
  credentials proves the `Proxy-Authorization` header; `socks5().withCredentials()` proves it throws
  rather than connecting unauthenticated.
- **Transport unification.** `SkinAvatarCache` and `CookieAuthUtil` must be shown to carry the timeouts,
  refuse redirects, and honour the proxy — the regression that "they open their own connections" was.
- **No injection.** A `SessionInjector` that fails the test if called, exercised across `check`,
  `refresh`, and both bulk operations. This is the single most important assertion in the set: it is the
  property the whole decomposition exists to provide.
- **Rotation discipline.** `check` on an expired account must not call the token endpoint; `refresh` must,
  exactly once.
- **Username correction.** A profile answering a different `name` updates and persists the stored record.
- **Entitlement mapping.** 404 yields `NOT_ENTITLED` and leaves the refresh token intact; 401 yields
  `INVALID_TOKEN` and renews.
- **Bulk.** Concurrency never exceeds the option; spacing is honoured per route; a 429 with `Retry-After`
  is waited out and retried; `stopOnRateLimit` stops and reports `stoppedEarly`; `cancel()` completes the
  report with `cancelled` and starts nothing further; results carry labels and never credentials.
- **Kind detection.** A table of real-shaped inputs per kind, including the `name:token` form and a line
  that must come back `UNKNOWN` rather than being guessed.
- **Device code.** A local endpoint answering `authorization_pending`, then `slow_down`, then success —
  asserting the poll interval grows and the flow does not busy-wait.

## Rejected alternatives

**Migrate to `java.net.http.HttpClient`.** Per-client authenticators would make authenticated SOCKS5
work, and the API is better in every other way. It supports no SOCKS proxies at all, so it trades
authenticated SOCKS5 for no SOCKS5 — a worse deal for this domain. Revisit if that changes.

**Store the proxy on `AltAccount`.** Simplest possible per-account proxying, and it publishes paid proxy
credentials into every shared repository the alt is a member of. Not close.

**One global proxy setting.** Trivial, and it defeats the purpose: the reason to proxy an alt manager is
that the accounts should not all look like one machine.

**Add `check`/`refresh` to `AltLoginService`.** Fewer types. But that interface is implemented directly by
some hosts, so widening it is breaking for them, and "log in" and "operate on" are genuinely different
verbs — the current conflation is exactly the defect.

**Have bulk operations drive `loginAccount` and simply ignore the injected session.** No new surface, and
it means fifty session switches with the last one winning at random. The whole feature is that the session
does not move.

**A `BulkProgress` that reports percentages.** Reads well, tells a user nothing about *which* alt failed.
Per-entry results with labels are what a host actually renders.

**Ship rotation policy — round-robin, health checks, dead-proxy eviction.** Genuinely useful, genuinely
not this library's job. The seam is called per request, so a host implements any policy it likes behind
it.
