package lol.trq.alts.account;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Everything an account can do about its name: check whether one is free, learn whether it may change
 * now, change it, and — for a name dropping at a known time — claim it the instant it opens.
 *
 * @author trq
 * @since 1.0.0
 */
public interface NameService {

    /**
     * Checks whether a username can be claimed.
     *
     * @param name the username to check
     * @return whether it is available, taken, or disallowed
     * @throws AccountException if the token was refused or the service failed
     * @since 1.0.0
     */
    NameAvailability checkAvailability(String name) throws AccountException;

    /**
     * Reads whether this account may change its name now, and when it was created.
     *
     * @return the eligibility
     * @throws AccountException if the token was refused or the service failed
     * @since 1.0.0
     */
    NameEligibility eligibility() throws AccountException;

    /**
     * Changes the account's username.
     *
     * @param name the new username
     * @return the classified outcome, carrying the updated profile on success
     * @throws AccountException only on a transport failure the call could not classify; a refused or
     *     rejected change is a {@link NameChangeResult}, not an exception
     * @since 1.0.0
     */
    NameChangeResult change(String name) throws AccountException;

    /**
     * Claims a name at the instant it drops, firing a bounded burst of change attempts around
     * {@code target} and stopping on the first success.
     *
     * <p>The target is an input, not something the library discovers — a host reads the drop time off
     * wherever names are listed and passes it here. Correct the clock through
     * {@link ClaimOptions#timeSource()} if the target is millisecond-critical.
     *
     * @param name the name to claim
     * @param target when the name becomes available
     * @param options how to pace the burst
     * @return a future holding the outcome
     * @since 1.0.0
     */
    CompletableFuture<ClaimResult> claimAt(String name, Instant target, ClaimOptions options);
}
