package lol.trq.alts.spi;

import lol.trq.alts.model.SessionData;

/**
 * Host hook that swaps the running game's authenticated session to a freshly logged-in account. The
 * library hands the host a transport-neutral {@link SessionData}; the host translates it into
 * whatever session type its platform uses (for example {@code net.minecraft.client.User}) and
 * installs it.
 *
 * <p>Called off the main thread by the login service; implementations that touch render-thread state
 * must marshal onto it via {@link MainThreadExecutor}.
 *
 * @author trq
 * @since 0.1.0
 */
@FunctionalInterface
public interface SessionInjector {

    /**
     * Installs {@code session} as the client's active identity.
     *
     * @param session the resolved account to make current
     */
    void inject(SessionData session);
}
