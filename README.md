# lol.trq.alts

[![](https://jitpack.io/v/trqlmao/lol.trq.alts.svg)](https://jitpack.io/#trqlmao/lol.trq.alts)
[![Build](https://github.com/trqlmao/lol.trq.alts/actions/workflows/build.yml/badge.svg)](https://github.com/trqlmao/lol.trq.alts/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> Drop-in Minecraft account-manager core for Fabric mods: auth, encrypted local storage, and async player data, all host-agnostic.

**lol.trq.alts** is a standalone, renderer-agnostic account-management library for Minecraft Fabric mods. It provides Microsoft, cookie, session, and offline login, an encrypted on-disk account store, async player-head and Hypixel-stats caches, and a clean set of host seams, with no Minecraft or renderer types of its own.

## Features

- **Four login methods.** Microsoft OAuth 2.0, browser-cookie, session-token, and offline, all `CompletableFuture`-based behind a single `AltLoginService`. You supply your own Azure app client id for Microsoft login (`MicrosoftAuthConfig`); the library ships no shared default.
- **Encrypted local store.** Accounts are persisted with AES-256-GCM and PBKDF2 in a host-chosen directory; the file never holds plaintext credentials at rest.
- **Zero-knowledge shared vault.** Share an alt repository between members with end-to-end encryption (Ed25519 identities, X25519-wrapped per-repo keys, AES-256-GCM payloads). The sync server stores only ciphertext, wrapped keys, public keys, and counters, so it can decrypt nothing.
- **Federated.** Repositories are addressed `avp://host/repoId` and reachable across independently hosted servers using one portable identity, so different clients can share alts without a central server. The wire contract is the open [Alt Vault Protocol](https://github.com/trqlmao/avp-spec).
- **Async caches.** A small `AsyncCache<K,V>` primitive (lazy, non-blocking, stale-while-revalidate) powers player-head avatars and optional Hypixel BedWars/SkyWars stats.
- **Host-agnostic.** The library never imports your mod. You provide a handful of backend seams (session injection, storage directory, texture upload, main-thread executor, toasts, stats source) and wire it once.
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
AltsRuntime<MyHandle> alts = new AltsRuntime.Builder<MyHandle>()
        .sessionInjector(new MySessionInjector())   // install a SessionData as the live session
        .vaultDirectory(() -> myDataDir)             // where the encrypted account file lives
        .textureUploader(new MyUploader())           // upload avatar bytes -> host texture
        .mainThread(myExecutor::execute)             // marshal onto the render thread
        .toastSink(new MyToastSink())                // surface login notifications
        .hypixelStatsSource(new MyHypixelSource())   // optional: BedWars/SkyWars stats
        .microsoftAuth(MicrosoftAuthConfig.of(MY_AZURE_CLIENT_ID))  // your own Azure app id
        .build();

// 2. Log in.
alts.loginService().loginMicrosoft(LoginMode.ADD)
        .thenAccept(result -> { /* result.success(), result.account() */ });
```

See [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) for a full walkthrough and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the internals.

## For AI agents

If you are an AI coding agent working on or with this library, start from [`llms.txt`](llms.txt), a
structured index of the docs in the [llmstxt.org](https://llmstxt.org) format, then read
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The points most often gotten wrong:

1. **Consumer-agnostic, always.** Never name a specific consuming mod, client, product, or sponsor in
   source, comments, docs, or commit messages. Keep examples generic (`your mod`, `the host`).
2. **Never reach into a host type.** The library imports only the JDK, Gson, and its own packages. Host
   platform behavior crosses the `spi/` seam interfaces, which the host implements.
3. **DTOs are records with `@SerializedName` on every component.** This keeps serialization stable under
   obfuscation. Do not drop the annotations or switch to field-name reflection.
4. **Microsoft login needs a host-supplied client id.** There is no built-in default; the host passes
   one through `MicrosoftAuthConfig`.
5. **House style.** palantir-java-format (4-space, 120 column), full Javadoc on public and protected
   members. Run `./gradlew spotlessApply` before committing, and `./gradlew build` (JDK 25) to test.

The shared vault implements the [Alt Vault Protocol](https://github.com/trqlmao/avp-spec); consult that
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
complies with any service's terms or with applicable law, is the consumer's responsibility.

## Activity

![Repobeats analytics image](https://repobeats.axiom.co/api/embed/8fbbf889a0dea0c16bb24d8a5dca479a4dd1bee1.svg "Repobeats analytics image")
