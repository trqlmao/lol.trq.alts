# lol.trq.alts

[![](https://jitpack.io/v/trqlmao/lol.trq.alts.svg)](https://jitpack.io/#trqlmao/lol.trq.alts)
[![Build](https://github.com/trqlmao/lol.trq.alts/actions/workflows/build.yml/badge.svg)](https://github.com/trqlmao/lol.trq.alts/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> Drop-in Minecraft account-manager core for Fabric mods: auth, encrypted local storage, and async player data, all host-agnostic.

**lol.trq.alts** is a standalone, renderer-agnostic account-management library for Minecraft Fabric mods. It provides Microsoft, refresh-token, cookie, cookie-file, session, and offline login, an encrypted on-disk account store, async player-head and per-server game-stats caches, and a clean set of host seams, with no Minecraft or renderer types of its own.

## Features

- **Six login methods.** Microsoft OAuth 2.0, OAuth refresh token, browser-cookie text, an exported cookie file (Netscape or JSON, byte-order-mark aware, size-capped), session-token, and offline, all `CompletableFuture`-based behind a single `AltLoginService`. You supply your own Azure app client id for Microsoft login (`MicrosoftAuthConfig`); the library ships no shared default, and a *legacy MSA* application is a supported dialect via `MicrosoftAuthConfig.legacyMsa`.
- **Encrypted local store.** Accounts are persisted with AES-256-GCM and PBKDF2 in a host-chosen directory; the file never holds plaintext credentials at rest. Stored Microsoft sessions renew silently from their refresh token instead of expiring after roughly a day, so a saved account does not need a fresh browser round every time its access token lapses. A file that exists but cannot be read is never mistaken for an empty store: it is reported on `AltStore.loadError()` and copied aside rather than overwritten.
- **Zero-knowledge shared vault.** Share an alt repository between members with end-to-end encryption (Ed25519 identities, X25519-wrapped per-repo keys, AES-256-GCM payloads). The sync server stores only ciphertext, wrapped keys, public keys, and counters, so it can decrypt nothing. Refresh tokens are withheld from a shared repository unless its manifest opts in, because a refresh token grants durable account access rather than the day an access token buys.
- **Federated.** Repositories are addressed `avp://host/repoId` and reachable across independently hosted servers using one portable identity, so different clients can share alts without a central server. The wire contract is the open [Alt Vault Protocol](https://github.com/trqlmao/avp).
- **Async caches.** A small `AsyncCache<K,V>` primitive (lazy, non-blocking, stale-while-revalidate) powers player-head avatars and optional, server-agnostic game stats. A host registers a `GameStatsSource` per server and the card renders whatever stat chips it returns; the library never interprets them.
- **Operate on accounts without logging into them.** `accountService().check(...)` asks whether a stored token still works; `refresh(...)` renews it and persists the rotation. Neither installs a session, so a sweep over every alt you hold does not switch session once per account. Outcomes are typed, including `NOT_ENTITLED` for an account that authenticates but owns no Minecraft profile.
- **Bulk operations.** `bulk().checkAll(...)`, `refreshAll(...)`, and `importCredentials(...)` run one operation over many accounts with bounded concurrency, spacing, retry budgets, progress, and cancellation — and a report that separates "stopped early" from "cancelled" from "ran and some failed". Nothing installs a session.
- **Per-account proxies.** A `ProxyProvider` names the route each request takes, so fifty validations do not all arrive from one address. Resolution fails closed: a provider that cannot name a route fails the request rather than quietly disclosing your real address.
- **Host-agnostic.** The library never imports your mod. You provide a handful of backend seams (session injection, storage directory, texture upload, main-thread executor, toasts, stats source, proxy routing) and wire it once.
- **Obfuscation-safe.** DTOs are records with a `@SerializedName` on every component, so (de)serialization survives shrinking and obfuscation.

## Installation

[![](https://jitpack.io/v/trqlmao/lol.trq.alts.svg)](https://jitpack.io/#trqlmao/lol.trq.alts)

<details>
<summary>Gradle (Groovy)</summary>

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.trqlmao:lol.trq.alts:VERSION'
}
```
</details>

<details>
<summary>Gradle (Kotlin)</summary>

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.trqlmao:lol.trq.alts:VERSION")
}
```
</details>

## Quick start

```java
// 1. Wire the host seams once at startup. H is your renderer's texture-handle type.
//    build() also reads whatever store is already on disk, so AltStore.accounts()
//    is populated the moment it returns.
AltsRuntime<MyHandle> alts = new AltsRuntime.Builder<MyHandle>()
        .sessionInjector(new MySessionInjector())   // install a SessionData as the live session
        .vaultDirectory(() -> myDataDir)             // where the encrypted account file lives
        .textureUploader(new MyUploader())           // upload avatar bytes -> host texture
        .mainThread(myExecutor::execute)             // marshal onto the render thread
        .toastSink(new MyToastSink())                // surface login notifications
        .gameStatsSource(new MyServerStatsSource())  // optional: per-server stat chips (one per server)
        .microsoftAuth(MicrosoftAuthConfig.of(MY_AZURE_CLIENT_ID))  // your own Azure app id
        .build();

// 2. Log in.
alts.loginService().loginMicrosoft(LoginMode.ADD)
        .thenAccept(result -> { /* result.success(), result.account() */ });

// Renew from a refresh token, no browser step.
alts.loginService().loginRefreshToken(storedRefreshToken, LoginMode.ADD)
        .thenAccept(result -> { /* result.success(), result.reason() */ });

// Log in from a cookie file the user picked, read off the calling thread.
alts.loginService().loginCookieFile(Path.of(chosenFile), LoginMode.ADD)
        .thenAccept(result -> { /* result.success(), result.reason() */ });
```

See [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) for a full walkthrough and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the internals.

## For AI agents

This repository is structured for you. Start with [`CLAUDE.md`](CLAUDE.md) — the mental model, the
patterns that matter, and the pitfalls — then [`llms.txt`](llms.txt), a structured index of the docs and
core source in the [llmstxt.org](https://llmstxt.org) format, then [`examples/`](examples/) for working
code. [`AGENTS.md`](AGENTS.md) is the condensed form of the same rules.

The points most often gotten wrong:

1. **Consumer-agnostic, always.** Never name a specific consuming mod, client, product, or sponsor in
   source, comments, docs, or commit messages. Keep examples generic (`your mod`, `the host`).
2. **Never reach into a host type.** The library imports only the JDK, Gson, and its own packages. Host
   platform behavior crosses the `spi/` seam interfaces, which the host implements.
3. **DTOs are records with `@SerializedName` on every component.** This keeps serialization stable under
   obfuscation. Do not drop the annotations or switch to field-name reflection.
4. **Microsoft login needs a host-supplied client id.** There is no built-in default; the host passes
   one through `MicrosoftAuthConfig`.
5. **A refresh token is a durable credential, not another access token.** It renews a session for as
   long as it is valid, so it is redacted from every `toString`, never persisted for an account the
   store does not already hold, and withheld from a shared repository unless the manifest opts in.
   Keep those three properties when touching `AltAccount`, `AltStore`, or `SharedVault`.
6. **Branch on `AltLoginCallback.FailureReason`, never on the message.** Messages change under
   obfuscation and localization; the reason is the contract. `NETWORK` means retry, `INVALID_TOKEN` and
   `REAUTH_REQUIRED` mean the credential is the problem — keep any new route classifying the two apart.
7. **An unreadable store is not an empty store.** `AltStore`/`SecretStore` never overwrite a file they
   failed to read, and report why on `loadError()`. Preserve that whenever you touch persistence: the
   key is machine-derived, so an ordinary environment change is enough to make a good file unreadable.
8. **House style.** palantir-java-format (4-space, 120 column), full Javadoc on public and protected
   members with an `@since` on each new one. Run `./gradlew spotlessApply` before committing, and
   `./gradlew build` (JDK 25) to test.
9. **Documentation snippets are compiled.** Every Java block in
   [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) is inlined from
   [`examples/`](examples/), which `check` compiles. Change the example first, then the guide.

The shared vault implements the [Alt Vault Protocol](https://github.com/trqlmao/avp); consult that
spec for the wire contract and the fixed byte constructions (AAD, key binding).

## Building

```bash
./gradlew build
```

Requires JDK 25. The library is pure JDK + Gson and has no Minecraft dependency, so it builds standalone.

## Versioning

This project follows [Semantic Versioning](https://semver.org/). See [CHANGELOG.md](CHANGELOG.md) for release history.

## License

[MIT](LICENSE) © trqlmao

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md). For security reports, see [SECURITY.md](SECURITY.md).

## Disclaimer

This is an independent, community library. It is not affiliated with, endorsed by, or associated with
Mojang or Microsoft. "Minecraft" and related marks belong to their respective owners and are used here
only to describe what the library interoperates with. The library is provided as is, without warranty of
any kind, under the MIT license. How a consuming application obtains and uses accounts, and whether that
complies with any service's terms or with applicable law, is the consumer's responsibility. Full text:
[DISCLAIMER.md](DISCLAIMER.md).

## Activity

![Repobeats analytics image](https://repobeats.axiom.co/api/embed/160f59cd4e1a963270464593e999684fe379c368.svg "Repobeats analytics image")
