# Architecture

**lol.trq.alts** is a renderer-agnostic, host-agnostic account-manager core. It holds no Minecraft or renderer types; everything platform-specific crosses a backend seam.

## Layers

```
            ┌─────────────────────────────────────────────┐
  host mod  │  seam implementations (SessionInjector, …)   │
            └───────────────▲─────────────────────────────┘
                            │ spi/ interfaces
            ┌───────────────┴─────────────────────────────┐
            │  AltsRuntime  — wiring root + Builder         │
            ├──────────────────────────────────────────────┤
  library   │ auth/   login flows (MS / refresh / cookie / cookie file / session / offline)
            │ store/  AltStore (encrypted file) + EncryptionUtil
            │ cache/  AsyncCache<K,V>
            │ skin/   SkinAvatarCache<H>
            │ model/  AltAccount, GameStats, BanInfo, …
            │ crypto/ VaultIdentity, PayloadCipher, KeyWrapScheme, …
            │ vault/  SharedVault + transport/ DTOs + federation/ addressing
            └──────────────────────────────────────────────┘
```

## Key pieces

- **`AltsRuntime<H>`** — the single wiring root. Its builder takes the seam implementations, validates the required ones, binds the static helpers (`AltStore`, `SecretStore`, `AltsToasts`), loads whatever those stores already hold on disk, and constructs the login service and caches. `H` is the host renderer's texture-handle type, threaded through `SkinAvatarCache<H>` so the cache is type-safe without the library knowing what a texture is.

- **`spi/` seams** — the entire host boundary. The library calls these interfaces; it never imports a host class. This is what makes the same core usable from different mods and different renderers.

- **`auth/`** — `AltLoginService` exposes seven `CompletableFuture`-based methods: six login routes (Microsoft, refresh token, cookie text, cookie file, session, offline) plus `loginAccount`, which logs into a record you already hold. The implementation runs the OAuth / cookie / token flows off-thread and hands the resolved identity to the host's `SessionInjector` as a transport-neutral `SessionData`. `loginAccount` renews an expired session in place from the account's stored refresh token — proactively when `TokenExpiry` says the access token is spent, and once reactively when the services refuse it — persisting the rotated credential before installing the session. Outcomes carry a typed `AltLoginCallback.FailureReason`, so a host can tell `REAUTH_REQUIRED` (the credential is permanently spent) from `NETWORK` (retryable) without matching on a message string. Every route classifies those two apart: a service that refused an answer and a service that never gave one mean opposite things to the user in front of it.

  `MicrosoftAuthConfig` carries the dialect as well as the endpoints. An Azure OAuth application and a *legacy MSA* one differ in three load-bearing ways — the redirect the grant must declare, the scope it asks for, and whether Xbox Live wants the ticket prefixed `d=` or `t=` — so `legacyMsa(clientId)` exists alongside `of(clientId)` rather than being a flag on the flow.

- **`store/`** — `AltStore` is a static façade over an encrypted on-disk file, and `SecretStore` is the same shape for per-user secrets that must never enter a shared repository. `EncryptionUtil` does AES-256-GCM with a PBKDF2-derived, machine-bound key. The filename and the key-binding constant are host-configurable so different hosts (and migrations from earlier layouts) don't collide.

  Because the key is derived from machine properties, an ordinary environment change — a renamed OS user, a moved home directory — turns a perfectly good file unreadable. Both stores therefore treat "cannot read" and "nothing stored" as different states: a failed load leaves the in-memory collection alone, records why on `loadError()`, and is copied aside to `<name>.unreadable` before any later save can replace it. The copy is made once, so a second failure cannot overwrite the backup that holds the data, and a save that cannot make the copy is skipped rather than allowed to destroy the original.

- **`cache/AsyncCache<K,V>`** — the reusable async-lookup primitive. `get(key)` never blocks: it returns the cached value or `null`, firing a background fetch on a miss. Entry states (pending / failed / value) are encoded as sentinels because `ConcurrentHashMap` forbids nulls. A positive TTL makes it stale-while-revalidate. Both `SkinAvatarCache` and the per-server game-stats caches are built on this; `AltsRuntime.gameStats(serverId)` returns one cache per registered `GameStatsSource`.

- **`crypto/` + `vault/SharedVault`** — the zero-knowledge shared-repository layer. A member is an Ed25519 identity (their stable id) paired with an X25519 key. `SharedVault` does pure client-side crypto over plain DTOs: it generates a per-repo data key, wraps it to each member's X25519 key (`KeyWrapScheme`), and encrypts the `AltAccount` payload under AES-256-GCM with the repo id / version / epoch bound into the AAD so stale or spliced ciphertext fails to authenticate. It never touches the network, which keeps the zero-knowledge guarantee provable: the server only ever sees what these methods emit (ciphertext, wrapped keys, public keys, counters). The manifest's `shareRefreshTokens` flag gates whether an account's refresh token and expiry may travel through the repository at all; it defaults to withholding, and the strip runs at the single encrypt/decrypt choke point on **both** write and read — on write so the credential never reaches the server, and on read so a peer running a modified build cannot push tokens into a repository whose policy forbids them. The policy therefore binds **members**, but not the **manifest host**: `shareRefreshTokens` is plain manifest metadata the server serves, carrying no signature and no binding into the payload AAD, so a malicious or compromised host can serve `true` for a repository created withholding and every member will honour it. Authenticating the manifest is a known limitation, tracked as follow-up work; until then a repository's refresh-token policy is only as trustworthy as whoever hosts it. Opting in is also effectively irreversible — `removeMember` / `rotateKey` re-key future payloads, but a refresh token already on a member's disk can only be revoked account-side, at the identity provider.

- **`vault/transport/`** — the network boundary as plain DTOs and a `VaultTransport` interface. The library names no server or wire protocol; a host plugs in gRPC, HTTP/JSON, or a test double. `KeyBindingVerifier` (and the concrete `IssuerSignedKeyBindingVerifier`) is the anti-MITM seam: before wrapping a data key to keys a server served, a member can require an identity-provider signature over those keys.

## Federation

Shared repositories are federated by **portable identity + addressing**, not server-to-server replication. A repository is addressed `avp://host/repoId` (`vault/federation/RepoAddress`); the `repoId` stays the opaque value the server minted, so the manifest and AAD are unchanged. Because identity is a server-agnostic keypair, the same member authenticates against any conformant server. To join a repository hosted elsewhere, a member resolves a transport for that repository's host via the `VaultTransportResolver` SPI seam and authenticates there with its own identity — so the union of all servers is one navigable network, with the server kept simple enough that anyone can host one. The join handshake is two copy-pasteable tokens: an `InviteRequest` (the joiner's public keys) and a `RepoLocator` (where the repo lives plus the issuer to trust). True server-to-server replication is a possible future extension, deliberately out of scope.

## Threading

- Login flows and cache fetches run on the common `ForkJoinPool` via `CompletableFuture`.
- Anything that must touch render-thread state (texture upload, session install) is marshalled through the host's `MainThreadExecutor`.
- `AltStore`'s account list is copy-on-write and `SecretStore`'s map is concurrent, because a login resolving on a pool thread mutates them while the host reads them from its render thread. `accounts()` is a live list, and iterating it during a login sees a snapshot rather than failing. Persistence is synchronous within `save()`, on whichever thread called it.

## Design rules

- No Minecraft / renderer / consumer-mod imports anywhere in the library — only JDK and Gson.
- DTOs are records with `@SerializedName` on every component, so (de)serialization survives obfuscation.
- Optional capabilities (avatars, game stats) degrade to `null` when their seam is absent, so hosts can opt in incrementally.
