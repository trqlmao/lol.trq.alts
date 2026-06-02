package lol.trq.alts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lol.trq.alts.auth.AltLoginService;
import lol.trq.alts.auth.AltLoginServiceImpl;
import lol.trq.alts.auth.MicrosoftAuthConfig;
import lol.trq.alts.cache.AsyncCache;
import lol.trq.alts.model.GameStats;
import lol.trq.alts.skin.SkinAvatarCache;
import lol.trq.alts.spi.GameStatsSource;
import lol.trq.alts.spi.MainThreadExecutor;
import lol.trq.alts.spi.SessionInjector;
import lol.trq.alts.spi.TextureUploader;
import lol.trq.alts.spi.ToastSink;
import lol.trq.alts.spi.VaultDirectoryProvider;
import lol.trq.alts.store.AltStore;
import lol.trq.alts.store.SecretStore;

/**
 * Central wiring object for the consumer-agnostic alt-manager library. The host supplies its platform
 * seams through {@link Builder}; the library exposes an {@link AltLoginService} and a generic
 * {@link SkinAvatarCache}, and binds the static {@link AltStore} and {@link AltsToasts} helpers to the
 * host's seams.
 *
 * <p>Construct exactly one per application. Build it during host initialization, before any alt screen
 * opens or {@link AltStore} is touched.
 *
 * @param <H> the host's opaque texture-handle type (for example a NanoVG image handle)
 * @author trq
 * @since 0.1.0
 */
public final class AltsRuntime<H> {

    /** How long a fetched game-stats snapshot stays fresh before a background refresh. */
    private static final long STATS_TTL_MILLIS = 5 * 60 * 1000L;

    private final AltLoginService loginService;
    private final SkinAvatarCache<H> skinCache;
    private final Map<String, AsyncCache<String, GameStats>> gameStatsCaches;
    private final AsyncCache<String, GameStats> emptyStatsCache;
    private final ToastSink toasts;

    private AltsRuntime(Builder<H> builder) {
        Objects.requireNonNull(builder.sessionInjector, "SessionInjector required");
        Objects.requireNonNull(builder.vaultDirectory, "VaultDirectoryProvider required");
        Objects.requireNonNull(builder.mainThread, "MainThreadExecutor required");
        Objects.requireNonNull(builder.toastSink, "ToastSink required");

        AltStore.bind(builder.vaultDirectory);
        AltStore.configure(builder.storeFileName, builder.storeKeyBinding);
        SecretStore.bind(builder.vaultDirectory);
        SecretStore.configure(builder.secretsFileName, builder.secretsKeyBinding);
        AltsToasts.bind(builder.toastSink);

        this.toasts = builder.toastSink;
        this.loginService = new AltLoginServiceImpl(builder.sessionInjector, builder.microsoftAuth);
        this.skinCache = new SkinAvatarCache<>(builder.textureUploader, builder.mainThread);

        // Game stats are optional and per-server: one cache per registered source. A request for a
        // server with no source returns the shared empty cache, whose loader always yields null, so
        // callers degrade gracefully.
        Map<String, AsyncCache<String, GameStats>> caches = new LinkedHashMap<>();
        for (Map.Entry<String, GameStatsSource> entry : builder.gameStatsSources.entrySet()) {
            GameStatsSource source = entry.getValue();
            caches.put(
                    entry.getKey(),
                    new AsyncCache<>(
                            uuid -> {
                                try {
                                    return source.fetch(uuid);
                                } catch (Exception e) {
                                    return null;
                                }
                            },
                            STATS_TTL_MILLIS));
        }
        this.gameStatsCaches = Map.copyOf(caches);
        this.emptyStatsCache = new AsyncCache<>(uuid -> null, STATS_TTL_MILLIS);
    }

    /**
     * Returns the login service callers use to authenticate accounts.
     *
     * @return the login service
     */
    public AltLoginService loginService() {
        return loginService;
    }

    /**
     * Returns the avatar cache, generic over this host's texture-handle type.
     *
     * @return the skin avatar cache
     */
    public SkinAvatarCache<H> skinCache() {
        return skinCache;
    }

    /**
     * Returns the lazy, cached game-stats lookup for one server, keyed by player UUID. If no source was
     * registered for {@code serverId}, returns an empty cache that always yields {@code null}; a failed
     * fetch likewise surfaces as {@code null} from {@link AsyncCache#get(Object)}, so callers degrade
     * cleanly.
     *
     * @param serverId the server whose stats to look up (matches a registered source's id)
     * @return the per-server game-stats cache
     */
    public AsyncCache<String, GameStats> gameStats(String serverId) {
        AsyncCache<String, GameStats> cache = gameStatsCaches.get(serverId);
        return cache != null ? cache : emptyStatsCache;
    }

    /**
     * Returns the toast sink, for host UI to raise consistent notifications directly.
     *
     * @return the toast sink
     */
    public ToastSink toasts() {
        return toasts;
    }

    /**
     * Accumulating builder that injects the host seams. Required seams are validated in {@link #build()},
     * never in the setters; setters use bare names (no {@code withX} prefixes).
     *
     * @param <H> the host's opaque texture-handle type
     * @author trq
     * @since 0.1.0
     */
    public static final class Builder<H> {

        private SessionInjector sessionInjector;
        private VaultDirectoryProvider vaultDirectory;
        private TextureUploader<H> textureUploader;
        private MainThreadExecutor mainThread;
        private ToastSink toastSink;
        private final Map<String, GameStatsSource> gameStatsSources = new LinkedHashMap<>();
        private MicrosoftAuthConfig microsoftAuth;
        private String storeFileName;
        private String storeKeyBinding;
        private String secretsFileName;
        private String secretsKeyBinding;

        /** Creates an empty builder; populate it with the host seams, then call {@link #build()}. */
        public Builder() {}

        /**
         * Sets the session injector (required).
         *
         * @param value the host session injector
         * @return this builder
         */
        public Builder<H> sessionInjector(SessionInjector value) {
            this.sessionInjector = value;
            return this;
        }

        /**
         * Sets the vault directory provider (required).
         *
         * @param value the host vault directory provider
         * @return this builder
         */
        public Builder<H> vaultDirectory(VaultDirectoryProvider value) {
            this.vaultDirectory = value;
            return this;
        }

        /**
         * Sets the texture uploader (optional; avatars degrade to the initial-letter fallback if absent).
         *
         * @param value the host texture uploader
         * @return this builder
         */
        public Builder<H> textureUploader(TextureUploader<H> value) {
            this.textureUploader = value;
            return this;
        }

        /**
         * Sets the main-thread executor (required).
         *
         * @param value the host main-thread executor
         * @return this builder
         */
        public Builder<H> mainThread(MainThreadExecutor value) {
            this.mainThread = value;
            return this;
        }

        /**
         * Sets the toast sink (required).
         *
         * @param value the host toast sink
         * @return this builder
         */
        public Builder<H> toastSink(ToastSink value) {
            this.toastSink = value;
            return this;
        }

        /**
         * Registers a game-stats source for one server (optional; callable once per server). Without any
         * source, {@link AltsRuntime#gameStats(String)} always returns null. The source's
         * {@link GameStatsSource#serverId()} is the key consumers pass to {@code gameStats(serverId)}.
         *
         * @param value the host game-stats source
         * @return this builder
         * @throws IllegalStateException if a source for the same server id was already registered
         */
        public Builder<H> gameStatsSource(GameStatsSource value) {
            Objects.requireNonNull(value, "gameStatsSource");
            String serverId = Objects.requireNonNull(value.serverId(), "serverId");
            if (gameStatsSources.putIfAbsent(serverId, value) != null) {
                throw new IllegalStateException("duplicate game-stats serverId: " + serverId);
            }
            return this;
        }

        /**
         * Sets the Microsoft authentication configuration (optional; without it Microsoft login fails
         * cleanly while offline / cookie / session login stay available). The {@code clientId} inside is
         * the host's own Azure app id — the library ships no default.
         *
         * @param value the host Microsoft authentication configuration
         * @return this builder
         */
        public Builder<H> microsoftAuth(MicrosoftAuthConfig value) {
            this.microsoftAuth = value;
            return this;
        }

        /**
         * Overrides the on-disk store filename (optional; defaults to a neutral name). Hosts migrating
         * from an earlier filename pass it here so existing files keep loading.
         *
         * @param value the store filename, or null/blank to keep the default
         * @return this builder
         */
        public Builder<H> storeFileName(String value) {
            this.storeFileName = value;
            return this;
        }

        /**
         * Overrides the key-binding constant mixed into the storage encryption key (optional; defaults
         * to a neutral constant). Hosts migrating from an earlier constant pass it here so existing
         * files keep decrypting. Changing it for an existing store renders prior files undecryptable.
         *
         * @param value the key-binding constant, or null/blank to keep the default
         * @return this builder
         */
        public Builder<H> storeKeyBinding(String value) {
            this.storeKeyBinding = value;
            return this;
        }

        /**
         * Overrides the on-disk secret-store filename (optional; defaults to a neutral name). The secret
         * store holds per-user secrets separately from the shareable account store.
         *
         * @param value the secret-store filename, or null/blank to keep the default
         * @return this builder
         */
        public Builder<H> secretsFileName(String value) {
            this.secretsFileName = value;
            return this;
        }

        /**
         * Overrides the key-binding constant mixed into the secret-store encryption key (optional;
         * defaults to a neutral constant). Changing it for an existing store renders prior secret files
         * undecryptable.
         *
         * @param value the secret-store key-binding constant, or null/blank to keep the default
         * @return this builder
         */
        public Builder<H> secretsKeyBinding(String value) {
            this.secretsKeyBinding = value;
            return this;
        }

        /**
         * Validates the required seams and constructs the runtime, binding the static helpers.
         *
         * @return the constructed runtime
         * @throws NullPointerException if a required seam is missing
         */
        public AltsRuntime<H> build() {
            return new AltsRuntime<>(this);
        }
    }
}
