package lol.trq.alts.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * The whole post-login account surface, built over one live Minecraft access token. Hands out the
 * focused services — profile, entitlements, name, skin, cape — each of which a host can hold on its own.
 *
 * <p>Construct one per account from its access token. Nothing here installs a session or touches the
 * account store; these operate directly on the account the token belongs to.
 *
 * @author trq
 * @since 1.0.0
 */
public final class AccountServices {

    private static final Gson GSON = new GsonBuilder().create();

    private final ProfileService profile;
    private final EntitlementService entitlements;
    private final NameService name;
    private final SkinService skin;
    private final CapeService cape;

    private AccountServices(String token, String accountUuid, AccountEndpoints endpoints) {
        AccountHttp http = new AccountHttp(token, accountUuid);
        this.profile = new ProfileServiceImpl(http, endpoints, GSON);
        this.entitlements = new EntitlementServiceImpl(http, endpoints);
        this.name = new NameServiceImpl(http, endpoints, GSON);
        this.skin = new SkinServiceImpl(http, endpoints, GSON);
        this.cape = new CapeServiceImpl(http, endpoints, GSON, this.profile);
    }

    /**
     * Builds the services for an account, against the public Minecraft services.
     *
     * @param token the account's Minecraft access token
     * @param accountUuid the account UUID, for per-account request routing; may be null
     * @return the services
     * @since 1.0.0
     */
    public static AccountServices of(String token, String accountUuid) {
        return of(token, accountUuid, AccountEndpoints.defaults());
    }

    /**
     * Builds the services against a given endpoint base — for a host fronting Minecraft services with
     * its own proxy, or a test pointing at a loopback.
     *
     * @param token the account's Minecraft access token
     * @param accountUuid the account UUID, for per-account request routing; may be null
     * @param endpoints the Minecraft services base
     * @return the services
     * @since 1.0.0
     */
    public static AccountServices of(String token, String accountUuid, AccountEndpoints endpoints) {
        return new AccountServices(token, accountUuid, endpoints);
    }

    /**
     * Returns the full-profile read service.
     *
     * @return the profile service
     * @since 1.0.0
     */
    public ProfileService profile() {
        return profile;
    }

    /**
     * Returns the entitlement (ownership) service.
     *
     * @return the entitlement service
     * @since 1.0.0
     */
    public EntitlementService entitlements() {
        return entitlements;
    }

    /**
     * Returns the name service — availability, eligibility, change, and scheduled claim.
     *
     * @return the name service
     * @since 1.0.0
     */
    public NameService name() {
        return name;
    }

    /**
     * Returns the skin service.
     *
     * @return the skin service
     * @since 1.0.0
     */
    public SkinService skin() {
        return skin;
    }

    /**
     * Returns the cape service.
     *
     * @return the cape service
     * @since 1.0.0
     */
    public CapeService cape() {
        return cape;
    }
}
