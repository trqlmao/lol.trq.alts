# Full account management — the 1.0.0 surface

Status: the management surface shipped in 0.11.0; 1.0.0 finalizes it
Date: 2026-08-06
Release: 0.11.0 (the surface, additive) then 1.0.0 (freeze)

**What shipped in 0.11.0, and how it differs from this record.** The whole `account/` surface — profile,
entitlements, name (availability, eligibility, change, scheduled claim), skin, cape — plus `time/` and
`XstsError`, all additive. Two deviations, both improvements: the full profile is a new `PlayerProfile`
in `account/` rather than a widened `MinecraftProfile`, so the auth chain's result type is untouched and
there is no break; and the credential-login decision, the deprecated-API removal, and the bulk
entitlement sweep are all deferred to 1.0.0, where the freeze is made deliberately. The device-code route
(§F below) is likewise a 1.0.0 item. So 1.0.0 is: decide credential login, add device code, add the bulk
sweep, remove the deprecated profile methods, and freeze.

The library authenticates well, stores accounts safely, and operates on them in bulk. What it cannot do
is anything to an account *after* login beyond validating it: read the full profile, check what the
account owns, change its name, set its skin or cape. 1.0.0 closes that — the whole account lifecycle,
library-side, no UI — and then freezes the public surface.

This is the design record: the decisions and their reasoning, not an API reference. It draws on two
reference projects surveyed for their endpoint surface (a name-claim tool and a credential checker);
neither is named or linked from shipped artifacts, and nothing from either is copied — only the public
Mojang/Minecraft-services API shapes they exercise, which are Mojang's, not theirs.

## Scope

**In:** everything an account owner can do to their own account through the public Minecraft services
and Mojang APIs, exposed as library services a host drives.

- Full profile read — name, UUID, skins, capes, and pending `profileActions`.
- Entitlement check — not a boolean, the product set (Java, Bedrock, Game Pass tiers, Legends,
  Dungeons), since that is what the endpoint actually returns.
- Name: availability probe, change/claim, and eligibility (`nameChangeAllowed`, account creation date).
- Scheduled name claim — fire a bounded burst of change attempts around a target instant, first success
  wins, with a pluggable corrected-time source.
- Skin: set from a URL, upload from bytes, reset to default, with the classic/slim model.
- Cape: list owned, set active, hide.
- Two new login routes: **device code** (headless, and the only interactive route a legacy-MSA app can
  use) and **credential** (username + password, for an account you control).
- Rich Xbox Live / XSTS error classification — the `XErr` codes that say *why* an Xbox step refused,
  which the library currently collapses to a bare failure.

**Out (host or app concerns, deliberately):**

- Any UI, CLI, notifier, or config-file format.
- Process-priority manipulation, OS scheduler tuning — a host raises its own priority if it wants the
  last millisecond on a claim.
- Name-drop *discovery* — when a name becomes available is scraped off a site by a human; the library
  claims at a time it is given, it does not monitor.
- Third-party stat scraping, head-render services beyond the existing `AvatarSource`, protocol-level
  server joins for ban checks.
- Name history — Mojang removed the endpoint; there is nothing to call.

## Module layout — the reorganization 1.0.0 is the moment for

The library has grown a flat `auth/` that now holds login routes, the account service, token expiry,
cookie handling, and Microsoft config. A 1.0.0 that adds a whole management surface should not pile it
into the same package. The new shape:

```
lol.trq.alts
├── auth/         login routes + AltAccountService + MicrosoftAuthConfig   (as today, plus 2 routes)
├── account/      NEW — the post-login management surface, all over a live token
│   ├── AccountServices          facade: built from a token (+ config), hands out the services below
│   ├── ProfileService           full profile read; MinecraftProfile gains skins/capes/profileActions
│   ├── NameService              availability, eligibility, change, scheduled claim
│   ├── SkinService              set-by-url, upload, reset, model
│   ├── CapeService              list, setActive, hide
│   └── EntitlementService       Entitlements (product set + tier helpers)
├── time/         NEW, optional — TimeSource seam + SystemTimeSource + NtpTimeSource
├── net/          (as today) + XstsError classification lands here
├── bulk/         (as today) — gains an entitlement sweep over the new EntitlementService
├── model/, store/, cache/, skin/, crypto/, vault/, spi/   (unchanged)
```

`account/` is the heart of the release. Every service in it is an interface with one focused job, built
over a Minecraft access token, so a host can hold just the one it needs and each is testable against a
loopback server in isolation. They compose under `AccountServices`, which a host obtains from a live
account:

```java
AccountServices services = alts.accountServices(account);   // or .accountServices(token)
services.profile().fetch();
services.entitlements().fetch();
services.name().checkAvailability("desiredName");
services.name().change("desiredName");
services.skin().setFromUrl(url, SkinModel.SLIM);
services.cape().setActive(capeId);
```

Rejected: bolting these onto `AltAccountService`. That interface is "validate/renew a stored account";
"change this account's name" is a different verb against a different endpoint set, and some hosts
implement `AltAccountService` directly, so widening it is a break for them and a muddle for everyone.

## The account services

### ProfileService — the full read

Today's profile fetch reads `name` and `id` and drops the rest. `GET /minecraft/profile` also returns
`skins[]` (each with `id`, `state=ACTIVE|INACTIVE`, `url`, `variant=CLASSIC|SLIM`), `capes[]` (`id`,
`state`, `url`, `alias`), and `profileActions` (pending moderation actions). `MinecraftProfile` grows to
carry them, and `ProfileService.fetch()` returns the whole thing. The auth chain keeps using the slim
read internally; nothing forces a host to care about capes to log in.

### EntitlementService — what the account owns

`GET /entitlements/mcstore` returns an `items[]` of named products. The useful reading is not "owns
Minecraft yes/no" — it is the set: `product_minecraft` (Java), `product_minecraft_bedrock`,
`product_game_pass_ultimate`, `product_game_pass_pc`, `product_legends`, `product_dungeons`.
`Entitlements` is that set plus helpers (`ownsJava()`, `ownsBedrock()`, `viaGamePass()`), so a host can
tell an account that owns Java outright from one entitled only through a lapsing Game Pass — which is
exactly the distinction an alt manager cares about and a boolean throws away.

This also sharpens the `NOT_ENTITLED` story from 0.8.0: a 404 on the profile plus an entitlement check
distinguishes "owns nothing" from "owns Java but has never set a name", which the profile 404 alone
cannot.

### NameService — availability, eligibility, change, claim

Four operations, and the endpoints differ in auth and shape:

- **`checkAvailability(name)`** → `GET /minecraft/profile/name/{name}/available` (bearer), returning
  `AVAILABLE | DUPLICATE | NOT_ALLOWED`. `NOT_ALLOWED` covers both blocked names and reserved ones; the
  library reports the status verbatim and does not pretend to know which.
- **`eligibility()`** → `GET /minecraft/profile/namechange` (bearer): `nameChangeAllowed` and
  `createdAt`. A `NameEligibility` record carries both, plus the derived account age, so a host can show
  "you can change your name" or "N days until eligible" without date arithmetic of its own.
- **`change(name)`** → `PUT /minecraft/profile/name/{name}` (bearer, no body). This is the mutation. The
  response is classified, not discarded: 200 carries the updated profile (returned), 400 is an invalid
  or malformed name, 403 is unavailable-or-on-cooldown, 429 carries a `Retry-After`, and the body's
  `errorMessage`/`details` are read so the failure says which. A dead token (401) and an unentitled
  account (404) are terminal and never retried — the reference tool retried both at full speed for its
  whole window, which is a bug not to reproduce.
- **`claimAt(name, target, ClaimOptions)`** → the scheduled claim. Covered below.

### The scheduled claim — `claimAt`

A name becomes free at a known instant; the goal is to land `change` as close after it as possible
without wasting attempts before it. The reference tool's timing machinery is the transferable idea; its
process-priority and NTP-scraping apparatus is not.

```java
CompletableFuture<ClaimResult> claimAt(String name, Instant target, ClaimOptions options);
```

`ClaimOptions`: `leadTime` (how far before `target` to begin, default 300 ms), `window` (how long to keep
trying after, default 10 s), `attemptSpacing` (min gap between attempts on one worker, default 8 ms),
`concurrency` (parallel attempt chains, default 3), and `timeSource`.

The shape borrows directly from the bulk runner already in the library — structural concurrency over the
common pool, first success cancels the rest via a shared flag, no executor created. What it adds is a
two-phase wait: coarse `CompletableFuture.delayedExecutor` sleeps down to a threshold, then a short
spin-poll of the `TimeSource` for the final approach, because a scheduler sleep overshoots a
millisecond target. The spin is bounded (tens of ms) so it never becomes a busy-loop that pins a core.

**Corrected time is a seam, not a scrape.** `time/TimeSource` returns `Instant now()`. `SystemTimeSource`
is the default and trusts the OS clock. `NtpTimeSource` is shipped for a host that wants sub-second
correction — a minimal SNTP client (one UDP round trip, the standard offset calculation), refreshed on
an interval, with the *only* new network shape in the release that is not HTTP. A host that already has
corrected time plugs its own `TimeSource` in and the library never opens a socket for it.

Rejected: bundling NTP into the claim itself, and a five-source HTTP-time-API aggregator like the
reference. NTP is one clean protocol; averaging four web APIs that each return time in a different JSON
shape is fragile and slower than the thing it corrects.

### SkinService and CapeService

- `SkinService.setFromUrl(url, model)` → `POST /minecraft/profile/skins` with `{url, variant}`.
- `SkinService.upload(pngBytes, model)` → `POST /minecraft/profile/skins` multipart (`variant` +
  `file`). The library already has no multipart helper; this adds one, kept in `net/`.
- `SkinService.reset()` → `DELETE /minecraft/profile/skins/active`.
- `CapeService.setActive(capeId)` → `PUT /minecraft/profile/capes/active` with `{capeId}`.
- `CapeService.hide()` → `DELETE /minecraft/profile/capes/active`.

Owned capes come from the profile read, so `CapeService` has no separate list call — it reads
`profile().fetch().capes()`.

## Two new login routes

### Device code — `loginDeviceCode`

Folded in from where it was previously planned (0.11.0), because 1.0.0 is the freeze and the login
surface should be complete at it.

```java
CompletableFuture<LoginResult> loginDeviceCode(DeviceCodePrompt prompt, LoginMode mode);
```

`spi/DeviceCodePrompt.show(userCode, verificationUri, expiresIn)` hands the host what to display; the
library polls `POST {tokenUrl}` with `grant_type=urn:ietf:params:oauth:grant-type:device_code`, honours
the advertised `interval`, backs off on `slow_down`, and finishes on success, decline, or expiry.
`MicrosoftAuthConfig` gains `deviceCodeUrl` (default the consumer device-auth endpoint). It earns its
place three ways: a headless host has no browser, a locked-down machine may have every loopback port
refused, and a legacy-MSA app declares a desktop redirect the loopback server cannot serve — so
`legacyMsa` configs have had no interactive route at all, only refresh-token import.

### Credential — `loginCredentials`

```java
CompletableFuture<LoginResult> loginCredentials(String email, char[] password, LoginMode mode);
```

The legacy-MSA implicit flow: fetch the login page, read its anti-forgery `PPFT` token, POST the
credentials, follow the consent interstitial if one appears, and pull the RPS ticket from the redirect
fragment, then run the existing Xbox → XSTS → Minecraft chain. It authenticates an account from its own
password, non-interactively — the one entry point the library lacks and the thing an account owner
plainly has.

**This one gets a checkpoint before it is built.** It is dual-use in a way the other routes are not: the
same call that logs a user into their own account is the mechanism of credential stuffing against
accounts that are not. The library is already an alt manager and does not moralize about that, but this
route is worth a deliberate yes rather than an assumed one — so it is specced here, flagged, and left for
the owner to confirm or cut. Everything else in 1.0.0 proceeds regardless; if the answer is no,
`loginCredentials` drops and nothing else changes. The password is a `char[]`, cleared after the POST,
and never stored or logged.

### XSTS error classification

Independent of the new routes and pure correctness. The XSTS step returns an `XErr` on refusal that
says why, and the library currently reads only whether a token came back. `net/XstsError` classifies the
known codes: `2148916233` (no Xbox account — the account has never signed into Xbox), `2148916235`
(region-blocked), `2148916236`/`2148916237` (adult verification needed), `2148916238` (child account,
needs family consent). Surfaced as a typed reason so a host can tell a user *what to fix* instead of
"Xbox auth failed", which is useless to them.

## Bulk

The bulk surface gains one operation: `entitlementsAll(accounts, options, progress)`, an
entitlement-tier sweep over `EntitlementService`, since "which of my accounts actually own Java / are on
a lapsing Game Pass" is exactly a bulk question. It reuses the existing runner unchanged; only the
per-item operation is new. No other bulk change.

## Breaking changes

| Change | Migration |
| --- | --- |
| `auth/` classes that move to `account/` | update imports; no behaviour change. `MinecraftProfile` moves to `account/` |
| `MinecraftProfile` gains `skins`, `capes`, `profileActions` | positional construction breaks; `of(...)` factory unchanged |
| `MicrosoftAuthConfig` gains `deviceCodeUrl` | positional construction gains an argument; `of`/`legacyMsa` unchanged |
| `AltLoginService` gains `loginDeviceCode` (and, if approved, `loginCredentials`) | a host implementing the interface adds the method(s); `AltsRuntime` users unaffected |

After 1.0.0 the surface is frozen: the deprecated `AccountNetworkUtil.fetchProfileFromToken` overloads
(deprecated in 0.8.0) are removed, and no further breaking change ships without a 2.0.

## Testing

The existing rig — a loopback `HttpServer`, fully configurable endpoints, an injected clock — covers all
of it without the internet.

- **Every account service** against a loopback returning each documented status, asserting the classified
  outcome and that a mutation reads the response body rather than the status alone.
- **Entitlement tiers**: a fixture per product combination, asserting `ownsJava` / `viaGamePass` / the set.
- **Name change classification**: 200 returns the profile; 400/403/404/429 each map to their reason and
  429 carries the `Retry-After`; 401 and 404 are terminal.
- **Scheduled claim**: against an injected `TimeSource`, a claim fires no attempt before `leadTime`
  before target, keeps trying across the window, stops on first success, and never spins unbounded.
- **Device code**: a loopback answering `authorization_pending` then `slow_down` then success, asserting
  the poll interval grows and the loop does not busy-wait.
- **XSTS errors**: each `XErr` code maps to its typed reason.
- **Credential** (if built): the PPFT scrape, the consent-interstitial branch, and the bad-password
  classification, against a loopback serving canned login-page HTML.

## Open decision for the owner

One, called out above: **ship `loginCredentials` (username + password) or not.** It is the single
sensitive item; everything else is unambiguously an account-owner capability. Specced either way — a no
removes exactly that route and its tests and changes nothing else. Awaiting the call before that route is
built; the rest of 1.0.0 does not wait on it.
