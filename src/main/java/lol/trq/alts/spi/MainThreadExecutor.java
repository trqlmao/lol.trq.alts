package lol.trq.alts.spi;

/**
 * Runs a task on the host's main/render thread. Mirrors a game loop's {@code execute(Runnable)} hook
 * (for example {@code Minecraft#execute}). The library uses it to marshal texture uploads and any
 * other render-thread-only work off background threads.
 *
 * @author trq
 * @since 0.1.0
 */
@FunctionalInterface
public interface MainThreadExecutor {

    /**
     * Schedules {@code task} to run on the main thread.
     *
     * @param task the work to run
     */
    void execute(Runnable task);
}
