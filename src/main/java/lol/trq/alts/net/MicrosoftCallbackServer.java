package lol.trq.alts.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lol.trq.alts.auth.MicrosoftAuthConfig;
import lol.trq.alts.auth.MicrosoftAuthUtil;

/**
 * A temporary local HTTP server that captures the authorization code returned by Microsoft's OAuth2
 * redirect. Listens on a loopback address and shuts itself down after capturing the code or timing
 * out. The client id, scope, authorize endpoint, and callback path all come from the host-supplied
 * {@link MicrosoftAuthConfig}.
 *
 * @author trq
 * @since 0.1.0
 */
public class MicrosoftCallbackServer {

    private static final Pattern CODE_PATTERN = Pattern.compile("code=([^&]+)");
    private static final Pattern STATE_PATTERN = Pattern.compile("state=([^&]+)");

    /** Ports to attempt binding, in order of preference. */
    private static final int[] PORTS = {59125, 25585, 25575, 25565, 0};

    private final String expectedState;
    private final MicrosoftAuthConfig config;
    private final CompletableFuture<String> codeFuture = new CompletableFuture<>();
    private HttpServer server;
    private int boundPort;

    /**
     * Initializes a new callback server with the expected state parameter and the host's auth config.
     *
     * @param state the state string generated during auth initiation, verified against CSRF
     * @param config the host's Microsoft authentication configuration (client id, scope, endpoints)
     */
    public MicrosoftCallbackServer(String state, MicrosoftAuthConfig config) {
        this.expectedState = state;
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Binds the server to an available port, starts listening for the redirect, and opens the user's
     * browser to the Microsoft login page.
     *
     * @return a future that completes with the captured authorization code
     */
    public CompletableFuture<String> start() {
        try {
            bindToAvailablePort();
            server.createContext(config.redirectPath(), this::handleCallback);
            server.setExecutor(null);
            server.start();

            String authUrl = String.format(
                    "%s?client_id=%s&response_type=code&scope=%s&redirect_uri=%s&prompt=select_account&state=%s",
                    config.authorizeUrl(),
                    URLEncoder.encode(config.clientId(), StandardCharsets.UTF_8),
                    URLEncoder.encode(config.scope(), StandardCharsets.UTF_8),
                    URLEncoder.encode(redirectUri(), StandardCharsets.UTF_8),
                    expectedState);

            MicrosoftAuthUtil.openBrowser(authUrl);

            CompletableFuture.delayedExecutor(5, TimeUnit.MINUTES).execute(() -> {
                if (!codeFuture.isDone()) {
                    codeFuture.completeExceptionally(new Exception("Authentication timeout"));
                    stop();
                }
            });
        } catch (Exception e) {
            codeFuture.completeExceptionally(e);
        }
        return codeFuture;
    }

    /**
     * Attempts to bind the server to the first available port in {@link #PORTS}.
     *
     * @throws IOException if the server cannot bind to any of the specified ports
     */
    private void bindToAvailablePort() throws IOException {
        server = HttpServer.create();
        for (int port : PORTS) {
            try {
                server.bind(new InetSocketAddress("localhost", port), 0);
                boundPort = server.getAddress().getPort();
                return;
            } catch (IOException ignored) {
            }
        }
        throw new IOException("No ports available");
    }

    /**
     * Handles the HTTP callback request. Validates the origin and query parameters before completing
     * the future.
     *
     * @param exchange the HTTP exchange object
     * @throws IOException if stream writing fails
     */
    private void handleCallback(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();

        // Security check: only allow requests originating from the local machine
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
            exchange.close();
            return;
        }

        try {
            if (query == null) throw new Exception("Empty query");

            Matcher codeMatcher = CODE_PATTERN.matcher(query);
            Matcher stateMatcher = STATE_PATTERN.matcher(query);

            if (!codeMatcher.find() || !stateMatcher.find()) throw new Exception("Invalid params");

            String code = codeMatcher.group(1);
            String state = stateMatcher.group(1);

            // Verify state to ensure the response matches the request sent by the client
            if (!expectedState.equals(state)) throw new Exception("State mismatch");

            codeFuture.complete(code);
            sendResponse(exchange, 200, "Authentication Successful. You may close this tab.");
        } catch (Exception e) {
            codeFuture.completeExceptionally(e);
            sendResponse(exchange, 400, "Authentication Failed.");
        }
    }

    /**
     * Sends a simple HTML response back to the user's browser.
     *
     * @param exchange the HTTP exchange
     * @param status the HTTP status code
     * @param message the message to display in the browser
     * @throws IOException if writing to the response body fails
     */
    private void sendResponse(HttpExchange exchange, int status, String message) throws IOException {
        String html =
                "<!DOCTYPE html><html><body style='font-family:sans-serif;text-align:center;padding-top:50px;'><h1>"
                        + message + "</h1></body></html>";
        byte[] response = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
        exchange.close();
    }

    /**
     * Returns the full redirect URI used for the OAuth request.
     *
     * @return the absolute loopback URL for the callback
     */
    public String redirectUri() {
        return "http://localhost:" + boundPort + config.redirectPath();
    }

    /** Forcefully stops the HTTP server. */
    public void stop() {
        if (server != null) server.stop(0);
    }
}
