package lol.trq.alts.auth;

import com.google.gson.annotations.SerializedName;

/**
 * Microsoft / Xbox Live authentication configuration the host supplies. The {@code clientId} is the
 * host's own Azure application id and is <strong>required</strong> — the library ships no default, so an
 * implementer must register their own app rather than sharing one. The scope, the local redirect path,
 * and every service endpoint default to the public Microsoft / Xbox / Minecraft URLs and are only
 * overridden when an implementer fronts them with a proxy or targets a non-standard deployment.
 *
 * <p>Build the common case with {@link #of(String)} and adjust with the {@code withX} copy methods.
 *
 * @param clientId the host's Azure application (OAuth) client id; required
 * @param scope the OAuth scope to request
 * @param redirectPath the local callback path the loopback redirect server listens on
 * @param authorizeUrl the OAuth authorize endpoint
 * @param tokenUrl the OAuth token endpoint
 * @param xboxLiveAuthUrl the Xbox Live user-authentication endpoint
 * @param xstsAuthUrl the XSTS authorization endpoint
 * @param minecraftLoginUrl the Minecraft services login-with-Xbox endpoint
 * @param minecraftProfileUrl the Minecraft services profile endpoint
 * @author trq
 * @since 0.2.0
 */
public record MicrosoftAuthConfig(
        @SerializedName("clientId") String clientId,
        @SerializedName("scope") String scope,
        @SerializedName("redirectPath") String redirectPath,
        @SerializedName("authorizeUrl") String authorizeUrl,
        @SerializedName("tokenUrl") String tokenUrl,
        @SerializedName("xboxLiveAuthUrl") String xboxLiveAuthUrl,
        @SerializedName("xstsAuthUrl") String xstsAuthUrl,
        @SerializedName("minecraftLoginUrl") String minecraftLoginUrl,
        @SerializedName("minecraftProfileUrl") String minecraftProfileUrl) {

    /** Default OAuth scope for a Minecraft login. */
    public static final String DEFAULT_SCOPE = "XboxLive.signin XboxLive.offline_access";

    /** Default loopback callback path — intentionally long to reduce accidental screen-share exposure. */
    public static final String DEFAULT_REDIRECT_PATH =
            "/in_game_account_switcher_long_enough_uri_to_prevent_accidental_leaks_on_screensharing_even_if_you_have_like_extremely_big_screen_though_it_might_not_mork_but_we_will_try_it_anyway_to_prevent_funny_things_from_happening_or_something";

    /** Default OAuth authorize endpoint. */
    public static final String DEFAULT_AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf";

    /** Default OAuth token endpoint. */
    public static final String DEFAULT_TOKEN_URL = "https://login.live.com/oauth20_token.srf";

    /** Default Xbox Live user-authentication endpoint. */
    public static final String DEFAULT_XBOX_LIVE_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";

    /** Default XSTS authorization endpoint. */
    public static final String DEFAULT_XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";

    /** Default Minecraft services login-with-Xbox endpoint. */
    public static final String DEFAULT_MINECRAFT_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";

    /** Default Minecraft services profile endpoint. */
    public static final String DEFAULT_MINECRAFT_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    /** Validates the required client id and substitutes defaults for any blank optional. */
    public MicrosoftAuthConfig {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Microsoft clientId is required (supply your own Azure app id)");
        }
        scope = blankToDefault(scope, DEFAULT_SCOPE);
        redirectPath = blankToDefault(redirectPath, DEFAULT_REDIRECT_PATH);
        authorizeUrl = blankToDefault(authorizeUrl, DEFAULT_AUTHORIZE_URL);
        tokenUrl = blankToDefault(tokenUrl, DEFAULT_TOKEN_URL);
        xboxLiveAuthUrl = blankToDefault(xboxLiveAuthUrl, DEFAULT_XBOX_LIVE_AUTH_URL);
        xstsAuthUrl = blankToDefault(xstsAuthUrl, DEFAULT_XSTS_AUTH_URL);
        minecraftLoginUrl = blankToDefault(minecraftLoginUrl, DEFAULT_MINECRAFT_LOGIN_URL);
        minecraftProfileUrl = blankToDefault(minecraftProfileUrl, DEFAULT_MINECRAFT_PROFILE_URL);
    }

    /**
     * Creates a config for the given client id with every other value defaulted.
     *
     * @param clientId the host's Azure application client id; required
     * @return a fully-defaulted config
     */
    public static MicrosoftAuthConfig of(String clientId) {
        return new MicrosoftAuthConfig(clientId, null, null, null, null, null, null, null, null);
    }

    /**
     * Returns a copy with the OAuth scope replaced.
     *
     * @param value the new scope, or null/blank to keep the default
     * @return a copy with the scope replaced
     */
    public MicrosoftAuthConfig withScope(String value) {
        return new MicrosoftAuthConfig(
                clientId,
                value,
                redirectPath,
                authorizeUrl,
                tokenUrl,
                xboxLiveAuthUrl,
                xstsAuthUrl,
                minecraftLoginUrl,
                minecraftProfileUrl);
    }

    /**
     * Returns a copy with the local redirect path replaced.
     *
     * @param value the new redirect path, or null/blank to keep the default
     * @return a copy with the redirect path replaced
     */
    public MicrosoftAuthConfig withRedirectPath(String value) {
        return new MicrosoftAuthConfig(
                clientId,
                scope,
                value,
                authorizeUrl,
                tokenUrl,
                xboxLiveAuthUrl,
                xstsAuthUrl,
                minecraftLoginUrl,
                minecraftProfileUrl);
    }

    /**
     * Returns a copy with all six service endpoints replaced (each null/blank argument keeps its
     * default). For implementers fronting Microsoft / Xbox / Minecraft services with their own proxy.
     *
     * @param authorizeUrl the OAuth authorize endpoint
     * @param tokenUrl the OAuth token endpoint
     * @param xboxLiveAuthUrl the Xbox Live user-authentication endpoint
     * @param xstsAuthUrl the XSTS authorization endpoint
     * @param minecraftLoginUrl the Minecraft services login-with-Xbox endpoint
     * @param minecraftProfileUrl the Minecraft services profile endpoint
     * @return a copy with the endpoints replaced
     */
    public MicrosoftAuthConfig withEndpoints(
            String authorizeUrl,
            String tokenUrl,
            String xboxLiveAuthUrl,
            String xstsAuthUrl,
            String minecraftLoginUrl,
            String minecraftProfileUrl) {
        return new MicrosoftAuthConfig(
                clientId,
                scope,
                redirectPath,
                authorizeUrl,
                tokenUrl,
                xboxLiveAuthUrl,
                xstsAuthUrl,
                minecraftLoginUrl,
                minecraftProfileUrl);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
