package lol.trq.alts.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.BanInfo;
import lol.trq.alts.spi.VaultDirectoryProvider;

/**
 * Persists and manages the lifecycle of Minecraft accounts on local disk. Data is stored encrypted
 * with a hardware-bound key to prevent unauthorized access or transfer between systems.
 *
 * <p>The directory is supplied by the host through a {@link VaultDirectoryProvider} bound at runtime
 * startup; the store keeps no Minecraft or platform types. This store holds <em>accounts</em> only —
 * accounts may be shared into a multi-member repository, so per-user secrets (API keys, tokens) live
 * separately in {@link SecretStore} and never travel with the accounts.
 *
 * @author trq
 * @since 0.1.0
 */
public final class AltStore {

    /** Gson keyed purely on {@link SerializedName}, the house serialization convention. */
    private static final Gson GSON = new GsonBuilder().create();

    /** Default filename for the encrypted account data; the host may override via {@link #configure}. */
    private static final String DEFAULT_FILE_NAME = "accounts.dat";

    /** Default key-binding constant; the host may override via {@link #configure}. */
    private static final String DEFAULT_KEY_BINDING = "lol.trq.alts";

    private static final List<AltAccount> ACCOUNTS = new ArrayList<>();

    private static String fileName = DEFAULT_FILE_NAME;
    private static String keyBinding = DEFAULT_KEY_BINDING;
    private static VaultDirectoryProvider directoryProvider;
    private static AltAccount currentAccount = null;

    private AltStore() {}

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
     * Overrides the store filename and key-binding constant. Hosts with pre-existing encrypted files
     * pass the legacy values here so those files keep loading; fresh hosts can ignore this and take the
     * neutral defaults. Null or blank arguments leave the current value unchanged.
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
     * Records a locally-observed ban for the account with {@code uuid} on {@code serverId} and persists
     * it. Updates the stored list entry and the current account when they match; a no-op if the UUID is
     * unknown. Other servers' ban entries on the account are preserved.
     *
     * @param uuid the dashed UUID of the banned account
     * @param serverId the server the ban was observed on (a host-supplied id; use a fallback such as
     *     {@code "unknown"} when the server cannot be identified)
     * @param ban the observed ban record
     */
    public static void markBanned(String uuid, String serverId, BanInfo ban) {
        ACCOUNTS.replaceAll(a -> a.uuid().equals(uuid) ? a.withBan(serverId, ban) : a);
        if (currentAccount != null && currentAccount.uuid().equals(uuid)) {
            currentAccount = currentAccount.withBan(serverId, ban);
        }
        save();
    }

    /**
     * Returns the list of all accounts currently held in memory.
     *
     * @return the live list of accounts
     */
    public static List<AltAccount> accounts() {
        return ACCOUNTS;
    }

    /**
     * Returns the currently active account, if any.
     *
     * @return an optional holding the active account, or empty if none is set
     */
    public static Optional<AltAccount> currentAccount() {
        return Optional.ofNullable(currentAccount);
    }

    /**
     * Marks {@code account} as the active account, stamping it as used now. If a matching-UUID entry
     * exists in the stored list, it is replaced with the stamped copy so its last-used time stays
     * fresh.
     *
     * @param account the account to make current, or null to clear
     */
    public static void useAccount(AltAccount account) {
        if (account == null) {
            currentAccount = null;
            return;
        }
        AltAccount stamped = account.usedNow();
        ACCOUNTS.replaceAll(a -> a.uuid().equals(stamped.uuid()) ? stamped : a);
        currentAccount = stamped;
    }

    /**
     * Adds an account to storage, replacing any existing account with the same UUID, then saves.
     *
     * @param account the account to add or update
     */
    public static void addAccount(AltAccount account) {
        ACCOUNTS.removeIf(a -> a.uuid().equals(account.uuid()));
        ACCOUNTS.add(account);
        save();
    }

    /**
     * Removes an account from storage and saves.
     *
     * @param account the account to remove
     */
    public static void removeAccount(AltAccount account) {
        ACCOUNTS.remove(account);
        if (currentAccount == account) {
            currentAccount = null;
        }
        save();
    }

    /**
     * Encrypts and persists the current accounts to disk under the hardware-bound key from
     * {@link EncryptionUtil#getHardwareKey(String)}.
     */
    public static void save() {
        try {
            File directory = directory();
            if (!directory.exists()) {
                directory.mkdirs();
            }

            StorageData data = new StorageData(new ArrayList<>(ACCOUNTS));
            String json = GSON.toJson(data);
            String encrypted = EncryptionUtil.encrypt(json, EncryptionUtil.getHardwareKey(keyBinding));
            Files.writeString(new File(directory, fileName).toPath(), encrypted);
        } catch (Exception ignored) {
            // Exceptions are ignored for production stability
        }
    }

    /** Loads and decrypts accounts from disk into memory, clearing existing entries first. */
    public static void load() {
        try {
            File file = new File(directory(), fileName);
            if (!file.exists()) {
                return;
            }

            String encrypted = Files.readString(file.toPath());
            String json = EncryptionUtil.decrypt(encrypted, EncryptionUtil.getHardwareKey(keyBinding));

            JsonElement root = normalizeLegacyBans(JsonParser.parseString(json));
            StorageData loaded = GSON.fromJson(root, StorageData.class);

            if (loaded != null) {
                ACCOUNTS.clear();
                if (loaded.accounts() != null) {
                    ACCOUNTS.addAll(loaded.accounts());
                }
            }
        } catch (Exception ignored) {
            // Exceptions are ignored for production stability
        }
    }

    private static File directory() {
        if (directoryProvider == null) {
            throw new IllegalStateException(
                    "AltStore not bound — call AltsRuntime.Builder.build() during host initialization");
        }
        return directoryProvider.vaultDirectory().toFile();
    }

    /**
     * Migrates legacy stored accounts in place: an account carrying a single {@code "ban"} object (and
     * no {@code "bans"} map) is rewritten to {@code "bans": { "unknown": <ban> }}, since bans are now
     * per-server. Returns the same element. Package-private for unit testing.
     *
     * @param root the parsed storage root
     * @return the (possibly mutated) root
     */
    static JsonElement normalizeLegacyBans(JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            return root;
        }
        JsonElement accountsEl = root.getAsJsonObject().get("accounts");
        if (accountsEl == null || !accountsEl.isJsonArray()) {
            return root;
        }
        for (JsonElement element : accountsEl.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject account = element.getAsJsonObject();
            if (account.has("ban") && !account.get("ban").isJsonNull() && !account.has("bans")) {
                JsonObject bans = new JsonObject();
                bans.add("unknown", account.get("ban"));
                account.add("bans", bans);
                account.remove("ban");
            }
        }
        return root;
    }

    /**
     * Internal serialization wrapper for the stored accounts.
     *
     * @param accounts the stored accounts
     */
    private record StorageData(@SerializedName("accounts") List<AltAccount> accounts) {}
}
