package lol.trq.alts.account;

/**
 * What a scheduled name claim came to.
 *
 * @param claimed whether the name was taken
 * @param attempts how many change attempts were made
 * @param finalResult the last change attempt's result, which on success is the winning one
 * @author trq
 * @since 1.0.0
 */
public record ClaimResult(boolean claimed, int attempts, NameChangeResult finalResult) {}
