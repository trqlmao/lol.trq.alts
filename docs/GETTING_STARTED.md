# Getting started

This guide walks through wiring **lol.trq.alts** into a Minecraft Fabric mod.

## 1. Add the dependency

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.trqlmao:lol.trq.alts:VERSION'
}
```

The library is pure JDK + Gson. Vanilla Minecraft already ships Gson, so you do not need to bundle it.

## 2. Implement the host seams

The library never touches your mod's types. You implement a small set of backend seams in `lol.trq.alts.spi`:

| Seam | What you provide |
|---|---|
| `SessionInjector` | Turn a `SessionData` into your platform's live session and install it. |
| `VaultDirectoryProvider` | The directory the encrypted account file lives in. |
| `TextureUploader<H>` | Upload PNG bytes to a GPU texture and return your renderer's handle type `H`. |
| `MainThreadExecutor` | Run a task on the render/main thread (e.g. `MinecraftClient#execute`). |
| `ToastSink` | Surface a login notification in your UI. |
| `HypixelStatsSource` | *(optional)* Fetch BedWars/SkyWars stats; you hold the API key. |

## 3. Build the runtime

Construct exactly one `AltsRuntime` at startup, before any account screen opens:

```java
AltsRuntime<MyHandle> alts = new AltsRuntime.Builder<MyHandle>()
        .sessionInjector(new MySessionInjector())
        .vaultDirectory(() -> myDataDir)
        .textureUploader(new MyUploader())
        .mainThread(client::execute)
        .toastSink(new MyToastSink())
        .hypixelStatsSource(new MyHypixelSource())   // optional
        // Required only for Microsoft login: your own Azure app client id. Without it,
        // Microsoft login fails cleanly and offline / cookie / session login still work.
        .microsoftAuth(MicrosoftAuthConfig.of(MY_AZURE_CLIENT_ID))
        // Optional: only if migrating an existing store written under a different
        // filename / key-binding, pass the legacy values so old files keep loading.
        .storeFileName("accounts.dat")
        .storeKeyBinding("my.mod.id")
        .build();
```

For Microsoft login you must register your own [Azure application](https://learn.microsoft.com/azure/active-directory/develop/quickstart-register-app) and pass its client id — the library intentionally ships no shared default. Keep the id out of source control (for example in a git-ignored `.env` your build reads), and override the scope or endpoints with `MicrosoftAuthConfig.of(id).withScope(...)` / `.withEndpoints(...)` only if you front the services with a proxy.

## 4. Use it

```java
// Log in (adds to the stored list and switches session)
alts.loginService().loginMicrosoft(LoginMode.ADD)
        .thenAccept(result -> {
            if (result.success()) {
                // result.account() is now the current account
            }
        });

// Stored accounts
List<AltAccount> saved = AltStore.accounts();

// Lazy, cached avatar (returns null until the background fetch lands)
MyHandle head = alts.skinCache().get(account.uuid());

// Lazy, cached Hypixel stats (null if no source wired or fetch pending)
HypixelStats stats = alts.hypixelStats().get(account.uuid());
```

## Persistence and encryption

Accounts are stored in a single file in your `VaultDirectoryProvider` directory, encrypted with AES-256-GCM under a PBKDF2 key derived from machine properties plus the host key-binding constant. The library never transmits credentials; the file is bound to the machine that wrote it.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the internals.
