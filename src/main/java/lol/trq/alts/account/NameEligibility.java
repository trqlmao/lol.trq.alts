package lol.trq.alts.account;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether an account may change its name right now, from {@code GET /minecraft/profile/namechange}.
 *
 * <p>Mojang enforces a cooldown between name changes; {@code nameChangeAllowed} is whether the account is
 * past it. {@code createdAt} is when the account was made, which a host turns into account age — useful
 * on its own, and the anchor for the cooldown a caller may want to display.
 *
 * @param nameChangeAllowed whether a name change is permitted now
 * @param createdAt when the account was created, or {@code null} when the endpoint did not say
 * @author trq
 * @since 1.0.0
 */
public record NameEligibility(boolean nameChangeAllowed, Instant createdAt) {

    /**
     * Returns how long ago the account was created, or {@code null} when the creation time is unknown.
     *
     * @param now the current instant to measure against
     * @return the account's age, or null
     * @since 1.0.0
     */
    public Duration ageAt(Instant now) {
        return createdAt == null ? null : Duration.between(createdAt, now);
    }
}
