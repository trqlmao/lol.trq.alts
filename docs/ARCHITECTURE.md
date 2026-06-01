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
  library   │ auth/   login flows (MS / cookie / session / offline)
            │ store/  AltStore (encrypted file) + EncryptionUtil
            │ cache/  AsyncCache<K,V>
            │ skin/   SkinAvatarCache<H>
            │ model/  AltAccount, HypixelStats, BanInfo, …
            │ crypto/ VaultIdentity, PayloadCipher, KeyWrapScheme, …
            │ vault/  SharedVault + transport/ DTOs + federation/ addressing
            └──────────────────────────────────────────────┘
```

## Key pieces

- **`AltsRuntime<H>`** — the single wiring root. Its builder takes the seam implementations, validates the required ones, binds the static helpers (`AltStore`, `AltsToasts`), and constructs the login service and caches. `H` is the host renderer's texture-handle type, threaded through `SkinAvatarCache<H>` so the cache is type-safe without the library knowing what a texture is.

- **`spi/` seams** — the entire host boundary. The library calls these interfaces; it never imports a host class. This is what makes the same core usable from different mods and different renderers.

- **`auth/`** — `AltLoginService` exposes four `CompletableFuture`-based login methods. The implementation runs the OAuth / cookie / token flows off-thread and hands the resolved identity to the host's `SessionInjector` as a transport-neutral `SessionData`.

- **`store/`** — `AltStore` is a static façade over an encrypted on-disk file. `EncryptionUtil` does AES-256-GCM with a PBKDF2-derived, machine-bound key. The filename and the key-binding constant are host-configurable so different hosts (and migrations from earlier layouts) don't collide.

- **`cache/AsyncCache<K,V>`** — the reusable async-lookup primitive. `get(key)` never blocks: it returns the cached value or `null`, firing a background fetch on a miss. Entry states (pending / failed / value) are encoded as sentinels because `ConcurrentHashMap` forbids nulls. A positive TTL makes it stale-while-revalidate. Both `SkinAvatarCache` and the Hypixel-stats cache are built on this.

- **`crypto/` + `vault/SharedVault`** — the zero-knowledge shared-repository layer. A member is an Ed25519 identity (their stable id) paired with an X25519 key. `SharedVault` does pure client-side crypto over plain DTOs: it generates a per-repo data key, wraps it to each member's X25519 key (`KeyWrapScheme`), and encrypts the `AltAccount` payload under AES-256-GCM with the repo id / version / epoch bound into the AAD so stale or spliced ciphertext fails to authenticate. It never touches the network, which keeps the zero-knowledge guarantee provable: the server only ever sees what these methods emit (ciphertext, wrapped keys, public keys, counters).

- **`vault/transport/`** — the network boundary as plain DTOs and a `VaultTransport` interface. The library names no server or wire protocol; a host plugs in gRPC, HTTP/JSON, or a test double. `KeyBindingVerifier` (and the concrete `IssuerSignedKeyBindingVerifier`) is the anti-MITM seam: before wrapping a data key to keys a server served, a member can require an identity-provider signature over those keys.

## Federation

Shared repositories are federated by **portable identity + addressing**, not server-to-server replication. A repository is addressed `avp://host/repoId` (`vault/federation/RepoAddress`); the `repoId` stays the opaque value the server minted, so the manifest and AAD are unchanged. Because identity is a server-agnostic keypair, the same member authenticates against any conformant server. To join a repository hosted elsewhere, a member resolves a transport for that repository's host via the `VaultTransportResolver` SPI seam and authenticates there with its own identity — so the union of all servers is one navigable network, with the server kept simple enough that anyone can host one. The join handshake is two copy-pasteable tokens: an `InviteRequest` (the joiner's public keys) and a `RepoLocator` (where the repo lives plus the issuer to trust). True server-to-server replication is a possible future extension, deliberately out of scope.

## Threading

- Login flows and cache fetches run on the common `ForkJoinPool` via `CompletableFuture`.
- Anything that must touch render-thread state (texture upload, session install) is marshalled through the host's `MainThreadExecutor`.
- `AltStore`'s in-memory list is mutated on the calling thread; persistence is synchronous within `save()`.

## Design rules

- No Minecraft / renderer / consumer-mod imports anywhere in the library — only JDK and Gson.
- DTOs are records with `@SerializedName` on every component, so (de)serialization survives obfuscation.
- Optional capabilities (avatars, Hypixel stats) degrade to `null` when their seam is absent, so hosts can opt in incrementally.
