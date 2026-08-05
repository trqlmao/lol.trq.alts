package lol.trq.alts.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lol.trq.alts.spi.VaultDirectoryProvider;

/**
 * Per-user encrypted key/value secret storage on local disk, separate from the account store. Holds
 * host secrets (API keys, referral codes, integration tokens) that belong to the individual user and
 * must never travel into a shared account repository.
 *
 * <p>Secrets are encrypted with a hardware-bound key from {@link EncryptionUtil#getHardwareKey(String)}
 * so the file cannot be transferred between systems. The directory is supplied by the host through a
 * {@link VaultDirectoryProvider} bound at runtime startup; the store keeps no Minecraft or platform
 * types. Hosts namespace their own keys (for example {@code "myfeature.api-key"}); the library imposes
 * no schema.
 *
 * <p>This store is deliberately split from the account store: accounts may be shared into a
 * multi-member repository, but secrets stay local to one user.
 *
 * @author trq
 * @since 0.2.0
 */
public final class SecretStore {

    /** Gson keyed purely on {@link SerializedName}, the house serialization convention. */
    private static final Gson GSON = new GsonBuilder().create();

    /** Default filename for the encrypted secret data; the host may override via {@link #configure}. */
    private static final String DEFAULT_FILE_NAME = "secrets.dat";

    /** Default key-binding constant; the host may override via {@link #configure}. */
    private static final String DEFAULT_KEY_BINDING = "lol.trq.alts.secrets";

    /** Suffix given to a secret file that could not be read, so a failed load never costs the data. */
    private static final String UNREADABLE_SUFFIX = ".unreadable";

    // Concurrent because a host reads secrets from its render thread while background work writes them.
    /** Arbitrary host key/value secrets, persisted as a single encrypted file. */
    private static final Map<String, String> SECRETS = new ConcurrentHashMap<>();

    private static volatile String fileName = DEFAULT_FILE_NAME;
    private static volatile String keyBinding = DEFAULT_KEY_BINDING;
    private static volatile VaultDirectoryProvider directoryProvider;
    private static volatile String loadError;

    private SecretStore() {}

    /**
     * Binds the host directory provider. Called once by {@code AltsRuntime.Builder#build()} before any
     * load or save.
     *
     * @param provider the host-supplied vault directory provider
     */
    public static void bind(VaultDirectoryProvider provider) {
        directoryProvider = Objects.requireNonNull(provider, "VaultDirectoryProvider");
    }

    /**
     * Overrides the store filename and key-binding constant. Null or blank arguments leave the current
     * value unchanged.
     *
     * @param storeFileName the on-disk filename, or null/blank to keep the default
     * @param storeKeyBinding the key-binding constant, or null/blank to keep the default
     */
    public static void configure(String storeFileName, String storeKeyBinding) {
        if (storeFileName != null && !storeFileName.isBlank()) {
            fileName = storeFileName;
        }
        if (storeKeyBinding != null && !storeKeyBinding.isBlank()) {
            keyBinding = storeKeyBinding;
        }
    }

    /**
     * Returns the stored secret for {@code key}, or {@code null} if absent.
     *
     * @param key the secret key
     * @return the stored value, or null if absent
     */
    public static String get(String key) {
        return SECRETS.get(key);
    }

    /**
     * Stores a secret and persists. A null or blank value removes the key instead.
     *
     * @param key the secret key
     * @param value the value to store, or null/blank to remove the key
     */
    public static void put(String key, String value) {
        if (value == null || value.isBlank()) {
            SECRETS.remove(key);
        } else {
            SECRETS.put(key, value);
        }
        save();
    }

    /**
     * Returns whether a non-blank secret is stored for {@code key}.
     *
     * @param key the secret key
     * @return true if a non-blank value is stored
     */
    public static boolean has(String key) {
        String value = SECRETS.get(key);
        return value != null && !value.isBlank();
    }

    /**
     * Removes a secret and persists.
     *
     * @param key the secret key to clear
     */
    public static void clear(String key) {
        if (SECRETS.remove(key) != null) {
            save();
        }
    }

    /**
     * Returns why the last {@link #load()} could not read an existing secret file, if it could not. As
     * with the account store, an unreadable file is not an empty one: while this is set, {@link #save()}
     * preserves the file rather than overwriting it.
     *
     * @return the failure description, or empty when the last load succeeded or found no file
     * @since 0.7.0
     */
    public static Optional<String> loadError() {
        return Optional.ofNullable(loadError);
    }

    /**
     * Encrypts and persists the current secrets to disk under the hardware-bound key. Does nothing when
     * an unreadable file is present and could not be copied aside — see {@link #loadError()}.
     */
    public static void save() {
        try {
            File directory = directory();
            if (!directory.exists()) {
                directory.mkdirs();
            }
            if (!preserveUnreadable(directory)) {
                return;
            }

            SecretData data = new SecretData(new LinkedHashMap<>(SECRETS));
            String json = GSON.toJson(data);
            String encrypted = EncryptionUtil.encrypt(json, EncryptionUtil.getHardwareKey(keyBinding));
            Files.writeString(new File(directory, fileName).toPath(), encrypted);
        } catch (Exception ignored) {
            // Exceptions are ignored for production stability
        }
    }

    /**
     * Loads and decrypts secrets from disk into memory, clearing existing entries first. A file that
     * exists but cannot be read leaves the in-memory map alone and records the reason on
     * {@link #loadError()}.
     */
    public static void load() {
        File file;
        try {
            file = new File(directory(), fileName);
            if (!file.exists()) {
                loadError = null;
                return;
            }
        } catch (Exception unavailable) {
            loadError = describe(unavailable);
            return;
        }

        try {
            String encrypted = Files.readString(file.toPath());
            String json = EncryptionUtil.decrypt(encrypted, EncryptionUtil.getHardwareKey(keyBinding));

            SecretData loaded = GSON.fromJson(json, SecretData.class);

            if (loaded != null) {
                SECRETS.clear();
                if (loaded.secrets() != null) {
                    SECRETS.putAll(loaded.secrets());
                }
            }
            loadError = null;
        } catch (Exception unreadable) {
            loadError = describe(unreadable);
        }
    }

    /**
     * Copies an unreadable secret file aside so the next {@link #save()} cannot destroy it, once.
     *
     * @param directory the store directory
     * @return true when saving may proceed, false when the unreadable file could not be preserved
     */
    private static boolean preserveUnreadable(File directory) {
        if (loadError == null) {
            return true;
        }
        Path source = new File(directory, fileName).toPath();
        if (!Files.exists(source)) {
            return true;
        }
        Path backup = new File(directory, fileName + UNREADABLE_SUFFIX).toPath();
        if (Files.exists(backup)) {
            return true;
        }
        try {
            Files.copy(source, backup);
            return true;
        } catch (IOException unwritable) {
            return false;
        }
    }

    /**
     * Renders a failure as a short description, falling back to the type when it carries no message.
     *
     * @param failure the failure to describe
     * @return a one-line description
     */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message != null && !message.isBlank()
                ? failure.getClass().getSimpleName() + ": " + message
                : failure.getClass().getSimpleName();
    }

    private static File directory() {
        if (directoryProvider == null) {
            throw new IllegalStateException(
                    "SecretStore not bound — call AltsRuntime.Builder.build() during host initialization");
        }
        return directoryProvider.vaultDirectory().toFile();
    }

    /**
     * Internal serialization wrapper for the encrypted secret map.
     *
     * @param secrets the host key/value secrets, or null
     */
    private record SecretData(@SerializedName("secrets") Map<String, String> secrets) {}
}
