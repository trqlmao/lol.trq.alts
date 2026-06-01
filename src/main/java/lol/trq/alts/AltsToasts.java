package lol.trq.alts;

import lol.trq.alts.spi.ToastSink;

/**
 * Thin static helpers over the host {@link ToastSink} for the toasts the alt manager dispatches around
 * login events. Centralizes the title and duration choices so every host renders identical copy.
 *
 * <p>The sink is bound once by {@link AltsRuntime.Builder#build()}; calls before binding are no-ops.
 *
 * @author trq
 * @since 0.1.0
 */
public final class AltsToasts {

    private static final int SUCCESS_MS = 3000;
    private static final int ERROR_MS = 4000;
    private static final int INFO_MS = 2500;

    private static ToastSink sink;

    private AltsToasts() {}

    static void bind(ToastSink toastSink) {
        sink = toastSink;
    }

    /**
     * Publishes a success toast announcing a completed login.
     *
     * @param username the name of the account that was logged in
     */
    public static void success(String username) {
        publish(ToastSink.Level.SUCCESS, "Logged In", "Logged in as " + username, SUCCESS_MS);
    }

    /**
     * Publishes an error toast describing why a login failed.
     *
     * @param message the human-readable failure reason to display
     */
    public static void failure(String message) {
        publish(ToastSink.Level.ERROR, "Login Failed", message, ERROR_MS);
    }

    /**
     * Publishes a general-purpose informational toast with a custom title and body.
     *
     * @param title the toast heading
     * @param description the toast body text
     */
    public static void info(String title, String description) {
        publish(ToastSink.Level.INFO, title, description, INFO_MS);
    }

    /**
     * Publishes an informational toast confirming an account was removed.
     *
     * @param username the name of the account that was removed
     */
    public static void removed(String username) {
        publish(ToastSink.Level.INFO, "Account Removed", "Removed " + username, INFO_MS);
    }

    private static void publish(ToastSink.Level level, String title, String description, int durationMs) {
        if (sink != null) {
            sink.toast(level, title, description, durationMs);
        }
    }
}
