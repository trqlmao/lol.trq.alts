package lol.trq.alts.auth;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import lol.trq.alts.auth.AccountNetworkUtil.ProfileLookup;
import lol.trq.alts.auth.AccountStatus.State;
import lol.trq.alts.auth.AltLoginCallback.FailureReason;
import lol.trq.alts.model.AccountType;
import lol.trq.alts.model.AltAccount;
import lol.trq.alts.net.NetworkScope;
import lol.trq.alts.store.AltStore;

/**
 * Concrete {@link AltAccountService}: validation and renewal, with no session anywhere in it.
 *
 * <p>{@link AltLoginServiceImpl} delegates here and adds the injection as its last step, which is the
 * decomposition that lets an account be operated on without being logged into.
 *
 * @author trq
 * @since 0.8.0
 */
public class AltAccountServiceImpl implements AltAccountService {

    private final MicrosoftAuthConfig microsoftAuth;
    private final Clock clock;

    /**
     * Creates an account service.
     *
     * @param microsoftAuth the host's Microsoft authentication configuration, or {@code null} to disable
     *     renewal (validation still works)
     * @since 0.8.0
     */
    public AltAccountServiceImpl(MicrosoftAuthConfig microsoftAuth) {
        this(microsoftAuth, Clock.systemUTC());
    }

    // Exists so expiry-sensitive behaviour can be exercised against a fixed clock in tests.
    AltAccountServiceImpl(MicrosoftAuthConfig microsoftAuth, Clock clock) {
        this.microsoftAuth = microsoftAuth;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletableFuture<AccountStatus> check(AltAccount account) {
        if (account == null) {
            return CompletableFuture.completedFuture(
                    AccountStatus.failure(null, State.UNKNOWN, FailureReason.UNKNOWN, "No account given"));
        }
        if (account.type() == AccountType.OFFLINE) {
            return CompletableFuture.completedFuture(AccountStatus.valid(account));
        }
        if (account.accessToken() == null || account.accessToken().isBlank()) {
            // Nothing to ask about. Whether that is recoverable depends only on what else it carries.
            return CompletableFuture.completedFuture(AccountStatus.failure(
                    account,
                    renewable(account) ? State.EXPIRED : State.REAUTH_REQUIRED,
                    FailureReason.INVALID_TOKEN,
                    "No stored session token"));
        }
        return CompletableFuture.supplyAsync(() -> validate(account));
    }

    @Override
    public CompletableFuture<AccountStatus> refresh(AltAccount account) {
        if (account == null) {
            return CompletableFuture.completedFuture(
                    AccountStatus.failure(null, State.UNKNOWN, FailureReason.UNKNOWN, "No account given"));
        }
        if (account.type() == AccountType.OFFLINE) {
            return CompletableFuture.completedFuture(AccountStatus.valid(account));
        }

        boolean renewable = renewable(account);
        boolean expired = TokenExpiry.isExpired(account, clock);

        if (!renewable) {
            // A live token on an account that cannot be renewed is taken at its word, so refreshing is
            // no more expensive than logging in was.
            return expired
                    ? CompletableFuture.supplyAsync(() -> validate(account))
                    : CompletableFuture.completedFuture(AccountStatus.valid(account));
        }
        if (expired) {
            return renew(account);
        }
        return CompletableFuture.supplyAsync(() -> validate(account)).thenCompose(status -> {
            // Only a refused token is worth a renewal. An unreachable service, or an account with no
            // profile, says nothing about the credential -- and the token endpoint rotates the refresh
            // token on every redemption, so renewing on those spends a rotation to fix something else.
            if (status.state() != State.EXPIRED) {
                return CompletableFuture.completedFuture(status);
            }
            return renew(account);
        });
    }

    /**
     * Asks the profile endpoint what the account's stored token is worth.
     *
     * @param account the account to validate
     * @return the classified outcome
     */
    private AccountStatus validate(AltAccount account) {
        ProfileLookup lookup;
        try {
            lookup = AccountNetworkUtil.fetchProfile(account.accessToken(), profileUrl(), scopeFor(account));
        } catch (Exception unreachable) {
            return AccountStatus.failure(
                    account,
                    State.UNREACHABLE,
                    FailureReason.NETWORK,
                    "Validation failed: " + unreachable.getMessage());
        }
        if (lookup.found()) {
            return AccountStatus.valid(account);
        }
        FailureReason reason = reasonFor(lookup);
        return AccountStatus.failure(account, stateFor(reason, renewable(account)), reason, messageFor(lookup));
    }

    /**
     * Renews an account from its stored refresh token, persisting the rotated credentials. The renewed
     * account is derived from the stored one, so bans, provenance, and shared attribution survive.
     *
     * @param account the account to renew
     * @return a future holding the outcome
     */
    private CompletableFuture<AccountStatus> renew(AltAccount account) {
        return MicrosoftAuthUtil.authenticateWithRefreshToken(microsoftAuth, account.refreshToken())
                .thenApply(profile -> {
                    AltAccount renewed =
                            account.withTokens(profile.accessToken(), profile.refreshToken(), profile.expiresAt());
                    AltStore.updateCredentials(renewed);
                    return AccountStatus.renewed(renewed);
                })
                .exceptionally(failure -> renewalFailure(failure, account));
    }

    /**
     * Maps a failed renewal onto a status, discarding the stored refresh token only when the rejection
     * is permanent. A transient failure must never cost the user a working credential.
     *
     * @param failure the failure the renewal completed with
     * @param account the account being renewed
     * @return the classified status
     */
    private AccountStatus renewalFailure(Throwable failure, AltAccount account) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        boolean permanent =
                cause instanceof MicrosoftAuthUtil.RefreshRejectedException rejection && rejection.permanent();
        String message = "Refresh: " + (cause.getMessage() != null ? cause.getMessage() : "unknown error");

        if (!permanent) {
            return AccountStatus.failure(account, State.UNREACHABLE, FailureReason.NETWORK, message);
        }
        AltStore.clearRefreshToken(account.uuid());
        return AccountStatus.failure(
                account.withTokens(account.accessToken(), null, 0L),
                State.REAUTH_REQUIRED,
                FailureReason.REAUTH_REQUIRED,
                message);
    }

    /**
     * Returns whether an account could be renewed if its token turned out to be spent.
     *
     * @param account the account to inspect
     * @return true if it holds a refresh token and Microsoft login is configured
     */
    private boolean renewable(AltAccount account) {
        return account.hasRefreshToken() && microsoftAuth != null;
    }

    /**
     * Returns the profile endpoint to validate against — the configured one when Microsoft login is
     * wired up, and the public default otherwise.
     *
     * @return the Minecraft services profile endpoint
     */
    private String profileUrl() {
        return microsoftAuth != null
                ? microsoftAuth.minecraftProfileUrl()
                : MicrosoftAuthConfig.DEFAULT_MINECRAFT_PROFILE_URL;
    }

    /**
     * Builds the network scope for a request made on one account's behalf, so a host proxying per
     * account can tell whose validation this is.
     *
     * @param account the account being validated
     * @return the scope
     */
    private static NetworkScope scopeFor(AltAccount account) {
        return NetworkScope.forAccount(NetworkScope.Purpose.PROFILE, account.uuid(), account.username());
    }

    /**
     * Classifies a profile lookup that did not resolve an identity. Package-private because the login
     * routes classify the same three answers the same way.
     *
     * @param lookup the lookup that failed
     * @return the classified cause
     */
    static FailureReason reasonFor(ProfileLookup lookup) {
        if (lookup.refused()) {
            return FailureReason.INVALID_TOKEN;
        }
        if (lookup.missingProfile()) {
            return FailureReason.NOT_ENTITLED;
        }
        return FailureReason.NETWORK;
    }

    /**
     * Describes a profile lookup that did not resolve an identity.
     *
     * @param lookup the lookup that failed
     * @return a short human-readable description
     */
    static String messageFor(ProfileLookup lookup) {
        if (lookup.refused()) {
            return "Session token was refused";
        }
        if (lookup.missingProfile()) {
            return "This account has no Minecraft profile";
        }
        return "Profile lookup failed with status " + lookup.status();
    }

    /**
     * Maps a cause onto the state that describes it, given whether renewal is still on the table. A
     * refused token is only re-authentication material when there is no refresh token left to try.
     *
     * @param reason the classified cause
     * @param renewable whether the account could still be renewed
     * @return the matching state
     */
    private static State stateFor(FailureReason reason, boolean renewable) {
        return switch (reason) {
            case INVALID_TOKEN -> renewable ? State.EXPIRED : State.REAUTH_REQUIRED;
            case NOT_ENTITLED -> State.NOT_ENTITLED;
            case NETWORK -> State.UNREACHABLE;
            default -> State.UNKNOWN;
        };
    }
}
