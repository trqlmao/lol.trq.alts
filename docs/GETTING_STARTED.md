# Getting started

This guide walks through wiring **lol.trq.alts** into a Minecraft Fabric mod.

Every Java snippet below is inlined from
[`examples/GettingStartedExample.java`](../examples/GettingStartedExample.java), which the `examples`
Gradle source set compiles on every build. That file is the authoritative copy: if a snippet here and
the example ever disagree, the example is right.

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
| `GameStatsSource` | *(optional)* Fetch one server's stats as display chips; you hold any API key. Register one per server. |

## 3. Build the runtime

Construct exactly one `AltsRuntime` at startup, before any account screen opens:

```java
AltsRuntime<MyHandle> alts = new AltsRuntime.Builder<MyHandle>()
        .sessionInjector(new MySessionInjector())
        .vaultDirectory(() -> myDataDir)
        .textureUploader(new MyUploader())
        .mainThread(client::execute)
        .toastSink(new MyToastSink())
        .gameStatsSource(new MyServerStatsSource())  // optional, one per server
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

If your credential was minted by a **legacy MSA** application rather than an Azure OAuth one, use `MicrosoftAuthConfig.legacyMsa(id)` instead of `of(id)`. The two are different dialects of the same flow — the grant must declare the desktop redirect the app registered, ask for the `MBI_SSL` scope, and hand Xbox Live a `t=`-prefixed RPS ticket — and sending the OAuth shape to a legacy app is answered with a flat `400`. Refresh tokens are the practical use: the interactive browser flow needs the desktop redirect these apps declare, which the loopback callback server cannot serve.

Compiled as `GettingStartedExample.buildRuntime`.

`build()` also reads whatever store is already on disk, so `AltStore.accounts()` is populated by the time it returns — you do not call `AltStore.load()` yourself.

A file that exists but cannot be read is a third state, and worth handling. The store's key is derived from machine properties, so a renamed OS user or a moved home directory turns a good file unreadable; the library refuses to overwrite one and tells you why, but only you can tell the user:

```java
AltStore.loadError()
        .ifPresent(reason -> alts.toasts()
                .toast(
                        ToastSink.Level.ERROR,
                        "Accounts not loaded",
                        "The saved file could not be read: " + reason,
                        8000));
```

Showing an empty list instead reads as "my accounts are gone". Compiled as
`GettingStartedExample.warnIfTheStoreDidNotLoad`.

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

for (AltAccount account : saved) {
    // Lazy, cached avatar. Keyed by USERNAME, not UUID; null until the background fetch lands.
    MyHandle head = alts.skinCache().get(account.username());

    // Lazy, cached per-server game stats (null if no source for that server, or fetch pending)
    GameStats stats = alts.gameStats("example.net").get(account.uuid());

    renderCard(head, stats);
}
```

Compiled as `GettingStartedExample.logInAndRead`.

## Logging in from a cookie file

Cookie exports arrive as files far more often than as pasted text: every browser extension that produces
them writes a `cookies.txt`, and the Netscape format they use is line-oriented, so pasting one through a
single-line input mangles it. Hand the library the path instead:

```java
alts.loginService().loginCookieFile(chosen, LoginMode.ADD).thenAccept(result -> {
    if (result.success()) {
        render(result.account().username());
    } else {
        showError(result.message());
    }
});
```

The read runs off the calling thread, so this is safe to call straight from a file-picker callback on the
render thread, and a file that is missing, is not a file, is larger than `CookieFile.MAX_BYTES`, or holds
nothing comes back as an `INVALID_TOKEN` result rather than as a thrown exception. Failure messages name
the file but never the path to it, because they end up in your UI and your log.

Three export shapes are recognised: the tab-separated **Netscape** format, the **JSON** array the common
cookie-editor extensions write (including when it is nested under a wrapper key), and text that has been
through a copy-paste and lost its structure. Decoding honours a byte-order mark — a file redirected out of
PowerShell is UTF-16LE, which read as UTF-8 yields text interleaved with NUL bytes that no cookie parser
recognises, a failure that reads to the user as "my cookies are bad" rather than "my file is in another
encoding".

If you collect cookie text some other way, `CookieFile.read(Path)` is public, so you can reuse the size
cap and the encoding handling without the login route. Compiled as
`GettingStartedExample.logInFromCookieFile`.

### The picker is yours

The library never opens a window. Choosing a file is a UI concern, and a UI concern is a host concern —
one client wants a native OS dialog, another an in-game browser, another only drag-and-drop. All the
library takes is a `Path`.

What it does supply is the filter, so your picker and its parser agree:

```java
CookieFile.EXTENSIONS   // ["txt", "json", "cookies"], no leading dot
```

Two host-side patterns worth wiring, since a user reaches for whichever is in front of them:

- **A picker button.** Open your platform's file dialog filtered on `CookieFile.EXTENSIONS`, then hand the
  chosen path to `loginCookieFile`. Marshal back onto your main thread first if your dialog runs off it.
- **Drag-and-drop.** Minecraft's `Screen` receives dropped files; pass the first one straight through. The
  library validates it, so you do not have to.

The extension list is advisory. `read` accepts any path, because a user who renamed their export is not
wrong.

## Refresh tokens and silent renewal

A Minecraft access token lasts roughly a day. A Microsoft login also yields an OAuth **refresh token**,
which the library stores on the account (`AltAccount.refreshToken()`) and redeems to mint a new session
without a browser round.

You do not have to drive this. `loginAccount` handles it:

```java
// One stored account, for example out of AltStore.accounts().
AltAccount account = saved.get(0);

// Reuses the stored session if it is still live; otherwise renews from the refresh token,
// persists the rotated credential, and installs the session — all without a browser.
alts.loginService().loginAccount(account)
        .thenAccept(result -> { /* ... */ });
```

Renewal runs on two triggers: proactively, when the stored expiry (or the token's own `exp` claim) says
the access token is spent, and once reactively, when a token that looked live is refused — which is what
happens after a password change. It never retries in a loop.

To import a refresh token you already hold, use the login route directly:

```java
alts.loginService().loginRefreshToken(myRefreshToken, LoginMode.ADD).thenAccept(result -> {
    if (result.success()) {
        // Always the rotated value; persist it for any account the store does not hold.
        persist(result.account().refreshToken());
    } else if (result.reason() == AltLoginCallback.FailureReason.NOT_CONFIGURED) {
        // Only the routes that talk to Microsoft — loginMicrosoft and loginRefreshToken — can
        // report this one. loginAccount never does.
        showError("Microsoft login is not configured");
    }
});
```

The token endpoint usually **rotates** the refresh token, issuing a new one and invalidating the old. The
rotated value is always on `result.account().refreshToken()`, and who persists it depends on where the
account came from:

- **Accounts in the local store** (added with `LoginMode.ADD`, or loaded by `AltStore.load()`) are
  updated and written for you.
- **Every other account** — one you handed to `loginAccount` straight from a shared repository, one
  logged in with `LoginMode.DIRECT`, or any record the store has never seen — is **not** persisted.
  `AltStore.updateCredentials` deliberately does nothing for a UUID it does not already hold, because
  silently writing an account the user chose not to save would be worse than not writing it. For those,
  read `result.account().refreshToken()` after every login and persist it yourself, or the next renewal
  redeems a token the service has already invalidated.

The same applies if you keep your own copy of a stored account's token: re-read it after every login.

### Branching on the failure reason

Every `LoginResult` carries a typed `AltLoginCallback.FailureReason`, so you can pick the right prompt
without matching on a message string (which does not survive obfuscation or localization):

```java
alts.loginService().loginAccount(account).thenAccept(result -> {
    if (result.success()) {
        return;
    }
    switch (result.reason()) {
        // The credential is permanently spent and has been discarded. Send the user
        // through a fresh interactive login.
        case REAUTH_REQUIRED -> promptMicrosoftLogin(account);
        // Transient: the service was unreachable or failed. The stored refresh token is
        // untouched, so offer a retry.
        case NETWORK -> showRetry(result.message());
        default -> showError(result.message());
    }
});
```

The distinction matters: a 5xx or a dropped connection never costs the user their refresh token, while a
rejection the service calls permanent clears it, so the account is not left replaying a dead credential.

Which reasons a route can emit is worth knowing: `NOT_CONFIGURED` comes only from `loginMicrosoft` and
`loginRefreshToken`, so branching on it inside a `loginAccount` handler is dead code. Compiled as
`GettingStartedExample.logInStoredAccountWithBranching`.

### Sharing policy

A refresh token is a durable credential, so it does **not** travel into a shared vault repository by
default. Opt in per repository at creation.

The facade is stateless and the identity is the member's key pair — created once, then unlocked from
its stored form on later runs. Creating an identity and creating a repository both throw the checked
`CryptoException`, so this block needs a `try` (or a `throws`):

```java
try {
    SharedVault vault = new SharedVault(new X25519HkdfAesGcmKeyWrap());
    VaultIdentity identity = VaultIdentity.create(passphrase);
    List<AltAccount> payload = AltStore.accounts();

    // Withholds refresh tokens (the default).
    SharedVault.CreatedRepo repo = vault.createRepo(identity, payload);

    // Shares them with every member of this repository.
    SharedVault.CreatedRepo sharing = vault.createRepo(identity, payload, true);
} catch (CryptoException e) {
    // Identity creation, key generation, or encryption failed.
}
```

Note the payload is a `List<AltAccount>`, not the `AltsRuntime`. Compiled as
`GettingStartedExample.createRepositories`.

The policy lives on the manifest and is enforced at the single encrypt/decrypt choke point, on both write
and read. Know what that does and does not buy you:

- **It holds against members.** A peer running a modified build that pushes refresh tokens into a
  withholding repository achieves nothing: every other member strips them on read.
- **It does not hold against the repository host.** The flag is plain manifest metadata that the server
  serves; it is neither signed nor bound into the payload AAD, so a malicious or compromised host can
  serve `shareRefreshTokens: true` to a repository that was created withholding, and members will
  believe it. Authenticating the manifest is a known gap, not a solved problem — until it lands, the
  policy is only as trustworthy as whoever hosts the repository.

**Opting in is effectively permanent.** `removeMember` and `rotateKey` re-key the repository so a removed
member cannot read *future* payloads, but a refresh token that already reached a member's disk is a
credential they hold outright: nothing in this library, or in any protocol message, can take it back. The
only real revocation is on the Microsoft side — revoke the account's sessions and change its password.
Treat enabling the flag as handing every current member durable access to those accounts, forever.

## Game stats (optional)

Stats are server-agnostic: you implement a `GameStatsSource` per server that returns ready-to-render
chips, and the library caches them. It never interprets the chips, so any server fits.

```java
public final class ExampleNetGameStatsSource implements GameStatsSource {
    @Override
    public String serverId() {
        return "example.net";
    }

    @Override
    public GameStats fetch(String uuid) {
        // Call your server's API here and format whatever matters for the card.
        return new GameStats(
                serverId(),
                uuid,
                List.of(new GameStats.Stat("rank", "Veteran"), new GameStats.Stat("wins", "128")),
                System.currentTimeMillis());
    }
}
```

Register it with `.gameStatsSource(new ExampleNetGameStatsSource())` (call it again for more servers),
then read it back per server:

```java
GameStats stats = alts.gameStats("example.net").get(playerUuid);
if (stats != null) {
    for (GameStats.Stat chip : stats.stats()) {
        render(chip.label(), chip.value());
    }
}
```

For tests and demos, the shipped `StaticGameStatsSource` returns fixed chips with no API call. The
source above is compiled as [`ExampleNetGameStatsSource`](../examples/ExampleNetGameStatsSource.java)
and the read-back as `GettingStartedExample.readGameStats`; see [../examples/](../examples/).

## Persistence and encryption

Accounts are stored in a single file in your `VaultDirectoryProvider` directory, encrypted with AES-256-GCM under a PBKDF2 key derived from machine properties plus the host key-binding constant. The library never transmits credentials; the file is bound to the machine that wrote it.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the internals.
