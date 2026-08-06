package lol.trq.alts.account;

/**
 * Reads what an account owns.
 *
 * @author trq
 * @since 1.0.0
 */
public interface EntitlementService {

    /**
     * Fetches the account's entitlements.
     *
     * @return the products the account holds
     * @throws AccountException if the token was refused or the service failed
     * @since 1.0.0
     */
    Entitlements fetch() throws AccountException;
}
