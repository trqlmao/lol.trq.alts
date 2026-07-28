package lol.trq.alts.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import lol.trq.alts.net.HttpUtil;
import lol.trq.alts.net.MicrosoftCallbackServer;

/**
 * Orchestrates the multi-step OAuth 2.0 and Xbox Live authentication flow, exchanging an
 * authorization code for a Minecraft-compatible session token. The client id, scope, and every service
 * endpoint come from a host-supplied {@link MicrosoftAuthConfig} — nothing is hardcoded.
 *
 * @author trq
 * @since 0.1.0
 */
public final class MicrosoftAuthUtil {

    private static final String STATE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-_";

    private MicrosoftAuthUtil() {}

    /**
     * Initiates the full asynchronous authentication flow. Starts a local callback server, opens the
     * browser, and chains the authentication steps.
     *
     * @param config the host's Microsoft authentication configuration (client id, scope, endpoints)
     * @return a future containing the final {@link MinecraftProfile} on success
     */
    public static CompletableFuture<MinecraftProfile> authenticate(MicrosoftAuthConfig config) {
        Objects.requireNonNull(config, "config");
        String state = generateState();
        MicrosoftCallbackServer server = new MicrosoftCallbackServer(state, config);

        return server.start()
                .thenCompose(code -> exchangeCodeForToken(config, code, server.redirectUri()))
                .thenCompose(tokens -> completeFrom(config, tokens))
                .whenComplete((profile, error) -> server.stop());
    }

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

    /**
     * Attempts to open the system's default web browser to the specified URL. Falls back to
     * OS-specific terminal commands if the Java Desktop API is unavailable.
     *
     * @param authUrl the Microsoft OAuth URL to open
     */
    public static void openBrowser(String authUrl) {
        boolean opened = false;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(authUrl));
                opened = true;
            }
        } catch (Exception ignored) {
        }

        if (!opened) {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                Runtime rt = Runtime.getRuntime();
                if (os.contains("win")) rt.exec("rundll32 url.dll,FileProtocolHandler " + authUrl);
                else if (os.contains("mac")) rt.exec("open " + authUrl);
                else if (os.contains("nix") || os.contains("nux")) rt.exec("xdg-open " + authUrl);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Generates a cryptographically secure random state string to prevent CSRF.
     *
     * @return a random alphanumeric string
     */
    private static String generateState() {
        try {
            SecureRandom random = SecureRandom.getInstanceStrong();
            int length = random.nextInt(96, 128);
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(STATE_CHARS.charAt(random.nextInt(STATE_CHARS.length())));
            }
            return sb.toString();
        } catch (Exception e) {
            return "fallback-state-" + System.currentTimeMillis();
        }
    }

    /**
     * Step 1: exchanges the OAuth2 authorization code for Microsoft access and refresh tokens.
     *
     * @param config the host's Microsoft authentication configuration
     * @param code the authorization code received from the callback server
     * @param redirectUri the URI where the code was received
     * @return a future containing the raw Microsoft tokens
     */
    private static CompletableFuture<MsTokens> exchangeCodeForToken(
            MicrosoftAuthConfig config, String code, String redirectUri) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = String.format(
                        "client_id=%s&code=%s&grant_type=authorization_code&redirect_uri=%s&scope=%s",
                        config.clientId(),
                        URLEncoder.encode(code, StandardCharsets.UTF_8),
                        URLEncoder.encode(redirectUri, StandardCharsets.UTF_8),
                        URLEncoder.encode(config.scope(), StandardCharsets.UTF_8));
                JsonObject response = HttpUtil.postForm(config.tokenUrl(), null, body);
                if (response == null) throw new Exception("Token exchange failed");
                long expiresIn =
                        response.has("expires_in") ? response.get("expires_in").getAsLong() : 0L;
                return new MsTokens(
                        response.get("access_token").getAsString(),
                        response.get("refresh_token").getAsString(),
                        expiresIn);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Redeems a stored refresh token at the OAuth token endpoint, classifying a rejection as permanent
     * (4xx) or transient (5xx, transport failure) so an outage never costs a working credential.
     *
     * @param config the host's Microsoft authentication configuration
     * @param refreshToken the stored refresh token to redeem
     * @return a future containing the freshly issued Microsoft tokens
     */
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
            String rotated =
                    json.has("refresh_token") ? json.get("refresh_token").getAsString() : refreshToken;
            long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 0L;
            return new MsTokens(json.get("access_token").getAsString(), rotated, expiresIn);
        });
    }

    /**
     * Step 2: authenticates with the Xbox Live user authentication service.
     *
     * @param config the host's Microsoft authentication configuration
     * @param tokens the Microsoft tokens from the previous step
     * @return a future containing the Xbox Live token
     */
    private static CompletableFuture<String> authenticateWithXboxLive(MicrosoftAuthConfig config, MsTokens tokens) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                JsonObject properties = new JsonObject();
                properties.addProperty("AuthMethod", "RPS");
                properties.addProperty("SiteName", "user.auth.xboxlive.com");
                properties.addProperty("RpsTicket", "d=" + tokens.accessToken());
                body.add("Properties", properties);
                body.addProperty("RelyingParty", "http://auth.xboxlive.com");
                body.addProperty("TokenType", "JWT");

                JsonObject response = HttpUtil.postJson(config.xboxLiveAuthUrl(), null, body.toString());
                if (response == null) throw new Exception("Xbox Live auth failed");
                return response.get("Token").getAsString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Step 3: authorizes with the XSTS (Xbox Security Token Service).
     *
     * @param config the host's Microsoft authentication configuration
     * @param xblToken the Xbox Live token from the previous step
     * @return a future containing the XSTS token and user hash
     */
    private static CompletableFuture<XstsData> authenticateWithXSTS(MicrosoftAuthConfig config, String xblToken) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                JsonObject properties = new JsonObject();
                JsonArray userTokens = new JsonArray();
                userTokens.add(xblToken);
                properties.add("UserTokens", userTokens);
                properties.addProperty("SandboxId", "RETAIL");
                body.add("Properties", properties);
                body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
                body.addProperty("TokenType", "JWT");

                JsonObject response = HttpUtil.postJson(config.xstsAuthUrl(), null, body.toString());
                if (response == null) throw new Exception("XSTS auth failed");

                String token = response.get("Token").getAsString();
                String uhs = response.getAsJsonObject("DisplayClaims")
                        .getAsJsonArray("xui")
                        .get(0)
                        .getAsJsonObject()
                        .get("uhs")
                        .getAsString();
                return new XstsData(token, uhs);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Step 4: finalizes authentication by obtaining a Minecraft services access token.
     *
     * @param config the host's Microsoft authentication configuration
     * @param xstsData the XSTS data from the previous step
     * @return a future containing the Minecraft services session and its lifetime
     */
    private static CompletableFuture<McSession> authenticateWithMinecraft(
            MicrosoftAuthConfig config, XstsData xstsData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("identityToken", "XBL3.0 x=" + xstsData.uhs() + ";" + xstsData.token());
                JsonObject response = HttpUtil.postJson(config.minecraftLoginUrl(), null, body.toString());
                if (response == null) throw new Exception("MC services auth failed");
                long expiresIn =
                        response.has("expires_in") ? response.get("expires_in").getAsLong() : 0L;
                return new McSession(response.get("access_token").getAsString(), expiresIn);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Step 5: retrieves the Minecraft profile information (username and UUID).
     *
     * @param config the host's Microsoft authentication configuration
     * @param session the final Minecraft services session
     * @param tokens the Microsoft tokens the flow started from
     * @return a future containing the populated profile
     */
    private static CompletableFuture<MinecraftProfile> getMinecraftProfile(
            MicrosoftAuthConfig config, McSession session, MsTokens tokens) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject profile = HttpUtil.get(
                        config.minecraftProfileUrl(), Map.of("Authorization", "Bearer " + session.accessToken()));

                if (profile == null) throw new Exception("Profile fetch failed");

                String uuid = profile.get("id").getAsString();
                String username = profile.get("name").getAsString();

                if (!uuid.contains("-")) {
                    uuid = uuid.replaceFirst(
                            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                            "$1-$2-$3-$4-$5");
                }
                return new MinecraftProfile(
                        username,
                        uuid,
                        session.accessToken(),
                        tokens.refreshToken(),
                        absoluteExpiry(session.expiresIn()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Converts an advertised lifetime in seconds into an absolute epoch-millis expiry.
     *
     * @param expiresInSeconds the lifetime in seconds, or a non-positive value when unknown
     * @return the absolute expiry, or {@code 0} when the lifetime was unknown
     */
    private static long absoluteExpiry(long expiresInSeconds) {
        return expiresInSeconds <= 0 ? 0L : System.currentTimeMillis() + (expiresInSeconds * 1000L);
    }

    /**
     * Signals that a refresh-token redemption failed, distinguishing a token that will never work
     * again from a failure worth retrying.
     *
     * @author trq
     * @since 0.6.0
     */
    public static final class RefreshRejectedException extends RuntimeException {

        /**
         * Whether the refresh token is permanently spent.
         *
         * @serial
         */
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

    /** Holds the Microsoft OAuth access and refresh tokens together with the access token's lifetime. */
    private record MsTokens(String accessToken, String refreshToken, long expiresIn) {}

    /** Holds the Minecraft services session token and its advertised lifetime in seconds. */
    private record McSession(String accessToken, long expiresIn) {}

    /** Holds XSTS authorization data. */
    private record XstsData(String token, String uhs) {}
}
