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
                .thenCompose(tokens -> authenticateWithXboxLive(config, tokens))
                .thenCompose(xblToken -> authenticateWithXSTS(config, xblToken))
                .thenCompose(xstsData -> authenticateWithMinecraft(config, xstsData))
                .thenCompose(mcToken -> getMinecraftProfile(config, mcToken))
                .whenComplete((profile, error) -> server.stop());
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
                return new MsTokens(
                        response.get("access_token").getAsString(),
                        response.get("refresh_token").getAsString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
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
     * @return a future containing the Minecraft access token
     */
    private static CompletableFuture<String> authenticateWithMinecraft(MicrosoftAuthConfig config, XstsData xstsData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("identityToken", "XBL3.0 x=" + xstsData.uhs() + ";" + xstsData.token());
                JsonObject response = HttpUtil.postJson(config.minecraftLoginUrl(), null, body.toString());
                if (response == null) throw new Exception("MC services auth failed");
                return response.get("access_token").getAsString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Step 5: retrieves the Minecraft profile information (username and UUID).
     *
     * @param config the host's Microsoft authentication configuration
     * @param mcAccessToken the final Minecraft services token
     * @return a future containing the populated profile
     */
    private static CompletableFuture<MinecraftProfile> getMinecraftProfile(
            MicrosoftAuthConfig config, String mcAccessToken) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject profile =
                        HttpUtil.get(config.minecraftProfileUrl(), Map.of("Authorization", "Bearer " + mcAccessToken));

                if (profile == null) throw new Exception("Profile fetch failed");

                String uuid = profile.get("id").getAsString();
                String username = profile.get("name").getAsString();

                if (!uuid.contains("-")) {
                    uuid = uuid.replaceFirst(
                            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                            "$1-$2-$3-$4-$5");
                }
                return new MinecraftProfile(username, uuid, mcAccessToken);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Holds the Microsoft OAuth access and refresh tokens. */
    private record MsTokens(String accessToken, String refreshToken) {}

    /** Holds XSTS authorization data. */
    private record XstsData(String token, String uhs) {}
}
