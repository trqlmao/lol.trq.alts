package lol.trq.alts.bulk;

import java.util.List;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.model.LoginMode;

/**
 * Running one operation over many accounts, paced so the service does not treat it as an attack.
 *
 * <p>Neither operation installs a session. A host writing its own loop over
 * {@link lol.trq.alts.auth.AltLoginService#loginAccount} gets fifty session switches and, on account
 * nine, a rate limit it has no way to recognise.
 *
 * @author trq
 * @since 0.9.0
 */
public interface BulkOperations {

    /**
     * Resolves a list of pasted credentials into accounts.
     *
     * <p>Each line is classified by {@link CredentialKind#detect} and sent to the route that fits it. A
     * line that cannot be placed fails as that entry rather than being guessed at. With
     * {@link LoginMode#ADD} the resolved accounts are stored; the live session is not touched either
     * way, so the host chooses afterwards what to log into.
     *
     * @param entries the credential lines, one per account
     * @param mode whether to store what is resolved
     * @param options how to pace the run
     * @param progress where to report progress, or {@link BulkProgress#NONE}
     * @return a handle on the running operation
     * @since 0.9.0
     */
    BulkHandle importCredentials(List<String> entries, LoginMode mode, BulkOptions options, BulkProgress progress);

    /**
     * Refreshes every given account, renewing the ones whose tokens are spent and persisting each
     * rotation.
     *
     * @param accounts the accounts to refresh
     * @param options how to pace the run
     * @param progress where to report progress, or {@link BulkProgress#NONE}
     * @return a handle on the running operation
     * @since 0.9.0
     */
    BulkHandle refreshAll(List<AltAccount> accounts, BulkOptions options, BulkProgress progress);

    /**
     * Checks every given account without renewing anything.
     *
     * <p>The one to reach for when only looking: the token endpoint rotates the refresh token on every
     * redemption, so a sweep built on {@link #refreshAll} spends one rotation per account per sweep.
     *
     * @param accounts the accounts to check
     * @param options how to pace the run
     * @param progress where to report progress, or {@link BulkProgress#NONE}
     * @return a handle on the running operation
     * @since 0.9.0
     */
    BulkHandle checkAll(List<AltAccount> accounts, BulkOptions options, BulkProgress progress);
}
