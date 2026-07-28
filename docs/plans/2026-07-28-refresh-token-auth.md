# Refresh-Token Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the OAuth refresh token a first-class credential — importable as a login route, used to renew stored Microsoft sessions silently, and shareable into a repository only when that repository opts in.

**Architecture:** `AltAccount` widens to carry `refreshToken` and `expiresAt`. `MicrosoftAuthUtil` keeps its Xbox/XSTS/Minecraft chain but gains a second entry point that starts from a `grant_type=refresh_token` exchange instead of an authorization code. `AltLoginServiceImpl` renews proactively on expiry and reactively once on rejection, and reports outcomes through a typed `FailureReason`. `SharedVault` strips the two new fields at its single encrypt/decrypt choke point unless the manifest sets `shareRefreshTokens`.

**Tech Stack:** Java 25, Gradle, Gson, JUnit 5 (junit-bom), palantir-java-format via Spotless, `com.sun.net.httpserver.HttpServer` for network-free tests.

## Global Constraints

- **Consumer-agnostic.** Never name a specific mod, client, launcher, product, sponsor, or third-party repository in source, Javadoc, docs, tests, or commit messages. Examples use `your mod`, `the host`, `democlient`, `vault.example`.
- **No host imports.** The library imports only the JDK, Gson, and `lol.trq.alts.*`. Platform behaviour crosses `spi/`.
- **DTOs are records** with `@SerializedName` on every component. Never drop the annotations.
- **Full Javadoc** on every public and protected member, including `@param`, `@return`, `@throws`, and `@since`. New public members get `@since 0.6.0`.
- **House format:** palantir-java-format, 4-space indent, 120 columns. Run `./gradlew spotlessApply` before every commit.
- **Build gate:** `./gradlew build` (JDK 25) must pass — it runs `spotlessCheck` plus the tests.
- **Conventional Commits.** `type(scope): summary`, lowercase after the colon, no trailing period, imperative mood. Breaking changes use `!` and a `BREAKING CHANGE:` footer. Never add a `Co-Authored-By` or any AI-attribution trailer.
- **No secrets, no machine paths.** No API keys, tokens, or `C:\Users\...`-style paths in source, tests, or docs.
- **No real people or companies in test data.** Use `Alex`, `Steve`, `democlient`, `serverone`. Existing tests that already use other values are left alone; only new or touched lines follow this.
- **Tests must not touch the public internet.** Every network test binds `com.sun.net.httpserver.HttpServer` to `loopback:0` and points config at it.
- **Commit blocks are PowerShell.** They use here-string syntax (`@'` … `'@`, closing delimiter at column 0). On a POSIX shell, substitute a heredoc or repeated `-m` flags.
- **`@since 0.6.0` on every new public member**, including members added to a record or class whose type-level `@since` is older.

---

### Task 1: Status-aware HTTP responses

`HttpUtil.executeRequest` collapses every non-2xx into `null`, so a renewal cannot tell a revoked token (4xx) from a transient outage (5xx). Without that distinction, the renewal path would wipe a user's refresh token during a momentary Microsoft outage. This task adds the status-carrying surface; the three existing methods keep their signatures and behaviour.

**Files:**
- Modify: `src/main/java/lol/trq/alts/net/HttpUtil.java`
- Test: `src/test/java/lol/trq/alts/net/HttpUtilStatusTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `HttpUtil.HttpResponse(int status, com.google.gson.JsonObject body)` with `boolean successful()`; `HttpUtil.postFormForStatus(String urlString, Map<String, String> headers, String formBody) -> HttpResponse`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/lol/trq/alts/net/HttpUtilStatusTest.java`:

```java
package lol.trq.alts.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpUtilStatusTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort() + "/token";
    }

    @Test
    void reportsSuccessStatusAndBody() throws Exception {
        String url = startServer(200, "{\"access_token\":\"a\"}");

        HttpUtil.HttpResponse response = HttpUtil.postFormForStatus(url, null, "grant_type=refresh_token");

        assertEquals(200, response.status());
        assertTrue(response.successful());
        assertNotNull(response.body());
        assertEquals("a", response.body().get("access_token").getAsString());
    }

    @Test
    void reportsClientErrorStatusAndParsesErrorBody() throws Exception {
        String url = startServer(400, "{\"error\":\"invalid_grant\"}");

        HttpUtil.HttpResponse response = HttpUtil.postFormForStatus(url, null, "grant_type=refresh_token");

        assertEquals(400, response.status());
        assertFalse(response.successful());
        assertNotNull(response.body(), "the error body must survive so the caller can classify the failure");
        assertEquals("invalid_grant", response.body().get("error").getAsString());
    }

    @Test
    void reportsServerErrorStatus() throws Exception {
        String url = startServer(503, "unavailable");

        HttpUtil.HttpResponse response = HttpUtil.postFormForStatus(url, null, "grant_type=refresh_token");

        assertEquals(503, response.status());
        assertFalse(response.successful());
        assertNull(response.body(), "a non-JSON error body parses to null rather than throwing");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'lol.trq.alts.net.HttpUtilStatusTest'`
Expected: compilation failure — `cannot find symbol: class HttpResponse`.

- [ ] **Step 3: Write the implementation**

In `HttpUtil.java`, add the record and the status-returning post. Keep `executeRequest` as-is and add a sibling that reads the error stream:

```java
    /**
     * An HTTP response carrying its status alongside the parsed body, for callers that must
     * distinguish a rejected request from a transient failure.
     *
     * @param status the HTTP status code
     * @param body the parsed JSON body, or {@code null} when the body was absent or not JSON
     * @author trq
     * @since 0.6.0
     */
    public record HttpResponse(int status, JsonObject body) {

        /**
         * Returns whether the status is in the 2xx success range.
         *
         * @return true if the request succeeded
         */
        public boolean successful() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * Sends a POST request with an x-www-form-urlencoded body, returning the status alongside the
     * parsed body so the caller can tell a rejection from an outage.
     *
     * @param urlString the target URL
     * @param headers optional HTTP headers to include in the request
     * @param formBody the form data payload as a string
     * @return the status and parsed body; the body is null when absent or not JSON
     * @throws Exception if a network or protocol error occurs
     * @since 0.6.0
     */
    public static HttpResponse postFormForStatus(String urlString, Map<String, String> headers, String formBody)
            throws Exception {
        return executeForStatus(
                urlString,
                "POST",
                "application/x-www-form-urlencoded",
                headers,
                formBody.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResponse executeForStatus(
            String urlString, String method, String contentType, Map<String, String> headers, byte[] body)
            throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);

        if (contentType != null) {
            conn.setRequestProperty("Content-Type", contentType);
        }

        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

        if (headers != null) {
            headers.forEach(conn::setRequestProperty);
        }

        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
        }

        int status = conn.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) {
            return new HttpResponse(status, null);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            JsonElement parsed = JsonParser.parseReader(br);
            return new HttpResponse(status, parsed.isJsonObject() ? parsed.getAsJsonObject() : null);
        } catch (Exception parseFailure) {
            return new HttpResponse(status, null);
        }
    }
```

Add the imports `com.google.gson.JsonElement`, `java.io.InputStream`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew spotlessApply && ./gradlew test --tests 'lol.trq.alts.net.HttpUtilStatusTest'`
Expected: 3 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/lol/trq/alts/net/HttpUtil.java src/test/java/lol/trq/alts/net/HttpUtilStatusTest.java
git commit -m "feat(net): expose HTTP status and error body for form posts"
```

---

### Task 2: Widen `AltAccount`

**Files:**
- Modify: `src/main/java/lol/trq/alts/model/AltAccount.java`
- Test: `src/test/java/lol/trq/alts/model/AltAccountSerializationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `AltAccount` with components `(String uuid, String username, String accessToken, AccountType type, long lastUsed, String lastUsedBy, Map<String, BanInfo> bans, String sourceClient, String sourceUser, String refreshToken, long expiresAt)`; `AltAccount.withTokens(String accessToken, String refreshToken, long expiresAt)`; `AltAccount.hasRefreshToken()`.

The two new components go **last** so the existing positional order is untouched for every current call site that constructs the record directly.

- [ ] **Step 1: Write the failing tests**

Append to `AltAccountSerializationTest.java`:

```java
    @Test
    void tokenFieldsDefaultToAbsentOnLegacyPayloads() {
        String legacy =
                "{\"uuid\":\"u\",\"username\":\"Steve\",\"accessToken\":\"tok\",\"type\":\"MICROSOFT\",\"lastUsed\":1}";

        AltAccount account = gson.fromJson(legacy, AltAccount.class);

        assertNull(account.refreshToken(), "legacy files predate the refreshToken field");
        assertEquals(0L, account.expiresAt(), "legacy files predate the expiresAt field");
        assertFalse(account.hasRefreshToken());
    }

    @Test
    void withTokensReplacesCredentialsAndPreservesEverythingElse() {
        AltAccount base = AltAccount.of("u", "Alex", "old-access", AccountType.MICROSOFT)
                .withSource("democlient", "user1")
                .withBan("hypixel", BanInfo.observed("self", "x"));

        AltAccount renewed = base.withTokens("new-access", "new-refresh", 1717000000000L);

        assertEquals("new-access", renewed.accessToken());
        assertEquals("new-refresh", renewed.refreshToken());
        assertEquals(1717000000000L, renewed.expiresAt());
        assertTrue(renewed.hasRefreshToken());
        assertEquals("democlient", renewed.sourceClient(), "withTokens preserves provenance");
        assertEquals("user1", renewed.sourceUser(), "withTokens preserves provenance");
        assertTrue(renewed.banned("hypixel"), "withTokens preserves bans");
        assertEquals(base.lastUsed(), renewed.lastUsed(), "withTokens does not restamp last-used");
    }

    @Test
    void tokenFieldsRoundTripThroughGson() {
        AltAccount original = AltAccount.of("u", "Alex", "access", AccountType.MICROSOFT)
                .withTokens("access", "refresh", 42L);

        AltAccount restored = gson.fromJson(gson.toJson(original), AltAccount.class);

        assertEquals(original, restored);
        assertEquals("refresh", restored.refreshToken());
        assertEquals(42L, restored.expiresAt());
    }

    @Test
    void hasRefreshTokenRejectsBlankValues() {
        AltAccount blank = AltAccount.of("u", "Alex", "access", AccountType.MICROSOFT).withTokens("access", "  ", 0L);

        assertFalse(blank.hasRefreshToken(), "a blank token is not usable for renewal");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'lol.trq.alts.model.AltAccountSerializationTest'`
Expected: compilation failure — `cannot find symbol: method refreshToken()`.

- [ ] **Step 3: Write the implementation**

In `AltAccount.java`, add the two components to the record header and extend the Javadoc:

```java
        @SerializedName("sourceUser") String sourceUser,
        @SerializedName("refreshToken") String refreshToken,
        @SerializedName("expiresAt") long expiresAt) {
```

Add to the record's class Javadoc, after the `sourceClient`/`sourceUser` paragraph:

```java
 * <p>{@code refreshToken} and {@code expiresAt} back silent session renewal. The refresh token is a
 * durable credential — far longer-lived than the access token — so it is stripped from a shared
 * repository payload unless that repository has explicitly opted in. {@code expiresAt} is epoch millis
 * and {@code 0} means "unknown", which callers read as "assume expired and renew".
```

Add the two `@param` lines:

```java
 * @param refreshToken the OAuth refresh token used to renew the session, or {@code null} when the
 *     account has none (cookie, session, and offline accounts never do)
 * @param expiresAt the epoch-millis expiry of {@code accessToken}, or {@code 0} when unknown
```

Update the existing factory and every copy method to thread the new components through. `of` supplies the absent pair:

```java
    public static AltAccount of(String uuid, String username, String accessToken, AccountType type) {
        return new AltAccount(
                uuid, username, accessToken, type, System.currentTimeMillis(), null, null, null, null, null, 0L);
    }
```

`usedNow()`, `usedNow(String)`, `withBan(String, BanInfo)`, and `withSource(String, String)` each gain `refreshToken, expiresAt` as their final two constructor arguments, preserving the current values.

Add the new members:

```java
    /**
     * Returns a copy of this account carrying freshly issued credentials, preserving attribution, bans,
     * and provenance. Used by the renewal path, which must persist the rotated refresh token.
     *
     * @param accessToken the newly issued access token
     * @param refreshToken the newly issued refresh token, or {@code null} to clear it
     * @param expiresAt the epoch-millis expiry of {@code accessToken}, or {@code 0} when unknown
     * @return a copy carrying the given credentials
     * @since 0.6.0
     */
    public AltAccount withTokens(String accessToken, String refreshToken, long expiresAt) {
        return new AltAccount(
                uuid,
                username,
                accessToken,
                type,
                lastUsed,
                lastUsedBy,
                bans,
                sourceClient,
                sourceUser,
                refreshToken,
                expiresAt);
    }

    /**
     * Returns whether this account carries a usable refresh token.
     *
     * @return true if a non-blank refresh token is present
     * @since 0.6.0
     */
    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }
```

- [ ] **Step 4: Fix all five existing direct constructor calls**

Widening the record breaks every positional construction. There are five, across three test files — `AltAccountSerializationTest:78`, `SharedVaultTest:34`, `SharedVaultTest:100`, `SharedVaultTest:127`, and `FederationJoinTest:58`. Each gains a trailing `, null, 0L`.

In `AltAccountSerializationTest`, also drop the real-person name while touching the line:

```java
        AltAccount base = new AltAccount(
                "u", "Steve", "t", AccountType.OFFLINE, 1L, null, null, "democlient", "user1", null, 0L);
```

The assertions in that test reference the username only through `base.username()`, so nothing else changes. Leave the other four files' assertions untouched — they only need the two extra arguments.

Run `./gradlew compileJava compileTestJava` and fix anything else the compiler flags the same way.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew spotlessApply && ./gradlew test --tests 'lol.trq.alts.model.AltAccountSerializationTest' --tests 'lol.trq.alts.store.AltStoreBanMigrationTest'`
Expected: all PASS. The migration test is included because it exercises the store's deserialization path.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/lol/trq/alts/model/AltAccount.java src/test/java/lol/trq/alts/model/AltAccountSerializationTest.java src/test/java/lol/trq/alts/vault/SharedVaultTest.java src/test/java/lol/trq/alts/vault/federation/FederationJoinTest.java
git commit -m @'
feat(model)!: carry a refresh token and access-token expiry

Accounts gain refreshToken and expiresAt so a stored Microsoft session can
be renewed instead of dying with its access token. Both are absent on
accounts written by earlier versions, which read as "no refresh token,
expiry unknown" and therefore renew on first use.

BREAKING CHANGE: AltAccount gains two trailing components, so positional
construction of the record must supply them. AltAccount.of is unchanged.
'@
```

---

### Task 3: Typed failure reasons

**Files:**
- Modify: `src/main/java/lol/trq/alts/auth/AltLoginCallback.java`
- Test: `src/test/java/lol/trq/alts/auth/LoginResultReasonTest.java`

**Interfaces:**
- Consumes: `AltAccount` (Task 2).
- Produces: `AltLoginCallback.FailureReason` enum with constants `NONE, UNKNOWN, REAUTH_REQUIRED, INVALID_TOKEN, NETWORK, NOT_CONFIGURED`; `LoginResult(boolean success, String message, AltAccount account, FailureReason reason)`; factories `success(AltAccount)`, `failure(String)`, `failure(String, FailureReason)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/lol/trq/alts/auth/LoginResultReasonTest.java`:

```java
package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lol.trq.alts.auth.AltLoginCallback.FailureReason;
import lol.trq.alts.auth.AltLoginCallback.LoginResult;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import org.junit.jupiter.api.Test;

class LoginResultReasonTest {

    @Test
    void successCarriesNoFailureReason() {
        AltAccount account = AltAccount.of("u", "Alex", "tok", AccountType.MICROSOFT);

        LoginResult result = LoginResult.success(account);

        assertTrue(result.success());
        assertEquals(FailureReason.NONE, result.reason());
        assertEquals(account, result.account());
    }

    @Test
    void unclassifiedFailureIsUnknownRatherThanMislabelled() {
        LoginResult result = LoginResult.failure("session injection blew up");

        assertFalse(result.success());
        assertNull(result.account());
        assertEquals(FailureReason.UNKNOWN, result.reason());
        assertEquals("session injection blew up", result.message());
    }

    @Test
    void classifiedFailureKeepsItsReason() {
        LoginResult result = LoginResult.failure("refresh token rejected", FailureReason.REAUTH_REQUIRED);

        assertFalse(result.success());
        assertEquals(FailureReason.REAUTH_REQUIRED, result.reason());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'lol.trq.alts.auth.LoginResultReasonTest'`
Expected: compilation failure — `cannot find symbol: class FailureReason`.

- [ ] **Step 3: Write the implementation**

In `AltLoginCallback.java`, add the enum inside the interface, above `LoginResult`:

```java
    /**
     * Why a login attempt failed, in a form a host can branch on. Matching on the human-readable
     * message is not viable: it is subject to obfuscation and localization.
     *
     * @author trq
     * @since 0.6.0
     */
    enum FailureReason {
        /** The attempt succeeded; carried by every successful result. */
        NONE,

        /** The attempt failed for a reason the library cannot classify. */
        UNKNOWN,

        /**
         * The stored credentials are permanently spent and the account needs a fresh interactive
         * login. Any refresh token held for the account has been discarded.
         */
        REAUTH_REQUIRED,

        /** The supplied token was empty, malformed, or rejected as invalid. */
        INVALID_TOKEN,

        /** The attempt could not reach the authentication service, or the service failed. Retryable. */
        NETWORK,

        /** The flow requires host configuration that was not supplied. */
        NOT_CONFIGURED
    }
```

Widen the record and its factories:

```java
    record LoginResult(boolean success, String message, AltAccount account, FailureReason reason) {

        /**
         * Creates a successful login result.
         *
         * @param account the account that was successfully authenticated
         * @return a new result indicating success
         */
        public static LoginResult success(AltAccount account) {
            return new LoginResult(true, "Logged in successfully", account, FailureReason.NONE);
        }

        /**
         * Creates a failed login result with no classified cause.
         *
         * @param reason the reason why the authentication failed
         * @return a new result indicating an unclassified failure
         */
        public static LoginResult failure(String reason) {
            return failure(reason, FailureReason.UNKNOWN);
        }

        /**
         * Creates a failed login result carrying a machine-readable cause.
         *
         * @param message the human-readable reason why the authentication failed
         * @param cause the classified cause, for hosts that branch on the outcome
         * @return a new result indicating failure
         * @since 0.6.0
         */
        public static LoginResult failure(String message, FailureReason cause) {
            return new LoginResult(false, message, null, cause);
        }
    }
```

Add the `@param reason` line to the record's Javadoc.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew spotlessApply && ./gradlew test --tests 'lol.trq.alts.auth.LoginResultReasonTest'`
Expected: 3 tests, all PASS.

- [ ] **Step 5: Classify the existing failure sites**

In `AltLoginServiceImpl.java`, give each existing `LoginResult.failure(...)` its correct cause:

| Line context | Cause |
| --- | --- |
| `"Token empty"` | `INVALID_TOKEN` |
| `"Login failed: " + e.getMessage()` in `loginSession` | `INVALID_TOKEN` |
| `"Invalid username length (1-16 chars)"` | `INVALID_TOKEN` |
| `"Error: " + e.getMessage()` in `loginOffline` | `UNKNOWN` |
| `"Microsoft login not configured"` | `NOT_CONFIGURED` |
| `"Microsoft Auth: " + msg` | `UNKNOWN` |
| `"Cookie data empty"` | `INVALID_TOKEN` |
| `"Cookie Auth: " + msg` | `INVALID_TOKEN` |
| `"Session Injection: " + e.getMessage()` | `UNKNOWN` |

- [ ] **Step 6: Run the full build**

Run: `./gradlew spotlessApply && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/lol/trq/alts/auth/AltLoginCallback.java src/main/java/lol/trq/alts/auth/AltLoginServiceImpl.java src/test/java/lol/trq/alts/auth/LoginResultReasonTest.java
git commit -m @'
feat(auth)!: classify login failures with a typed reason

Hosts need to tell "this account needs a fresh interactive login" from
"the network is down" in order to render the right prompt. Matching on the
message string is not viable under obfuscation or localization.

BREAKING CHANGE: LoginResult gains a trailing FailureReason component.
LoginResult.failure(String) is retained and maps to UNKNOWN.
'@
```

---

### Task 4: Refresh-token exchange

**Files:**
- Modify: `src/main/java/lol/trq/alts/auth/MinecraftProfile.java`
- Modify: `src/main/java/lol/trq/alts/auth/MicrosoftAuthUtil.java`
- Test: `src/test/java/lol/trq/alts/auth/RefreshTokenExchangeTest.java`

**Interfaces:**
- Consumes: `HttpUtil.HttpResponse` and `HttpUtil.postFormForStatus` (Task 1).
- Produces: `MinecraftProfile(String username, String uuid, String accessToken, String refreshToken, long expiresAt)`; `MicrosoftAuthUtil.authenticateWithRefreshToken(MicrosoftAuthConfig config, String refreshToken) -> CompletableFuture<MinecraftProfile>`; `MicrosoftAuthUtil.RefreshRejectedException extends RuntimeException` with `boolean permanent()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/lol/trq/alts/auth/RefreshTokenExchangeTest.java`. It stands up a loopback server that answers all five endpoints, so no traffic leaves the machine:

```java
package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RefreshTokenExchangeTest {

    private HttpServer server;
    private final AtomicReference<String> tokenRequestBody = new AtomicReference<>();
    private int tokenStatus = 200;
    private String tokenBody = "{\"access_token\":\"ms-access\",\"refresh_token\":\"rotated\",\"expires_in\":3600}";

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private MicrosoftAuthConfig startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", exchange -> {
            tokenRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, tokenStatus, tokenBody);
        });
        server.createContext("/xbl", exchange -> respond(exchange, 200, "{\"Token\":\"xbl\"}"));
        server.createContext(
                "/xsts",
                exchange -> respond(
                        exchange, 200, "{\"Token\":\"xsts\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"hash\"}]}}"));
        server.createContext(
                "/mclogin",
                exchange -> respond(exchange, 200, "{\"access_token\":\"mc-access\",\"expires_in\":86400}"));
        server.createContext(
                "/mcprofile",
                exchange -> respond(exchange, 200, "{\"id\":\"00000000000040008000000000000001\",\"name\":\"Alex\"}"));
        server.start();

        String base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort();
        return MicrosoftAuthConfig.of("test-client-id")
                .withEndpoints(
                        base + "/authorize",
                        base + "/token",
                        base + "/xbl",
                        base + "/xsts",
                        base + "/mclogin",
                        base + "/mcprofile");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void sendsRefreshGrantAndReturnsRotatedToken() throws Exception {
        MicrosoftAuthConfig config = startServer();

        MinecraftProfile profile =
                MicrosoftAuthUtil.authenticateWithRefreshToken(config, "original-refresh").get();

        String body = tokenRequestBody.get();
        assertTrue(body.contains("grant_type=refresh_token"), "must use the refresh grant: " + body);
        assertTrue(body.contains("refresh_token=original-refresh"), "must send the stored token: " + body);
        assertTrue(body.contains("client_id=test-client-id"), "must send the host client id: " + body);
        assertFalse(body.contains("grant_type=authorization_code"), "must not use the code grant: " + body);

        assertEquals("Alex", profile.username());
        assertEquals("00000000-0000-4000-8000-000000000001", profile.uuid());
        assertEquals("mc-access", profile.accessToken());
        assertEquals("rotated", profile.refreshToken(), "the rotated token must survive, not the original");

        // The Minecraft token lives ~24h and the Microsoft OAuth token ~1h. The stamped expiry must
        // describe the token actually stored on the account, which is the Minecraft one.
        long fromNow = profile.expiresAt() - System.currentTimeMillis();
        assertTrue(fromNow > 80_000_000L, "expiry must come from the Minecraft lifetime, not the OAuth one: " + fromNow);
        assertTrue(fromNow <= 86_400_000L, "expiry must not exceed the advertised lifetime: " + fromNow);
    }

    @Test
    void keepsTheOriginalTokenWhenMicrosoftOmitsARotation() throws Exception {
        tokenBody = "{\"access_token\":\"ms-access\",\"expires_in\":3600}";
        MicrosoftAuthConfig config = startServer();

        MinecraftProfile profile =
                MicrosoftAuthUtil.authenticateWithRefreshToken(config, "original-refresh").get();

        assertEquals("original-refresh", profile.refreshToken(), "omitted rotation must not clear the token");
    }

    @Test
    void clientErrorIsAPermanentRejection() throws Exception {
        tokenStatus = 400;
        tokenBody = "{\"error\":\"invalid_grant\"}";
        MicrosoftAuthConfig config = startServer();

        ExecutionException thrown = assertThrows(
                ExecutionException.class,
                () -> MicrosoftAuthUtil.authenticateWithRefreshToken(config, "revoked").get());

        MicrosoftAuthUtil.RefreshRejectedException rejection =
                assertInstanceOf(MicrosoftAuthUtil.RefreshRejectedException.class, thrown.getCause());
        assertTrue(rejection.permanent(), "a 4xx means the token will never work again");
    }

    @Test
    void serverErrorIsATransientRejection() throws Exception {
        tokenStatus = 503;
        tokenBody = "unavailable";
        MicrosoftAuthConfig config = startServer();

        ExecutionException thrown = assertThrows(
                ExecutionException.class,
                () -> MicrosoftAuthUtil.authenticateWithRefreshToken(config, "fine").get());

        MicrosoftAuthUtil.RefreshRejectedException rejection =
                assertInstanceOf(MicrosoftAuthUtil.RefreshRejectedException.class, thrown.getCause());
        assertFalse(rejection.permanent(), "a 5xx must not cost the user their refresh token");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'lol.trq.alts.auth.RefreshTokenExchangeTest'`
Expected: compilation failure — `cannot find symbol: method authenticateWithRefreshToken`.

- [ ] **Step 3: Widen `MinecraftProfile`**

```java
/**
 * The resolved Minecraft account profile produced by an authentication flow.
 *
 * @param username the player's Minecraft username
 * @param uuid the player's account UUID
 * @param accessToken the Minecraft session access token
 * @param refreshToken the OAuth refresh token to persist for renewal, or {@code null} when the flow
 *     produced none
 * @param expiresAt the epoch-millis expiry of {@code accessToken}, or {@code 0} when unknown
 * @author trq
 * @since 0.1.0
 */
public record MinecraftProfile(
        String username, String uuid, String accessToken, String refreshToken, long expiresAt) {}
```

- [ ] **Step 4: Restructure `MicrosoftAuthUtil`**

Add `expiresIn` to `MsTokens` and thread the tokens through to the profile:

```java
    /** Holds the Microsoft OAuth access and refresh tokens together with the access token's lifetime. */
    private record MsTokens(String accessToken, String refreshToken, long expiresIn) {}
```

`exchangeCodeForToken` returns `new MsTokens(access, refresh, expiresIn)`, reading `expires_in` defensively (`response.has("expires_in") ? response.get("expires_in").getAsLong() : 0L`).

Extract the shared tail so both entry points use it:

```java
    /**
     * Runs the Xbox Live, XSTS, Minecraft services, and profile steps shared by both entry points.
     *
     * @param config the host's Microsoft authentication configuration
     * @param tokens the Microsoft tokens produced by whichever first step ran
     * @return a future containing the resolved profile
     */
    private static CompletableFuture<MinecraftProfile> completeFrom(MicrosoftAuthConfig config, MsTokens tokens) {
        return authenticateWithXboxLive(config, tokens)
                .thenCompose(xblToken -> authenticateWithXSTS(config, xblToken))
                .thenCompose(xstsData -> authenticateWithMinecraft(config, xstsData))
                .thenCompose(session -> getMinecraftProfile(config, session, tokens));
    }
```

**The expiry must come from the Minecraft response, not the Microsoft one.** `MsTokens.expiresIn` describes the Microsoft OAuth access token, which lives about an hour; the token that goes into `AltAccount.accessToken` is the Minecraft services token from `login_with_xbox`, which lives about a day. Stamping the former onto the latter would mark every renewed account expired roughly twenty-three hours early and re-redeem the refresh token on every single login.

So step 4 returns both values. Change `authenticateWithMinecraft` to yield a record instead of a bare string:

```java
    /** Holds the Minecraft services session token and its advertised lifetime in seconds. */
    private record McSession(String accessToken, long expiresIn) {}
```

Its body reads the lifetime alongside the token, defaulting to `0` when absent:

```java
                JsonObject response = HttpUtil.postJson(config.minecraftLoginUrl(), null, body.toString());
                if (response == null) throw new Exception("MC services auth failed");
                long expiresIn = response.has("expires_in") ? response.get("expires_in").getAsLong() : 0L;
                return new McSession(response.get("access_token").getAsString(), expiresIn);
```

and its return type becomes `CompletableFuture<McSession>`.

`authenticate(config)` becomes:

```java
        return server.start()
                .thenCompose(code -> exchangeCodeForToken(config, code, server.redirectUri()))
                .thenCompose(tokens -> completeFrom(config, tokens))
                .whenComplete((profile, error) -> server.stop());
```

`getMinecraftProfile` takes the `McSession` and the `MsTokens`, using the Minecraft lifetime for the expiry and the Microsoft refresh token for renewal. Its signature becomes `getMinecraftProfile(MicrosoftAuthConfig config, McSession session, MsTokens tokens)`, it authorizes with `session.accessToken()`, and it returns:

```java
                return new MinecraftProfile(
                        username, uuid, session.accessToken(), tokens.refreshToken(),
                        absoluteExpiry(session.expiresIn()));
```

with

```java
    /**
     * Converts an advertised lifetime in seconds into an absolute epoch-millis expiry.
     *
     * @param expiresInSeconds the lifetime in seconds, or a non-positive value when unknown
     * @return the absolute expiry, or {@code 0} when the lifetime was unknown
     */
    private static long absoluteExpiry(long expiresInSeconds) {
        return expiresInSeconds <= 0 ? 0L : System.currentTimeMillis() + (expiresInSeconds * 1000L);
    }
```

`MsTokens.expiresIn` is now unused by the profile and exists only to keep the record honest about what the token endpoint returned; keep it, since Task 7 never reads it and dropping it would mean reshaping the record twice.

Add the new entry point and the rejection type:

```java
    /**
     * Renews a session from a stored OAuth refresh token, skipping the interactive browser step. The
     * token endpoint issues a rotated refresh token on success; the returned profile carries it, and
     * callers must persist it or the next renewal will fail.
     *
     * @param config the host's Microsoft authentication configuration (client id, scope, endpoints)
     * @param refreshToken the stored refresh token to redeem
     * @return a future containing the renewed {@link MinecraftProfile}
     * @throws NullPointerException if {@code config} is null
     * @since 0.6.0
     */
    public static CompletableFuture<MinecraftProfile> authenticateWithRefreshToken(
            MicrosoftAuthConfig config, String refreshToken) {
        Objects.requireNonNull(config, "config");
        if (refreshToken == null || refreshToken.isBlank()) {
            return CompletableFuture.failedFuture(new RefreshRejectedException("refresh token is blank", true));
        }
        return exchangeRefreshForToken(config, refreshToken).thenCompose(tokens -> completeFrom(config, tokens));
    }

    private static CompletableFuture<MsTokens> exchangeRefreshForToken(
            MicrosoftAuthConfig config, String refreshToken) {
        return CompletableFuture.supplyAsync(() -> {
            HttpUtil.HttpResponse response;
            try {
                String body = String.format(
                        "client_id=%s&refresh_token=%s&grant_type=refresh_token&scope=%s",
                        config.clientId(),
                        URLEncoder.encode(refreshToken, StandardCharsets.UTF_8),
                        URLEncoder.encode(config.scope(), StandardCharsets.UTF_8));
                response = HttpUtil.postFormForStatus(config.tokenUrl(), null, body);
            } catch (Exception transportFailure) {
                throw new RefreshRejectedException("refresh transport failure", false, transportFailure);
            }

            if (!response.successful() || response.body() == null) {
                boolean permanent = response.status() >= 400 && response.status() < 500;
                throw new RefreshRejectedException("refresh rejected with status " + response.status(), permanent);
            }

            JsonObject json = response.body();
            String rotated = json.has("refresh_token")
                    ? json.get("refresh_token").getAsString()
                    : refreshToken;
            long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 0L;
            return new MsTokens(json.get("access_token").getAsString(), rotated, expiresIn);
        });
    }

    /**
     * Signals that a refresh-token redemption failed, distinguishing a token that will never work
     * again from a failure worth retrying.
     *
     * @author trq
     * @since 0.6.0
     */
    public static final class RefreshRejectedException extends RuntimeException {

        private final boolean permanent;

        /**
         * Creates a rejection.
         *
         * @param message the failure description
         * @param permanent whether the refresh token is permanently spent
         * @since 0.6.0
         */
        public RefreshRejectedException(String message, boolean permanent) {
            this(message, permanent, null);
        }

        /**
         * Creates a rejection carrying the underlying failure, so a connection error keeps its
         * diagnostic instead of being reported as a bare message.
         *
         * @param message the failure description
         * @param permanent whether the refresh token is permanently spent
         * @param cause the underlying failure, or {@code null}
         * @since 0.6.0
         */
        public RefreshRejectedException(String message, boolean permanent, Throwable cause) {
            super(message, cause);
            this.permanent = permanent;
        }

        /**
         * Returns whether the refresh token is permanently spent and must be discarded.
         *
         * @return true if the token will never succeed again
         */
        public boolean permanent() {
            return permanent;
        }
    }
```

Note: `rotated` falls back to the supplied token when Microsoft omits `refresh_token`, so an omitted rotation never clears a working credential.

- [ ] **Step 5: Fix the remaining `MinecraftProfile` construction**

`CookieAuthUtil.authenticate` builds a `MinecraftProfile`. Extend that call with `null, 0L` — the cookie flow yields no refresh token. Run `./gradlew compileJava` and fix anything else the compiler flags.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew spotlessApply && ./gradlew test --tests 'lol.trq.alts.auth.RefreshTokenExchangeTest'`
Expected: 4 tests, all PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/lol/trq/alts/auth/MinecraftProfile.java src/main/java/lol/trq/alts/auth/MicrosoftAuthUtil.java src/main/java/lol/trq/alts/auth/CookieAuthUtil.java src/test/java/lol/trq/alts/auth/RefreshTokenExchangeTest.java
git commit -m @'
feat(auth)!: redeem OAuth refresh tokens for a Minecraft session

The refresh token returned by the token endpoint was parsed and dropped,
so the offline_access scope bought nothing. Redeeming it reuses the
existing Xbox Live, XSTS, and Minecraft services chain unchanged; only the
first step differs. A 4xx is reported as a permanent rejection and a 5xx
as transient, so an outage never costs the user a working credential.

BREAKING CHANGE: MinecraftProfile gains refreshToken and expiresAt
components.
'@
```

---

### Task 5: Configurable profile endpoint

Testing the renewal path requires validating an access token without reaching live Mojang infrastructure. `AccountNetworkUtil` hardcodes its URLs while `MicrosoftAuthConfig` already parameterizes the same profile endpoint. This closes that gap.

**Files:**
- Modify: `src/main/java/lol/trq/alts/auth/AccountNetworkUtil.java`
- Test: `src/test/java/lol/trq/alts/auth/AccountNetworkUtilTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `AccountNetworkUtil.fetchProfileFromToken(String token, String profileUrl) -> String[]`; the existing one-argument overload delegates using `MicrosoftAuthConfig.DEFAULT_MINECRAFT_PROFILE_URL`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/lol/trq/alts/auth/AccountNetworkUtilTest.java`:

```java
package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AccountNetworkUtilTest {

    private HttpServer server;
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/profile", exchange -> {
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort() + "/profile";
    }

    @Test
    void resolvesProfileFromTheSuppliedEndpoint() throws Exception {
        String url = startServer(200, "{\"id\":\"abc\",\"name\":\"Alex\"}");

        String[] profile = AccountNetworkUtil.fetchProfileFromToken("tok", url);

        assertArrayEquals(new String[] {"Alex", "abc"}, profile);
        assertEquals("Bearer tok", seenAuthorization.get());
    }

    @Test
    void returnsNullWhenTheTokenIsRejected() throws Exception {
        String url = startServer(401, "{}");

        assertNull(AccountNetworkUtil.fetchProfileFromToken("expired", url));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'lol.trq.alts.auth.AccountNetworkUtilTest'`
Expected: compilation failure — no two-argument `fetchProfileFromToken`.

- [ ] **Step 3: Write the implementation**

```java
    /**
     * Validates a Minecraft access token against the default profile endpoint.
     *
     * @param token the Minecraft/Bearer access token to validate
     * @return a String array containing {@code [username, uuid]}, or {@code null} if the token is invalid
     * @throws Exception if a network error occurs during the request
     */
    public static String[] fetchProfileFromToken(String token) throws Exception {
        return fetchProfileFromToken(token, MicrosoftAuthConfig.DEFAULT_MINECRAFT_PROFILE_URL);
    }

    /**
     * Validates a Minecraft access token against a caller-supplied profile endpoint, so a host that
     * fronts Minecraft services with its own proxy validates through the same route it authenticates
     * through.
     *
     * @param token the Minecraft/Bearer access token to validate
     * @param profileUrl the Minecraft services profile endpoint
     * @return a String array containing {@code [username, uuid]}, or {@code null} if the token is invalid
     * @throws Exception if a network error occurs during the request
     * @since 0.6.0
     */
    public static String[] fetchProfileFromToken(String token, String profileUrl) throws Exception {
        JsonObject response = HttpUtil.get(profileUrl, Map.of("Authorization", "Bearer " + token));

        if (response != null && response.has("name") && response.has("id")) {
            return new String[] {response.get("name").getAsString(), response.get("id").getAsString()};
        }

        return null;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew spotlessApply && ./gradlew test --tests 'lol.trq.alts.auth.AccountNetworkUtilTest'`
Expected: 2 tests, both PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/lol/trq/alts/auth/AccountNetworkUtil.java src/test/java/lol/trq/alts/auth/AccountNetworkUtilTest.java
git commit -m "feat(auth): let callers supply the profile endpoint"
```

---

### Task 6: Expiry checking and the expired-JWT fix

`attemptFastJwtLogin` decodes the JWT payload for a name and UUID but never inspects `exp`, so an expired token reports success and installs a dead session. This task adds a testable clock, an expiry helper, and the `exp` gate.

**Files:**
- Create: `src/main/java/lol/trq/alts/auth/TokenExpiry.java`
- Modify: `src/main/java/lol/trq/alts/auth/AltLoginServiceImpl.java`
- Test: `src/test/java/lol/trq/alts/auth/TokenExpiryTest.java`

**Interfaces:**
- Consumes: `AltAccount` (Task 2).
- Produces: `TokenExpiry.SKEW_MILLIS` (`60_000L`); `TokenExpiry.jwtExpiryMillis(String token) -> long` (0 when absent or unparseable); `TokenExpiry.isExpired(AltAccount account, java.time.Clock clock) -> boolean`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/lol/trq/alts/auth/TokenExpiryTest.java`:

```java
package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import org.junit.jupiter.api.Test;

class TokenExpiryTest {

    private static final long NOW = 1_800_000_000_000L;

    private static Clock at(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static String jwtExpiringAt(long epochSeconds) {
        String payload = "{\"name\":\"Alex\",\"id\":\"abc\",\"exp\":" + epochSeconds + "}";
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return "eyJhbGciOiJIUzI1NiJ9." + encoded + ".sig";
    }

    private static AltAccount accountWith(String accessToken, long expiresAt) {
        return AltAccount.of("u", "Alex", accessToken, AccountType.MICROSOFT).withTokens(accessToken, "r", expiresAt);
    }

    @Test
    void readsTheExpiryClaimFromAJwt() {
        assertEquals(1_800_000_500_000L, TokenExpiry.jwtExpiryMillis(jwtExpiringAt(1_800_000_500L)));
    }

    @Test
    void reportsNoExpiryForNonJwtTokens() {
        assertEquals(0L, TokenExpiry.jwtExpiryMillis("not-a-jwt"));
        assertEquals(0L, TokenExpiry.jwtExpiryMillis(""));
        assertEquals(0L, TokenExpiry.jwtExpiryMillis(null));
    }

    @Test
    void storedExpiryInThePastIsExpired() {
        assertTrue(TokenExpiry.isExpired(accountWith("opaque", NOW - 1), at(NOW)));
    }

    @Test
    void storedExpiryComfortablyAheadIsLive() {
        assertFalse(TokenExpiry.isExpired(accountWith("opaque", NOW + 600_000L), at(NOW)));
    }

    @Test
    void expiryInsideTheSkewMarginCountsAsExpired() {
        assertTrue(
                TokenExpiry.isExpired(accountWith("opaque", NOW + TokenExpiry.SKEW_MILLIS - 1), at(NOW)),
                "a token expiring mid-handshake must be renewed first");
    }

    @Test
    void unknownStoredExpiryFallsBackToTheJwtClaim() {
        AltAccount live = accountWith(jwtExpiringAt((NOW + 600_000L) / 1000L), 0L);
        AltAccount dead = accountWith(jwtExpiringAt((NOW - 600_000L) / 1000L), 0L);

        assertFalse(TokenExpiry.isExpired(live, at(NOW)));
        assertTrue(TokenExpiry.isExpired(dead, at(NOW)));
    }

    @Test
    void whollyUnknownExpiryIsTreatedAsExpired() {
        assertTrue(
                TokenExpiry.isExpired(accountWith("opaque", 0L), at(NOW)),
                "with no expiry signal at all, renewing is the safe default");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'lol.trq.alts.auth.TokenExpiryTest'`
Expected: compilation failure — `cannot find symbol: class TokenExpiry`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/lol/trq/alts/auth/TokenExpiry.java`:

```java
package lol.trq.alts.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import lol.trq.alts.model.AltAccount;

/**
 * Expiry arithmetic for Minecraft access tokens. An account's stored expiry is authoritative when
 * known; otherwise the token's own {@code exp} claim is read, and when neither is available the token
 * is treated as expired, because renewing an already-valid session is cheap and installing a dead one
 * is not.
 *
 * @author trq
 * @since 0.6.0
 */
public final class TokenExpiry {

    /**
     * How far ahead of the real expiry a token is considered spent, so a session that would lapse
     * during the handshake is renewed first.
     */
    public static final long SKEW_MILLIS = 60_000L;

    private TokenExpiry() {}

    /**
     * Reads the {@code exp} claim from a JWT access token.
     *
     * @param token the access token, which may be null, empty, opaque, or malformed
     * @return the expiry in epoch millis, or {@code 0} when the token carries no readable claim
     */
    public static long jwtExpiryMillis(String token) {
        if (token == null || !token.startsWith("eyJ")) {
            return 0L;
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return 0L;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(padded(parts[1]));
            JsonObject payload =
                    JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();
            return payload.has("exp") ? payload.get("exp").getAsLong() * 1000L : 0L;
        } catch (Exception unreadable) {
            return 0L;
        }
    }

    /**
     * Returns whether an account's access token is spent, within the skew margin.
     *
     * @param account the account to inspect
     * @param clock the clock to read the current time from
     * @return true if the token should be renewed before use
     */
    public static boolean isExpired(AltAccount account, Clock clock) {
        long expiry = account.expiresAt() > 0 ? account.expiresAt() : jwtExpiryMillis(account.accessToken());
        if (expiry <= 0) {
            return true;
        }
        return clock.millis() >= expiry - SKEW_MILLIS;
    }

    private static String padded(String base64Url) {
        int padding = (4 - base64Url.length() % 4) % 4;
        return base64Url + "=".repeat(padding);
    }
}
```

- [ ] **Step 4: Gate the fast path on a live expiry**

In `AltLoginServiceImpl.attemptFastJwtLogin`, after decoding the payload and before returning success, reject a spent token so it falls through to the network path or to renewal:

```java
    private Optional<AltLoginCallback.LoginResult> attemptFastJwtLogin(String token, LoginMode mode) {
        try {
            long expiry = TokenExpiry.jwtExpiryMillis(token);
            if (expiry > 0 && clock.millis() >= expiry - TokenExpiry.SKEW_MILLIS) {
                return Optional.empty();
            }

            String payload = decodeJwtPayload(token);
            String uuid = extractRegex(payload, "\"(?:id|mc)\"\\s*:\\s*\"([a-fA-F0-9\\-]+)\"");
            String username = extractRegex(payload, "\"name\"\\s*:\\s*\"([^\"]+)\"");

            if (uuid != null && username != null) {
                return Optional.of(finalizeLogin(username, uuid, token, AccountType.SESSION, mode));
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }
```

Add the clock field and a package-private constructor that takes one, leaving the public constructor's signature untouched:

```java
    private final Clock clock;

    public AltLoginServiceImpl(SessionInjector sessionInjector, MicrosoftAuthConfig microsoftAuth) {
        this(sessionInjector, microsoftAuth, Clock.systemUTC());
    }

    AltLoginServiceImpl(SessionInjector sessionInjector, MicrosoftAuthConfig microsoftAuth, Clock clock) {
        this.sessionInjector = Objects.requireNonNull(sessionInjector, "sessionInjector");
        this.microsoftAuth = microsoftAuth;
        this.clock = Objects.requireNonNull(clock, "clock");
    }
```

Give the public constructor full Javadoc including `@param`, and mark the package-private one with a short comment noting it exists for deterministic expiry tests.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew spotlessApply && ./gradlew test --tests 'lol.trq.alts.auth.TokenExpiryTest'`
Expected: 7 tests, all PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/lol/trq/alts/auth/TokenExpiry.java src/main/java/lol/trq/alts/auth/AltLoginServiceImpl.java src/test/java/lol/trq/alts/auth/TokenExpiryTest.java
git commit -m @'
fix(auth): reject expired tokens on the fast JWT path

The fast path read a token's name and UUID claims without checking exp, so
an expired token reported a successful login, installed a dead session,
and failed later at the server with no useful diagnostic. It now falls
through to validation instead.
'@
```

---

### Task 7: The refresh-token route and silent renewal

**Files:**
- Modify: `src/main/java/lol/trq/alts/auth/AltLoginService.java`
- Modify: `src/main/java/lol/trq/alts/auth/AltLoginServiceImpl.java`
- Test: `src/test/java/lol/trq/alts/auth/RefreshLoginRouteTest.java`

**Interfaces:**
- Consumes: `MicrosoftAuthUtil.authenticateWithRefreshToken` and `RefreshRejectedException` (Task 4); `AccountNetworkUtil.fetchProfileFromToken(String, String)` (Task 5); `TokenExpiry` and the clock constructor (Task 6); `FailureReason` (Task 3); `AltAccount.withTokens` (Task 2).
- Produces: `AltLoginService.loginRefreshToken(String refreshToken, LoginMode mode)`; renewal behaviour inside `loginAccount`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/lol/trq/alts/auth/RefreshLoginRouteTest.java`. It reuses the five-endpoint loopback server pattern from Task 4, binds `AltStore` to a JUnit `@TempDir`, and supplies a recording `SessionInjector`:

```java
package lol.trq.alts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lol.trq.alts.auth.AltLoginCallback.FailureReason;
import lol.trq.alts.auth.AltLoginCallback.LoginResult;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.BanInfo;
import lol.trq.alts.model.LoginMode;
import lol.trq.alts.model.SessionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RefreshLoginRouteTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String UUID = "00000000-0000-4000-8000-000000000001";

    @TempDir
    Path vaultDir;

    private HttpServer server;
    private MicrosoftAuthConfig config;
    private final AtomicReference<SessionData> injected = new AtomicReference<>();
    private final AtomicReference<String> tokenRequestBody = new AtomicReference<>("");
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger profileCalls = new AtomicInteger();
    private final AtomicBoolean refuseProfileOnce = new AtomicBoolean();

    private int tokenStatus = 200;
    private String tokenBody = "{\"access_token\":\"ms-access\",\"refresh_token\":\"rotated\",\"expires_in\":3600}";
    private int profileStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        AltStoreTestSupport.bindTo(vaultDir);

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            tokenRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, tokenStatus, tokenBody);
        });
        server.createContext("/xbl", exchange -> respond(exchange, 200, "{\"Token\":\"xbl\"}"));
        server.createContext(
                "/xsts",
                exchange -> respond(
                        exchange, 200, "{\"Token\":\"xsts\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"hash\"}]}}"));
        server.createContext(
                "/mclogin",
                exchange -> respond(exchange, 200, "{\"access_token\":\"mc-access\",\"expires_in\":86400}"));
        server.createContext("/mcprofile", exchange -> {
            int call = profileCalls.incrementAndGet();
            int status = profileStatus;
            if (refuseProfileOnce.get() && call == 1) {
                status = 401;
            }
            respond(
                    exchange,
                    status,
                    status == 200 ? "{\"id\":\"00000000000040008000000000000001\",\"name\":\"Alex\"}" : "{}");
        });
        server.start();

        String base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort();
        config = MicrosoftAuthConfig.of("test-client-id")
                .withEndpoints(
                        base + "/authorize",
                        base + "/token",
                        base + "/xbl",
                        base + "/xsts",
                        base + "/mclogin",
                        base + "/mcprofile");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private AltLoginServiceImpl service() {
        return new AltLoginServiceImpl(
                injected::set, config, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void refreshRouteLogsInAndStoresTheRotatedToken() throws Exception {
        LoginResult result = service().loginRefreshToken("original", LoginMode.ADD).get();

        assertTrue(result.success(), result.message());
        assertEquals("Alex", result.account().username());
        assertEquals("rotated", result.account().refreshToken());
        assertEquals(AccountType.MICROSOFT, result.account().type());
        assertEquals("Alex", injected.get().username());
    }

    @Test
    void refreshRouteRequiresConfiguration() throws Exception {
        AltLoginServiceImpl unconfigured = new AltLoginServiceImpl(
                injected::set, null, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        LoginResult result = unconfigured.loginRefreshToken("original", LoginMode.ADD).get();

        assertFalse(result.success());
        assertEquals(FailureReason.NOT_CONFIGURED, result.reason());
    }

    @Test
    void expiredAccountRenewsAndPersistsTheRotatedToken() throws Exception {
        AltAccount stored = AltAccount.of(UUID, "Alex", "stale", AccountType.MICROSOFT)
                .withTokens("stale", "stored-refresh", NOW - 1)
                .withBan("serverone", BanInfo.observed("self", "x"))
                .withSource("democlient", "user1");
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertTrue(result.success(), result.message());
        assertEquals(1, tokenCalls.get(), "an expired account renews exactly once");
        assertTrue(
                tokenRequestBody.get().contains("refresh_token=stored-refresh"),
                "the stored token must reach the wire: " + tokenRequestBody.get());
        assertEquals("mc-access", result.account().accessToken());
        assertEquals("rotated", result.account().refreshToken(), "the rotated token replaces the stored one");

        AltAccount persisted = AltStoreTestSupport.find(UUID);
        assertEquals("rotated", persisted.refreshToken(), "the rotated token must reach disk, not just memory");
        assertEquals(AccountType.MICROSOFT, persisted.type(), "renewal must not change the account type");
        assertTrue(persisted.banned("serverone"), "renewal must preserve shared ban records");
        assertEquals("democlient", persisted.sourceClient(), "renewal must preserve provenance");
    }

    @Test
    void liveAccountInjectsTheStoredRecordWithoutStrippingIt() throws Exception {
        AltAccount stored = AltAccount.of(UUID, "Alex", "opaque-live", AccountType.MICROSOFT)
                .withTokens("opaque-live", "stored-refresh", NOW + 3_600_000L)
                .withBan("serverone", BanInfo.observed("self", "x"));
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertTrue(result.success(), result.message());
        assertEquals(0, tokenCalls.get(), "a live token must not trigger a renewal");

        // Regression guard: routing this path through loginSession would rebuild the account as
        // AccountType.SESSION with a null refresh token, permanently disabling renewal.
        assertEquals("stored-refresh", result.account().refreshToken(), "a live login must not drop the credential");
        assertEquals(AccountType.MICROSOFT, result.account().type(), "a live login must not retype the account");
        assertEquals("stored-refresh", AltStoreTestSupport.find(UUID).refreshToken());
        assertEquals(AccountType.MICROSOFT, AltStoreTestSupport.find(UUID).type());
        assertTrue(AltStoreTestSupport.find(UUID).banned("serverone"), "a live login must preserve bans");
    }

    @Test
    void connectionFailureIsTransientAndKeepsTheStoredToken() throws Exception {
        AltAccount stored = AltAccount.of(UUID, "Alex", "stale", AccountType.MICROSOFT)
                .withTokens("stale", "still-good", NOW - 1);
        AltStoreTestSupport.seed(stored);

        // Point the token endpoint at a port nothing is listening on.
        server.stop(0);
        server = null;

        LoginResult result = service().loginAccount(stored).get();

        assertFalse(result.success());
        assertEquals(FailureReason.NETWORK, result.reason());
        assertEquals(
                "still-good",
                AltStoreTestSupport.find(UUID).refreshToken(),
                "an unreachable service must not cost the user their credential");
    }

    @Test
    void rejectedAccessTokenRenewsOnceThenSucceeds() throws Exception {
        // The stored token is live by the clock but refused by the service — a token revoked early, as
        // happens after a password change. The first profile fetch refuses; the renewed chain's fetch
        // succeeds, so the reactive path is genuinely exercised end to end.
        refuseProfileOnce.set(true);
        AltAccount stored = AltAccount.of(UUID, "Alex", "opaque-live", AccountType.MICROSOFT)
                .withTokens("opaque-live", "stored-refresh", NOW + 3_600_000L);
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertTrue(result.success(), result.message());
        assertEquals(1, tokenCalls.get(), "renewal is attempted exactly once, never in a loop");
        assertEquals("rotated", AltStoreTestSupport.find(UUID).refreshToken());
    }

    @Test
    void aSecondRejectionFailsRatherThanLooping() throws Exception {
        profileStatus = 401;
        AltAccount stored = AltAccount.of(UUID, "Alex", "opaque-live", AccountType.MICROSOFT)
                .withTokens("opaque-live", "stored-refresh", NOW + 3_600_000L);
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertFalse(result.success(), "the renewed chain saw the same refusing endpoint");
        assertEquals(1, tokenCalls.get(), "renewal must not retry itself");
    }

    @Test
    void permanentRejectionClearsTheStoredRefreshTokenAndAsksForReauth() throws Exception {
        tokenStatus = 400;
        tokenBody = "{\"error\":\"invalid_grant\"}";
        AltAccount stored = AltAccount.of(UUID, "Alex", "stale", AccountType.MICROSOFT)
                .withTokens("stale", "revoked-refresh", NOW - 1);
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertFalse(result.success());
        assertEquals(FailureReason.REAUTH_REQUIRED, result.reason());
        assertNull(
                AltStoreTestSupport.find(stored.uuid()).refreshToken(),
                "a permanently rejected token must not stay on disk");
    }

    @Test
    void transientRejectionKeepsTheStoredRefreshToken() throws Exception {
        tokenStatus = 503;
        tokenBody = "unavailable";
        AltAccount stored = AltAccount.of(UUID, "Alex", "stale", AccountType.MICROSOFT)
                .withTokens("stale", "still-good", NOW - 1);
        AltStoreTestSupport.seed(stored);

        LoginResult result = service().loginAccount(stored).get();

        assertFalse(result.success());
        assertEquals(FailureReason.NETWORK, result.reason());
        assertEquals(
                "still-good",
                AltStoreTestSupport.find(stored.uuid()).refreshToken(),
                "an outage must not cost the user their credential");
    }
}
```

- [ ] **Step 2: Add the test support helper**

`AltStore` is a static façade, so tests need it bound to a temp directory. Create `src/test/java/lol/trq/alts/auth/AltStoreTestSupport.java`:

```java
package lol.trq.alts.auth;

import java.nio.file.Path;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.store.AltStore;

/** Binds the static {@link AltStore} to a scratch directory so login tests can assert on persistence. */
final class AltStoreTestSupport {

    private AltStoreTestSupport() {}

    static void bindTo(Path directory) {
        AltStore.bind(() -> directory);
        AltStore.accounts().clear();
        AltStore.useAccount(null);
    }

    static void seed(AltAccount account) {
        AltStore.addAccount(account);
    }

    static AltAccount find(String uuid) {
        return AltStore.accounts().stream()
                .filter(a -> a.uuid().equals(uuid))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stored account with uuid " + uuid));
    }
}
```

Confirm `VaultDirectoryProvider` is a single-method interface returning `Path`; if its method name differs, use an explicit anonymous class instead of the lambda.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests 'lol.trq.alts.auth.RefreshLoginRouteTest'`
Expected: compilation failure — `cannot find symbol: method loginRefreshToken`.

- [ ] **Step 4: Declare the route**

In `AltLoginService.java`:

```java
    /**
     * Authenticates using a stored OAuth refresh token, skipping the interactive browser step. The
     * token endpoint issues a rotated refresh token, which is stored on the resulting account.
     *
     * @param refreshToken the OAuth refresh token to redeem
     * @param mode the login mode
     * @return a future containing the result of the login attempt
     * @since 0.6.0
     */
    CompletableFuture<AltLoginCallback.LoginResult> loginRefreshToken(String refreshToken, LoginMode mode);
```

Update the interface Javadoc: "via Microsoft OAuth, browser cookies, session tokens, refresh tokens, or offline (cracked) identities."

- [ ] **Step 5: Implement the route and renewal**

In `AltLoginServiceImpl.java`:

```java
    @Override
    public CompletableFuture<AltLoginCallback.LoginResult> loginRefreshToken(String refreshToken, LoginMode mode) {
        if (microsoftAuth == null) {
            return CompletableFuture.completedFuture(
                    AltLoginCallback.LoginResult.failure("Microsoft login not configured", FailureReason.NOT_CONFIGURED));
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            return CompletableFuture.completedFuture(
                    AltLoginCallback.LoginResult.failure("Refresh token empty", FailureReason.INVALID_TOKEN));
        }
        return MicrosoftAuthUtil.authenticateWithRefreshToken(microsoftAuth, refreshToken)
                .thenApply(profile -> finalizeLogin(profile, AccountType.MICROSOFT, mode))
                .exceptionally(ex -> refreshFailure(ex, null));
    }
```

`loginAccount` becomes the following. **Do not route the live path through `loginSession`.** That method's `finalizeLogin` rebuilds the account with `AltAccount.of(..., AccountType.SESSION)` — no refresh token, no expiry, wrong type — and `AltStore.useAccount` then replaces the stored entry with that stripped copy. A single successful live login would permanently disable renewal for the account. The live path must inject the stored record itself.

```java
    @Override
    public CompletableFuture<AltLoginCallback.LoginResult> loginAccount(AltAccount account) {
        if (account.type() == AccountType.OFFLINE) {
            return loginOffline(account.username(), LoginMode.DIRECT);
        }
        if (!account.hasRefreshToken() || microsoftAuth == null) {
            return loginSession(account.accessToken(), LoginMode.DIRECT);
        }
        if (TokenExpiry.isExpired(account, clock)) {
            return renew(account);
        }
        return useStored(account).thenCompose(result -> {
            if (result.success()) {
                return CompletableFuture.completedFuture(result);
            }
            return renew(account);
        });
    }

    /**
     * Validates an account's stored access token and installs the stored record as-is, preserving its
     * type, refresh token, bans, and provenance.
     *
     * @param account the stored account to use
     * @return a future holding the outcome; a failure means the token was refused and renewal should run
     */
    private CompletableFuture<AltLoginCallback.LoginResult> useStored(AltAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (AccountNetworkUtil.fetchProfileFromToken(account.accessToken(), profileUrl()) == null) {
                    return AltLoginCallback.LoginResult.failure(
                            "Stored token refused", FailureReason.INVALID_TOKEN);
                }
            } catch (Exception unreachable) {
                return AltLoginCallback.LoginResult.failure(
                        "Validation failed: " + unreachable.getMessage(), FailureReason.NETWORK);
            }
            return inject(account);
        });
    }

    /**
     * Renews an account from its stored refresh token, persisting the rotated credentials before
     * installing the session. The renewed account is derived from the stored one, so bans, provenance,
     * and shared attribution survive the renewal.
     *
     * @param account the stored account to renew
     * @return a future holding the outcome
     */
    private CompletableFuture<AltLoginCallback.LoginResult> renew(AltAccount account) {
        return MicrosoftAuthUtil.authenticateWithRefreshToken(microsoftAuth, account.refreshToken())
                .thenApply(profile -> {
                    AltAccount renewed =
                            account.withTokens(profile.accessToken(), profile.refreshToken(), profile.expiresAt());
                    AltStore.updateCredentials(renewed);
                    return inject(renewed);
                })
                .exceptionally(ex -> refreshFailure(ex, account));
    }

    /**
     * Installs an already-resolved account as the live session without rebuilding it.
     *
     * @param account the account to install
     * @return the login result
     */
    private AltLoginCallback.LoginResult inject(AltAccount account) {
        try {
            sessionInjector.inject(
                    new SessionData(account.username(), account.uuid(), account.accessToken(), account.type()));
            AltStore.useAccount(account);
            return AltLoginCallback.LoginResult.success(account);
        } catch (Exception e) {
            return AltLoginCallback.LoginResult.failure("Session Injection: " + e.getMessage(), FailureReason.UNKNOWN);
        }
    }

    /**
     * Returns the profile endpoint to validate against — the configured one when Microsoft login is
     * wired up, and the public default otherwise.
     *
     * @return the Minecraft services profile endpoint
     */
    private String profileUrl() {
        return microsoftAuth != null
                ? microsoftAuth.minecraftProfileUrl()
                : MicrosoftAuthConfig.DEFAULT_MINECRAFT_PROFILE_URL;
    }

    /**
     * Maps a failed renewal onto a classified result, discarding the stored refresh token only when the
     * rejection is permanent. A transient failure must never cost the user a working credential.
     */
    private AltLoginCallback.LoginResult refreshFailure(Throwable ex, AltAccount account) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        boolean permanent = cause instanceof MicrosoftAuthUtil.RefreshRejectedException rejection
                && rejection.permanent();

        if (permanent && account != null) {
            AltStore.clearRefreshToken(account.uuid());
        }

        return AltLoginCallback.LoginResult.failure(
                "Refresh: " + (cause.getMessage() != null ? cause.getMessage() : "unknown error"),
                permanent ? FailureReason.REAUTH_REQUIRED : FailureReason.NETWORK);
    }
```

Add a `finalizeLogin(MinecraftProfile, AccountType, LoginMode)` overload for the two flows that produce a *brand-new* account — the browser flow and the refresh-token import route. Those have no stored record to preserve, so building one from scratch is correct here and only here:

```java
    /**
     * Finalizes a flow that produced a brand-new account, carrying the issued credentials onto it.
     *
     * @param profile the resolved profile
     * @param type the type of account used
     * @param mode the login mode
     * @return the login result
     */
    private AltLoginCallback.LoginResult finalizeLogin(MinecraftProfile profile, AccountType type, LoginMode mode) {
        AltAccount account = AltAccount.of(formatUuid(profile.uuid()), profile.username(), profile.accessToken(), type)
                .withTokens(profile.accessToken(), profile.refreshToken(), profile.expiresAt());

        if (mode == LoginMode.ADD) {
            AltStore.addAccount(account);
        }
        return inject(account);
    }
```

Add `import lol.trq.alts.auth.AltLoginCallback.FailureReason;` — the class sits in the same package as the interface, so the nested enum still needs importing or qualifying.

Point `loginMicrosoft` at the new overload so the browser flow also persists its refresh token, keeping its existing message mapping verbatim:

```java
        return MicrosoftAuthUtil.authenticate(microsoftAuth)
                .thenApply(profile -> finalizeLogin(profile, AccountType.MICROSOFT, mode))
                .exceptionally(ex -> {
                    String msg = ex.getMessage();
                    if (ex.getCause() != null) msg = ex.getCause().getMessage();
                    return AltLoginCallback.LoginResult.failure("Microsoft Auth: " + msg, FailureReason.UNKNOWN);
                });
```

Route `loginSession`'s network validation through the configured endpoint:

```java
                String[] profile = AccountNetworkUtil.fetchProfileFromToken(cleanToken, profileUrl());
```

- [ ] **Step 6: Add the store mutators**

Two are needed. `AltStore.useAccount` mutates only the in-memory list — it never calls `save()` — so without a persisting mutator the rotated refresh token would never reach disk and the account would die on the *second* renewal, which is precisely the failure this design exists to remove.

```java
    /**
     * Replaces the stored entry for {@code account}'s UUID with the given record and persists. Called
     * after a renewal so the rotated refresh token survives a restart; without it the next process
     * start would replay a token the authentication service has already invalidated. A no-op if the
     * UUID is unknown, which is the case for an account that was never added to storage.
     *
     * @param account the account carrying freshly issued credentials
     * @since 0.6.0
     */
    public static void updateCredentials(AltAccount account) {
        ACCOUNTS.replaceAll(a -> a.uuid().equals(account.uuid()) ? account : a);
        if (currentAccount != null && currentAccount.uuid().equals(account.uuid())) {
            currentAccount = account;
        }
        save();
    }
```

and:

```java
    /**
     * Discards the refresh token and expiry for the account with {@code uuid} and persists. Called when
     * the authentication service permanently rejects the token, so a spent credential is not retried or
     * left at rest. A no-op if the UUID is unknown.
     *
     * @param uuid the dashed UUID of the account whose refresh token is spent
     * @since 0.6.0
     */
    public static void clearRefreshToken(String uuid) {
        ACCOUNTS.replaceAll(a -> a.uuid().equals(uuid) ? a.withTokens(a.accessToken(), null, 0L) : a);
        if (currentAccount != null && currentAccount.uuid().equals(uuid)) {
            currentAccount = currentAccount.withTokens(currentAccount.accessToken(), null, 0L);
        }
        save();
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew spotlessApply && ./gradlew test --tests 'lol.trq.alts.auth.RefreshLoginRouteTest'`
Expected: 9 tests, all PASS.

- [ ] **Step 8: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```powershell
git add src/main/java/lol/trq/alts/auth/AltLoginService.java src/main/java/lol/trq/alts/auth/AltLoginServiceImpl.java src/main/java/lol/trq/alts/store/AltStore.java src/test/java/lol/trq/alts/auth/RefreshLoginRouteTest.java src/test/java/lol/trq/alts/auth/AltStoreTestSupport.java
git commit -m @'
feat(auth)!: add a refresh-token route and renew sessions silently

Stored Microsoft accounts replayed an access token that expires in roughly
a day, so every saved alt died and the only recovery was a full browser
round per account. They now renew from the stored refresh token, both
ahead of a known expiry and once reactively when a token is refused.

A permanently rejected token is discarded and reported as
REAUTH_REQUIRED; a transient failure keeps the credential and reports
NETWORK.

BREAKING CHANGE: AltLoginService gains an abstract loginRefreshToken
method, so any host implementing the interface directly must add it.
'@
```

---

### Task 8: Per-repository sharing policy

**Files:**
- Modify: `src/main/java/lol/trq/alts/vault/transport/VaultManifest.java`
- Modify: `src/main/java/lol/trq/alts/vault/RepoContext.java`
- Modify: `src/main/java/lol/trq/alts/vault/SharedVault.java`
- Test: `src/test/java/lol/trq/alts/vault/RefreshTokenSharingTest.java`

**Interfaces:**
- Consumes: `AltAccount.withTokens` and `hasRefreshToken` (Task 2).
- Produces: `VaultManifest(String repoId, String schemeId, long keyEpoch, long payloadVersion, List<MemberEntry> members, boolean shareRefreshTokens)`; `RepoContext(String repoId, VaultIdentity identity, RepoDataKey dataKey, long payloadVersion, boolean shareRefreshTokens)`; `SharedVault.createRepo(VaultIdentity, List<AltAccount>, boolean)` alongside the existing two-argument form.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/lol/trq/alts/vault/RefreshTokenSharingTest.java`:

```java
package lol.trq.alts.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import lol.trq.alts.crypto.VaultIdentity;
import lol.trq.alts.crypto.X25519HkdfAesGcmKeyWrap;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.vault.transport.EncryptedEnvelope;
import org.junit.jupiter.api.Test;

class RefreshTokenSharingTest {

    private final SharedVault vault = new SharedVault(new X25519HkdfAesGcmKeyWrap());

    private static AltAccount withRefresh() {
        return AltAccount.of("u", "Alex", "access", AccountType.MICROSOFT).withTokens("access", "secret-refresh", 99L);
    }

    @Test
    void repositoryDefaultsToWithholdingRefreshTokens() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());

        SharedVault.CreatedRepo repo = vault.createRepo(creator, List.of(withRefresh()));

        assertFalse(repo.manifest().shareRefreshTokens(), "sharing a durable credential must be opt-in");
    }

    @Test
    void refreshTokensAreStrippedFromAWithholdingRepository() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());
        SharedVault.CreatedRepo repo = vault.createRepo(creator, List.of(withRefresh()));

        List<AltAccount> decrypted = vault.decryptPayload(repo.context(), repo.envelope(), 0L);

        assertEquals(1, decrypted.size());
        assertEquals("access", decrypted.get(0).accessToken(), "the access token still travels");
        assertNull(decrypted.get(0).refreshToken(), "the refresh token must not travel");
        assertEquals(0L, decrypted.get(0).expiresAt());
    }

    @Test
    void refreshTokensSurviveInAnOptedInRepository() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());
        SharedVault.CreatedRepo repo = vault.createRepo(creator, List.of(withRefresh()), true);

        List<AltAccount> decrypted = vault.decryptPayload(repo.context(), repo.envelope(), 0L);

        assertTrue(repo.manifest().shareRefreshTokens());
        assertEquals("secret-refresh", decrypted.get(0).refreshToken());
        assertEquals(99L, decrypted.get(0).expiresAt());
    }

    @Test
    void aPeerCannotSmuggleRefreshTokensIntoAWithholdingRepository() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());
        SharedVault.CreatedRepo permissive = vault.createRepo(creator, List.of(withRefresh()), true);

        // Same key material, but the reader's policy withholds. This models a peer running a modified
        // build that writes tokens into a repository whose manifest forbids them.
        RepoContext withholding = new RepoContext(
                permissive.context().repoId(),
                permissive.context().identity(),
                permissive.context().dataKey(),
                permissive.context().payloadVersion(),
                false);
        EncryptedEnvelope smuggled = permissive.envelope();

        List<AltAccount> decrypted = vault.decryptPayload(withholding, smuggled, 0L);

        assertNull(decrypted.get(0).refreshToken(), "the policy must hold regardless of what a peer sends");
    }

    @Test
    void rotationPreservesAWithholdingPolicy() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());
        SharedVault.CreatedRepo repo = vault.createRepo(creator, List.of(withRefresh()), false);

        SharedVault.RotationResult rotated = vault.rotateKey(repo.context(), List.of(withRefresh()), List.of());
        List<AltAccount> decrypted = vault.decryptPayload(rotated.context(), rotated.envelope(), 0L);

        assertFalse(rotated.context().shareRefreshTokens());
        assertNull(decrypted.get(0).refreshToken(), "a key rotation must not widen the policy");
    }

    @Test
    void rotationPreservesAnOptedInPolicy() throws Exception {
        VaultIdentity creator = VaultIdentity.create("pw".toCharArray());
        SharedVault.CreatedRepo repo = vault.createRepo(creator, List.of(withRefresh()), true);

        SharedVault.RotationResult rotated = vault.rotateKey(repo.context(), List.of(withRefresh()), List.of());
        List<AltAccount> decrypted = vault.decryptPayload(rotated.context(), rotated.envelope(), 0L);

        // Regression guard: rotateKey builds a fresh RepoContext. Hardcoding false there would silently
        // downgrade an opted-in repository on every rotation and on every removeMember, and the next
        // encryptPayload would strip the tokens permanently.
        assertTrue(rotated.context().shareRefreshTokens(), "rotation must carry the policy forward");
        assertEquals("secret-refresh", decrypted.get(0).refreshToken());
    }
}
```

`VaultIdentity.create(char[])` is the real factory — confirmed against `VaultIdentity.java:51` and `SharedVaultTest`. There is no `generate()`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'lol.trq.alts.vault.RefreshTokenSharingTest'`
Expected: compilation failure — `cannot find symbol: method shareRefreshTokens()`.

- [ ] **Step 3: Widen the manifest**

Add the component with Javadoc:

```java
 * @param shareRefreshTokens whether members may share OAuth refresh tokens through this repository;
 *     {@code false} by default, because a refresh token grants durable account access rather than the
 *     roughly one day an access token buys
```

```java
        @SerializedName("members") List<MemberEntry> members,
        @SerializedName("shareRefreshTokens") boolean shareRefreshTokens) {
```

A manifest written by an earlier version has no such key, so Gson yields `false` — the safe reading.

- [ ] **Step 4: Widen the context**

`RepoContext` gains `boolean shareRefreshTokens` as its final component, with `withPayloadVersion` and `withDataKey` preserving it. Add the `@param` line.

- [ ] **Step 5: Thread the policy through `SharedVault`**

`createRepo` gains an overload; the existing two-argument form delegates with `false`:

```java
    /**
     * Creates a repository that withholds refresh tokens.
     *
     * @param creator the creating member's unlocked identity
     * @param alts the initial alt payload (may be empty)
     * @return the created repository artifacts
     * @throws CryptoException if key generation or encryption fails
     */
    public CreatedRepo createRepo(VaultIdentity creator, List<AltAccount> alts) throws CryptoException {
        return createRepo(creator, alts, false);
    }

    /**
     * Creates a repository with an explicit refresh-token sharing policy.
     *
     * @param creator the creating member's unlocked identity
     * @param alts the initial alt payload (may be empty)
     * @param shareRefreshTokens whether members may share refresh tokens through this repository
     * @return the created repository artifacts
     * @throws CryptoException if key generation or encryption fails
     * @since 0.6.0
     */
    public CreatedRepo createRepo(VaultIdentity creator, List<AltAccount> alts, boolean shareRefreshTokens)
            throws CryptoException {
```

Inside, the manifest and context both take `shareRefreshTokens`, and the `encrypt` call passes it. `openRepo` reads `manifest.shareRefreshTokens()` into the context. `encryptPayload` passes `ctx.shareRefreshTokens()`.

`rotateKey` needs it in **two** places, and missing the second is the trap: it passes `ctx.shareRefreshTokens()` to `encrypt`, *and* it must propagate it into the fresh `RepoContext` it builds at `SharedVault.java:201`:

```java
        RepoContext newCtx =
                new RepoContext(ctx.repoId(), ctx.identity(), newDataKey, newVersion, ctx.shareRefreshTokens());
```

Supplying `false` there compiles fine and silently downgrades an opted-in repository on every rotation and every `removeMember`, after which the next `encryptPayload` strips the tokens for good.

`encrypt` gains the parameter and strips before serializing:

```java
    private EncryptedEnvelope encrypt(
            String repoId,
            long version,
            long epoch,
            RepoDataKey dataKey,
            List<AltAccount> alts,
            boolean shareRefreshTokens)
            throws CryptoException {
        String json = GSON.toJson(new VaultPayload(applyPolicy(alts, shareRefreshTokens), version));
```

`decryptPayload` strips on the way out:

```java
        return payload == null || payload.alts() == null
                ? new ArrayList<>()
                : applyPolicy(payload.alts(), ctx.shareRefreshTokens());
```

The shared helper:

```java
    /**
     * Applies the repository's refresh-token policy to a list of alts. Stripping happens on both write
     * and read: on write so the credential never reaches the server, and on read so a peer running a
     * modified build cannot push tokens into a repository whose policy forbids them.
     */
    private static List<AltAccount> applyPolicy(List<AltAccount> alts, boolean shareRefreshTokens) {
        List<AltAccount> copy = new ArrayList<>(alts.size());
        for (AltAccount alt : alts) {
            copy.add(shareRefreshTokens ? alt : alt.withTokens(alt.accessToken(), null, 0L));
        }
        return copy;
    }
```

- [ ] **Step 6: Fix existing construction sites**

Three test files construct `VaultManifest` positionally — `SharedVaultTest:47`, `SharedVaultTest:74`, `SharedVaultTest:89`, `FederationJoinTest:67`, and `TransportDtoSerializationTest:37`. No test constructs `RepoContext` directly except the new one added in Step 1. Add the trailing `false` to each manifest. Run `./gradlew compileTestJava` and fix anything else the compiler flags. Do not change what those tests assert.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew spotlessApply && ./gradlew test --tests 'lol.trq.alts.vault.*'`
Expected: the new 6 plus every existing vault test, all PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/lol/trq/alts/vault src/test/java/lol/trq/alts/vault
git commit -m @'
feat(vault)!: gate refresh-token sharing behind a repository policy

An access token buys about a day; a refresh token grants durable access to
the Microsoft account until it is revoked. Sharing one is a materially
larger decision, so repositories withhold them unless the manifest opts
in. Stripping happens on read as well as write, so the policy holds even
if a peer runs a modified build.

BREAKING CHANGE: VaultManifest and RepoContext each gain a trailing
shareRefreshTokens component. SharedVault.createRepo keeps its
two-argument form, which withholds.
'@
```

---

### Task 9: Documentation and changelog

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/GETTING_STARTED.md`
- Modify: `CHANGELOG.md`
- Modify: `build.gradle:8`

**Interfaces:**
- Consumes: everything above.
- Produces: no code surface.

- [ ] **Step 1: Update the README**

Change the "Four login methods" bullet to five and name the refresh route. Add a sentence to the encrypted-store bullet noting that sessions renew silently from a stored refresh token instead of expiring after roughly a day. Extend the zero-knowledge bullet: refresh tokens are withheld from a shared repository unless it opts in. Add the route to the quick start:

```java
// Renew from a refresh token, no browser step.
alts.loginService().loginRefreshToken(storedRefreshToken, LoginMode.ADD)
        .thenAccept(result -> { /* result.success(), result.reason() */ });
```

- [ ] **Step 2: Update `docs/ARCHITECTURE.md`**

In the `auth/` bullet, change "four `CompletableFuture`-based login methods" to five and mention that `loginAccount` renews expired sessions. In the layer diagram, the `auth/` line becomes `login flows (MS / refresh / cookie / session / offline)`. In the `crypto/` + `vault/SharedVault` bullet, note that the manifest's `shareRefreshTokens` flag gates refresh tokens and that stripping happens on both write and read.

- [ ] **Step 3: Update `docs/GETTING_STARTED.md`**

Add a section covering the refresh route, the renewal behaviour of `loginAccount`, and branching on `FailureReason.REAUTH_REQUIRED` to prompt for a fresh interactive login.

- [ ] **Step 4: Update `CHANGELOG.md`**

Add a `## [0.6.0] - 2026-07-28` section above the existing `[Unreleased]` content, moving the AVP-conformance-vector entry into it.

Under `### Added`, every new public surface:

- `AltLoginService.loginRefreshToken` and silent renewal inside `loginAccount`
- `MicrosoftAuthUtil.authenticateWithRefreshToken` and `MicrosoftAuthUtil.RefreshRejectedException`
- `AltLoginCallback.FailureReason`
- `TokenExpiry`
- `AltAccount.withTokens` and `AltAccount.hasRefreshToken`
- `AltStore.updateCredentials` and `AltStore.clearRefreshToken`
- `HttpUtil.HttpResponse` and `HttpUtil.postFormForStatus`
- the two-argument `AccountNetworkUtil.fetchProfileFromToken`
- the three-argument `SharedVault.createRepo` and the `shareRefreshTokens` repository policy

Under `### Fixed`, the expired-JWT false success.

Under `### Changed`, all five breaking items — `AltAccount`, `LoginResult`, `MinecraftProfile`, `AltLoginService`, and `VaultManifest`/`RepoContext` — each with the migration note from its commit.

- [ ] **Step 5: Bump the version**

`build.gradle:8` becomes `version = '0.6.0'`.

- [ ] **Step 6: Verify the whole build**

Run: `./gradlew spotlessApply && ./gradlew build`
Expected: BUILD SUCCESSFUL, every test green.

- [ ] **Step 7: Commit, as two logical changes**

```powershell
git add README.md docs/ARCHITECTURE.md docs/GETTING_STARTED.md CHANGELOG.md
git commit -m "docs: document the refresh-token route and silent renewal"
git add build.gradle
git commit -m "build: release 0.6.0"
```

---

## Follow-up, tracked separately

The Alt Vault Protocol specification in the `trqlmao/avp` repository needs a minor version bump adding two optional payload fields (`refreshToken`, `expiresAt`) and one optional manifest field (`shareRefreshTokens`). All three are optional and additive, so an implementation that ignores them stays conformant. This is separate work in a separate repository and does not block the 0.6.0 release. The vendored conformance vectors under `src/test/resources/avp-vectors/` cover crypto constructions rather than payload field sets, so they are unaffected.
