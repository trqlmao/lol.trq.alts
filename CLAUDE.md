# CLAUDE.md

Guidance for AI coding agents working **on** this library or **with** it from a host.

**lol.trq.alts** is a standalone, renderer-agnostic Minecraft account-manager core for Fabric mods. It
is consumed as a JitPack dependency or a git submodule, and it must never depend on or reference any
specific consumer. Pure JDK plus Gson — no Minecraft types, no renderer types, no host imports.

## 30-second mental model

One wiring root, a handful of host seams, everything else behind them.

```java
AltsRuntime<H> alts = new AltsRuntime.Builder<H>()
        .sessionInjector(session -> ...)   // REQUIRED: make a SessionData the live session
        .vaultDirectory(() -> dataDir)     // REQUIRED: where the encrypted account file lives
        .mainThread(client::execute)       // REQUIRED: run a task on the render thread
        .toastSink(...)                    // REQUIRED: surface a notification
        .textureUploader(...)              // optional: PNG bytes -> your handle type H
        .gameStatsSource(...)              // optional: one per server
        .microsoftAuth(MicrosoftAuthConfig.of(yourAzureClientId))  // optional
        .vaultTransportResolver(...)       // optional: avp:// authority -> transport
        .proxyProvider(scope -> ...)       // optional: route each request; absent means direct
        .build();   // validates, binds the static stores, and loads them from disk

alts.loginService();          // the entire login surface
alts.accountService();        // check / refresh, without touching the live session
alts.skinCache();             // AsyncCache<username, H>
alts.gameStats("example.net") // AsyncCache<uuid, GameStats>, one per registered source
```

`H` is the host's opaque texture-handle type. The library never inspects it; it only hands it back.

Package map:

| Package | What lives there |
|---|---|
| `spi/` | the host seams — the entire boundary. The library calls these; the host implements them |
| `net/` | `HttpUtil` — the one way out of the library — plus `NetworkScope` and `ProxyRoute` |
| `auth/` | the login routes, account check/refresh, `MicrosoftAuthConfig`, token expiry, cookies |
| `store/` | `AltStore` (accounts) and `SecretStore` (per-user secrets), both encrypted files |
| `cache/` | `AsyncCache<K,V>` — lazy, non-blocking, stale-while-revalidate |
| `skin/` | `SkinAvatarCache<H>` over that cache |
| `model/` | the records: `AltAccount`, `SessionData`, `GameStats`, `BanInfo`, enums |
| `crypto/`, `vault/` | the zero-knowledge shared repository; `vault/federation/` is `avp://` addressing |

## Hard rules

- **No consumer references.** Never name, import, or describe any specific mod, client, launcher,
  product, sync server, or hosted instance that consumes this. Keep examples generic (`your mod`,
  `the host`, `example.net`, `vault.example`). This covers source, comments, Javadoc, docs, commit
  messages, tests, and the README. `AltAccount.sourceClient`/`sourceUser` and `avp://host/repoId`
  define field *names* only — the values are host-supplied at runtime. CI enforces this
  (`.github/workflows/no-leak.yml`, against a denylist held as a repository secret).
- **Host-agnostic boundary.** Seam interfaces live in `spi/`; hosts implement them. Never reach into a
  host type. No `import` outside `lol.trq.alts`, the JDK, and Gson.
- **Secrets stay out.** No API keys, tokens, or credentials in source or history. A server API key lives
  host-side behind `GameStatsSource` (or another seam). Never commit `.idea/`, `*.iml`, or machine paths.
- **House style.** palantir-java-format (4-space, 120 column). Records for DTOs with `@SerializedName`
  on every component. Full Javadoc on public and protected members, with `@since` on each new one.
- **Conventional Commits**, one logical change each, and **never** a `Co-Authored-By` or any other
  AI-attribution trailer — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Patterns that matter

**Login is one interface, six routes plus one.** `AltLoginService` exposes `loginMicrosoft`,
`loginRefreshToken`, `loginCookie`, `loginCookieFile`, `loginSession`, `loginOffline`, and
`loginAccount` (log into a record you already hold). All return `CompletableFuture<LoginResult>`; none
throw at the caller. A new route follows the same shape: resolve off-thread, finish through
`finalizeLogin`, return a classified result.

**Outcomes are typed, messages are not.** Every `LoginResult` carries an `AltLoginCallback.FailureReason`
(`NONE`, `UNKNOWN`, `REAUTH_REQUIRED`, `INVALID_TOKEN`, `NETWORK`, `NOT_CONFIGURED`). Hosts branch on the
reason — a message string survives neither obfuscation nor localization. Classify a *refusal* apart from
an *unreachable service*: one means the credential is spent, the other means retry, and conflating them
sends the user through a browser login to fix an outage.

**A refresh token is a durable credential, not another access token.** It renews a session for as long as
it is valid, so it is redacted from every `toString`, never persisted for an account the store does not
already hold, discarded only on a *stated* `invalid_grant` (never on a 429, a 408, or a 5xx), and
withheld from a shared repository unless the manifest opts in. Keep all four properties when touching
`AltAccount`, `AltStore`, `MicrosoftAuthUtil`, or `SharedVault`.

**DTOs are records with `@SerializedName` on every component**, so (de)serialization survives shrinking
and obfuscation. Any record carrying a credential overrides `toString` to redact it. Adding a component
is a breaking change for positional construction — note it in the changelog.

**Logging in is one step more than operating on an account.** `AltAccountService.check`/`refresh` never
touch the live session; `loginAccount` is `refresh` plus the injection. Keep it that way — anything that
works over many accounts (a validation sweep, a background refresh, a bulk import) is unbuildable the
moment a session install creeps back into the middle of the chain. And prefer `check` over `refresh`
when only looking: renewal rotates the refresh token every single time.

**Every request goes through `HttpUtil`.** That is where the finite timeouts, the refusal to follow
redirects, the drained error bodies, and the host's proxy live. A new call site that opens its own
connection silently opts out of all four — which is what the cookie chain and the avatar fetch used to
do. Pass a `NetworkScope` naming the purpose and, when there is one, the account.

**Proxy resolution fails closed.** An installed `ProxyProvider` that throws or returns null fails the
request; it must never fall back to a direct connection. That fallback would disclose the host's real
address at the moment it believed every request was routed, and unlike a failed request it cannot be
undone.

**An unreadable store is not an empty store.** The encryption key is derived from machine properties, so
a renamed OS user or a moved home directory turns a good file unreadable. `AltStore`/`SecretStore` never
overwrite a file they failed to read: they report why on `loadError()` and copy it aside first. Preserve
that in any change to persistence.

**Microsoft login needs a host-supplied client id.** There is no built-in default. `MicrosoftAuthConfig.of`
is the Azure OAuth dialect; `legacyMsa` is the pre-Azure one, which differs in its redirect, its scope,
and its RPS ticket prefix.

## Pitfalls

- **Do not add a Minecraft, renderer, or consumer import.** If a change seems to need one, it belongs
  behind a new `spi/` seam instead.
- **Do not branch on a failure message** anywhere, including in tests. Add a `FailureReason` if the
  existing ones do not fit.
- **Do not make `AltStore` mutations assume a thread.** Logins resolve on the common pool while the host
  reads `accounts()` from its render thread; the collections are concurrent for that reason.
- **Do not persist an account the store does not already hold.** `updateCredentials` is deliberately a
  no-op for an unknown UUID — a `DIRECT` login or an account from a shared repository is one the user
  chose not to save.
- **Do not change a crypto construction casually.** The scheme ids, AAD, and key-binding bytes are fixed
  by the [Alt Vault Protocol](https://github.com/trqlmao/avp) and gated byte-for-byte by
  `AvpConformanceVectorsTest` against vendored vectors. Changing one breaks interoperability with every
  other implementation.
- **Do not edit a docs snippet directly.** Every Java block in `docs/GETTING_STARTED.md` is inlined from
  a method in `examples/`, which `check` compiles. Change the example first, then the guide.
- **Do not skip the `@since`.** New public and protected members carry the version they will first ship
  in, and a `CHANGELOG.md` entry under `[Unreleased]`.

## Build

```bash
./gradlew build          # compile + test + spotlessCheck
./gradlew check          # the same gate CI runs, including compiling examples/
./gradlew spotlessApply  # format (run before committing)
```

JDK 25 required. The library has no Minecraft dependency, so it builds standalone.

## Further reading

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the internals,
[docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) for host integration,
[llms.txt](llms.txt) for a structured index of everything,
[SECURITY.md](SECURITY.md) for the threat model and its stated boundaries.
