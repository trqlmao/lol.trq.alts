package lol.trq.alts.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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

    /** Arbitrary host key/value secrets, persisted as a single encrypted file. */
    private static final Map<String, String> SECRETS = new LinkedHashMap<>();

    private static String fileName = DEFAULT_FILE_NAME;
    private static String keyBinding = DEFAULT_KEY_BINDING;
    private static VaultDirectoryProvider directoryProvider;

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

    /** Encrypts and persists the current secrets to disk under the hardware-bound key. */
    public static void save() {
        try {
            File directory = directory();
            if (!directory.exists()) {
                directory.mkdirs();
            }

            SecretData data = new SecretData(new LinkedHashMap<>(SECRETS));
            String json = GSON.toJson(data);
            String encrypted = EncryptionUtil.encrypt(json, EncryptionUtil.getHardwareKey(keyBinding));
            Files.writeString(new File(directory, fileName).toPath(), encrypted);
        } catch (Exception ignored) {
            // Exceptions are ignored for production stability
        }
    }

    /** Loads and decrypts secrets from disk into memory, clearing existing entries first. */
    public static void load() {
        try {
            File file = new File(directory(), fileName);
            if (!file.exists()) {
                return;
            }

            String encrypted = Files.readString(file.toPath());
            String json = EncryptionUtil.decrypt(encrypted, EncryptionUtil.getHardwareKey(keyBinding));

            SecretData loaded = GSON.fromJson(json, SecretData.class);

            if (loaded != null) {
                SECRETS.clear();
                if (loaded.secrets() != null) {
                    SECRETS.putAll(loaded.secrets());
                }
            }
        } catch (Exception ignored) {
            // Exceptions are ignored for production stability
        }
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
