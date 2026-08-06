package lol.trq.alts.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import lol.trq.alts.spi.ProxyProvider;

/**
 * Centralized utility for handling HTTP requests to Microsoft, Xbox, and Mojang APIs. Supports JSON
 * and form-encoded payloads with automated header management.
 *
 * <p>Every request the library makes goes through here, which is what lets a host route them: install a
 * {@link ProxyProvider} and each call resolves a {@link ProxyRoute} from its {@link NetworkScope} first.
 * The scope-less overloads are kept for compatibility and resolve as {@link NetworkScope.Purpose#AUTH}.
 *
 * @author trq
 * @since 0.1.0
 */
public final class HttpUtil {

    /** How long to wait for the TCP connection before giving the request up. */
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    /** How long to wait for response bytes before giving the request up. */
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    private static volatile ProxyProvider proxyProvider;

    private HttpUtil() {}

    /**
     * Binds the host's proxy provider, or clears it with {@code null}. Called once by
     * {@code AltsRuntime.Builder#build()}; without one every request is direct.
     *
     * @param provider the host-supplied proxy provider, or null for direct connections
     * @since 0.8.0
     */
    public static void bind(ProxyProvider provider) {
        proxyProvider = provider;
    }

    /**
     * Resolves the route a request should take.
     *
     * <p>Fails closed once a provider is installed: a provider that throws or returns null fails the
     * request rather than quietly connecting directly, because a silent fallback discloses the host's
     * real address at the moment it believed it was proxied.
     *
     * @param scope what is being fetched; null resolves as {@link NetworkScope.Purpose#AUTH}
     * @return the route to use
     * @throws IOException if an installed provider could not name one
     */
    static ProxyRoute routeFor(NetworkScope scope) throws IOException {
        ProxyProvider provider = proxyProvider;
        if (provider == null) {
            return ProxyRoute.direct();
        }
        NetworkScope effective = scope == null ? NetworkScope.of(NetworkScope.Purpose.AUTH) : scope;
        ProxyRoute route;
        try {
            route = provider.routeFor(effective);
        } catch (RuntimeException failure) {
            throw new IOException("proxy resolution failed for " + effective.purpose(), failure);
        }
        if (route == null) {
            throw new IOException("proxy provider named no route for " + effective.purpose());
        }
        return route;
    }

    /**
     * Applies the connection defaults every request shares. The timeouts are finite because these calls
     * run on a shared thread pool: an endpoint that accepts a connection and then goes silent would
     * otherwise hold a pooled thread for the life of the process and leave the login future to never
     * complete. Redirects are not followed because {@code HttpURLConnection} replays the caller's
     * request properties to the redirect target, which would send an {@code Authorization: Bearer}
     * header to whatever host a {@code 3xx} names.
     *
     * @param conn the connection to configure
     */
    static void applyDefaults(HttpURLConnection conn) {
        conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        conn.setReadTimeout(READ_TIMEOUT_MILLIS);
        conn.setInstanceFollowRedirects(false);
    }

    /**
     * Opens a connection over the route the scope resolves to.
     *
     * <p>A direct route opens without naming a proxy, rather than naming {@link java.net.Proxy#NO_PROXY},
     * so the JVM's own proxy configuration keeps working for a host that set one. "Direct" means the
     * library adds no proxy of its own.
     *
     * @param urlString the target URL
     * @param scope what is being fetched, and on whose behalf
     * @return the configured connection
     * @throws IOException if the route cannot be resolved or the connection cannot be opened
     */
    private static HttpURLConnection open(String urlString, NetworkScope scope) throws IOException {
        URI uri = URI.create(urlString);
        ProxyRoute route = routeFor(scope);
        HttpURLConnection conn = (HttpURLConnection)
                (route.isDirect() ? uri.toURL().openConnection() : uri.toURL().openConnection(route.toProxy()));
        applyDefaults(conn);
        String authorization = route.authorizationHeader();
        if (authorization != null) {
            conn.setRequestProperty("Proxy-Authorization", authorization);
        }
        return conn;
    }

    /**
     * Sends a POST request with a JSON body.
     *
     * @param urlString the target URL
     * @param headers optional HTTP headers to include in the request
     * @param jsonBody the JSON payload as a string
     * @return the parsed JSON response, or null if the request returned a non-2xx status
     * @throws Exception if a network or protocol error occurs
     */
    public static JsonObject postJson(String urlString, Map<String, String> headers, String jsonBody) throws Exception {
        return postJson(urlString, headers, jsonBody, null);
    }

    /**
     * Sends a POST request with a JSON body over the route the scope resolves to.
     *
     * @param urlString the target URL
     * @param headers optional HTTP headers to include in the request
     * @param jsonBody the JSON payload as a string
     * @param scope what is being fetched, and on whose behalf
     * @return the parsed JSON response, or null if the request returned a non-2xx status
     * @throws Exception if a network or protocol error occurs
     * @since 0.8.0
     */
    public static JsonObject postJson(
            String urlString, Map<String, String> headers, String jsonBody, NetworkScope scope) throws Exception {
        return executeRequest(
                urlString, "POST", "application/json", headers, jsonBody.getBytes(StandardCharsets.UTF_8), scope);
    }

    /**
     * Sends a POST request with an x-www-form-urlencoded body.
     *
     * @param urlString the target URL
     * @param headers optional HTTP headers to include in the request
     * @param formBody the form data payload as a string
     * @return the parsed JSON response, or null if the request failed
     * @throws Exception if a network or protocol error occurs
     */
    public static JsonObject postForm(String urlString, Map<String, String> headers, String formBody) throws Exception {
        return postForm(urlString, headers, formBody, null);
    }

    /**
     * Sends a POST request with an x-www-form-urlencoded body over the route the scope resolves to.
     *
     * @param urlString the target URL
     * @param headers optional HTTP headers to include in the request
     * @param formBody the form data payload as a string
     * @param scope what is being fetched, and on whose behalf
     * @return the parsed JSON response, or null if the request failed
     * @throws Exception if a network or protocol error occurs
     * @since 0.8.0
     */
    public static JsonObject postForm(
            String urlString, Map<String, String> headers, String formBody, NetworkScope scope) throws Exception {
        return executeRequest(
                urlString,
                "POST",
                "application/x-www-form-urlencoded",
                headers,
                formBody.getBytes(StandardCharsets.UTF_8),
                scope);
    }

    /**
     * An HTTP response carrying its status alongside the parsed body, for callers that must
     * distinguish a rejected request from a transient failure.
     *
     * @param status the HTTP status code
     * @param body the parsed JSON body, or {@code null} when the body was absent or not JSON
     * @param retryAfter how long the service asked the caller to wait, or {@code null} when it did not
     * @author trq
     * @since 0.6.0
     */
    public record HttpResponse(int status, JsonObject body, Duration retryAfter) {

        /**
         * Returns whether the status is in the 2xx success range.
         *
         * @return true if the request succeeded
         * @since 0.6.0
         */
        public boolean successful() {
            return status >= 200 && status < 300;
        }

        /**
         * Returns whether the service is asking the caller to slow down.
         *
         * @return true if the status is 429
         * @since 0.8.0
         */
        public boolean rateLimited() {
            return status == 429;
        }
    }

    /**
     * Sends a POST request with an x-www-form-urlencoded body, returning the status alongside the
     * parsed body so the caller can tell a rejection from an outage.
     *
     * @param urlString the target URL
     * @param headers optional HTTP headers to include in the request
     * @param formBody the form data payload as a string
     * @return the status and parsed body; the body is null when absent or not JSON
     * @throws Exception if a network or protocol error occurs
     * @since 0.6.0
     */
    public static HttpResponse postFormForStatus(String urlString, Map<String, String> headers, String formBody)
            throws Exception {
        return postFormForStatus(urlString, headers, formBody, null);
    }

    /**
     * Sends a POST request with an x-www-form-urlencoded body over the route the scope resolves to,
     * returning the status alongside the parsed body.
     *
     * @param urlString the target URL
     * @param headers optional HTTP headers to include in the request
     * @param formBody the form data payload as a string
     * @param scope what is being fetched, and on whose behalf
     * @return the status and parsed body; the body is null when absent or not JSON
     * @throws Exception if a network or protocol error occurs
     * @since 0.8.0
     */
    public static HttpResponse postFormForStatus(
            String urlString, Map<String, String> headers, String formBody, NetworkScope scope) throws Exception {
        return executeForStatus(
                urlString,
                "POST",
                "application/x-www-form-urlencoded",
                headers,
                formBody.getBytes(StandardCharsets.UTF_8),
                scope);
    }

    /**
     * Sends a GET request to the specified URL.
     *
     * @param urlString the target URL
     * @param headers optional HTTP headers to include in the request
     * @return the parsed JSON response, or null if the request failed
     * @throws Exception if a network or protocol error occurs
     */
    public static JsonObject get(String urlString, Map<String, String> headers) throws Exception {
        return get(urlString, headers, null);
    }

    /**
     * Sends a GET request over the route the scope resolves to.
     *
     * @param urlString the target URL
     * @param headers optional HTTP headers to include in the request
     * @param scope what is being fetched, and on whose behalf
     * @return the parsed JSON response, or null if the request failed
     * @throws Exception if a network or protocol error occurs
     * @since 0.8.0
     */
    public static JsonObject get(String urlString, Map<String, String> headers, NetworkScope scope) throws Exception {
        return executeRequest(urlString, "GET", null, headers, null, scope);
    }

    /**
     * Sends a GET request over the route the scope resolves to, returning the status alongside the
     * parsed body.
     *
     * @param urlString the target URL
     * @param headers optional HTTP headers to include in the request
     * @param scope what is being fetched, and on whose behalf
     * @return the status and parsed body; the body is null when absent or not JSON
     * @throws Exception if a network or protocol error occurs
     * @since 0.8.0
     */
    public static HttpResponse getForStatus(String urlString, Map<String, String> headers, NetworkScope scope)
            throws Exception {
        return executeForStatus(urlString, "GET", null, headers, null, scope);
    }

    /**
     * A response reduced to its status and one header, for a caller following a redirect chain by hand.
     *
     * @param status the HTTP status code
     * @param value the requested header's value, or {@code null} when the response did not carry it
     * @author trq
     * @since 0.8.0
     */
    public record HeaderResponse(int status, String value) {}

    /**
     * Sends a GET request and reads one response header, discarding the body. For following a redirect
     * chain a step at a time, which the cookie flow does.
     *
     * @param urlString the target URL
     * @param headers optional HTTP headers to include in the request
     * @param headerName the response header to read
     * @param scope what is being fetched, and on whose behalf
     * @return the status and the header's value
     * @throws Exception if a network or protocol error occurs
     * @since 0.8.0
     */
    public static HeaderResponse getHeader(
            String urlString, Map<String, String> headers, String headerName, NetworkScope scope) throws Exception {
        HttpURLConnection conn = open(urlString, scope);
        try {
            conn.setRequestMethod("GET");
            applyHeaders(conn, null, headers);
            int status = conn.getResponseCode();
            return new HeaderResponse(status, conn.getHeaderField(headerName));
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Sends a GET request and returns the raw response body, for a resource that is not JSON.
     *
     * <p>The cap binds the read itself. A response arriving over a proxy the library does not control is
     * exactly the case where an unbounded read is a memory hazard.
     *
     * <p>Unlike every other method here this one follows redirects, because the resources it fetches are
     * images and image hosts redirect. That is safe precisely as long as the request carries no
     * credential to replay, so a caller that supplies an {@code Authorization} header gets the strict
     * behaviour back automatically.
     *
     * @param urlString the target URL
     * @param headers optional HTTP headers to include in the request
     * @param maxBytes the largest body to accept
     * @param scope what is being fetched, and on whose behalf
     * @return the body bytes, or null on a non-2xx status or a body past the cap
     * @throws Exception if a network or protocol error occurs
     * @since 0.8.0
     */
    public static byte[] getBytes(String urlString, Map<String, String> headers, long maxBytes, NetworkScope scope)
            throws Exception {
        HttpURLConnection conn = open(urlString, scope);
        try {
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(!carriesCredential(headers));
            applyHeaders(conn, null, headers);

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                drainErrorBody(conn);
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return readCapped(in, maxBytes);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Returns whether a header map carries something a redirect must not be allowed to replay.
     *
     * @param headers the caller's headers, or null
     * @return true if an authorization header is present
     */
    private static boolean carriesCredential(Map<String, String> headers) {
        if (headers == null) {
            return false;
        }
        return headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("Authorization"));
    }

    /**
     * Reads a stream, giving up rather than allocating past the cap.
     *
     * @param in the stream to read
     * @param maxBytes the largest body to accept
     * @return the bytes read, or null if the body ran past the cap
     * @throws IOException if reading fails
     */
    private static byte[] readCapped(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (out.size() + read > maxBytes) {
                return null;
            }
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Applies the shared and caller-supplied request headers. Caller headers land last, so a caller that
     * needs its own {@code Accept} or {@code User-Agent} — the cookie flow, which must look like a
     * browser — replaces the default rather than fighting it.
     *
     * @param conn the connection to configure
     * @param contentType the Content-Type header value, or null if no body is sent
     * @param headers map of additional headers to apply
     */
    private static void applyHeaders(HttpURLConnection conn, String contentType, Map<String, String> headers) {
        if (contentType != null) {
            conn.setRequestProperty("Content-Type", contentType);
        }
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        if (headers != null) {
            headers.forEach(conn::setRequestProperty);
        }
    }

    /**
     * Internal method to execute HTTP requests and handle stream lifecycle. Configures common headers
     * such as User-Agent and Accept.
     *
     * @param urlString the target URL
     * @param method the HTTP method (GET, POST, etc.)
     * @param contentType the Content-Type header value, or null if no body is sent
     * @param headers map of additional headers to apply
     * @param body the raw byte array of the request body, or null
     * @param scope what is being fetched, and on whose behalf
     * @return the parsed JsonObject from the response stream, or null if status is not 2xx
     * @throws Exception if connection fails or stream reading is interrupted
     */
    private static JsonObject executeRequest(
            String urlString,
            String method,
            String contentType,
            Map<String, String> headers,
            byte[] body,
            NetworkScope scope)
            throws Exception {
        HttpURLConnection conn = open(urlString, scope);
        conn.setRequestMethod(method);
        applyHeaders(conn, contentType, headers);
        writeBody(conn, body);

        int status = conn.getResponseCode();

        // Validation check for successful response codes (200-299)
        if (status < 200 || status >= 300) {
            drainErrorBody(conn);
            return null;
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            return JsonParser.parseReader(br).getAsJsonObject();
        }
    }

    /**
     * Consumes and closes a rejected response's body. An error body left unread keeps its connection out
     * of the keep-alive pool, so a run of failures — a rate limit, a service having a bad minute — opens
     * a fresh socket every time instead of reusing one.
     *
     * @param conn the connection whose error body to discard
     */
    private static void drainErrorBody(HttpURLConnection conn) {
        try (InputStream errors = conn.getErrorStream()) {
            if (errors != null) {
                errors.transferTo(OutputStream.nullOutputStream());
            }
        } catch (Exception ignored) {
            // Draining is an optimization; failing to do it must never fail the request.
        }
    }

    /**
     * Writes a request body, if there is one.
     *
     * @param conn the connection to write to
     * @param body the body bytes, or null
     * @throws IOException if writing fails
     */
    private static void writeBody(HttpURLConnection conn, byte[] body) throws IOException {
        if (body == null) {
            return;
        }
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
    }

    private static HttpResponse executeForStatus(
            String urlString,
            String method,
            String contentType,
            Map<String, String> headers,
            byte[] body,
            NetworkScope scope)
            throws Exception {
        HttpURLConnection conn = open(urlString, scope);
        conn.setRequestMethod(method);
        applyHeaders(conn, contentType, headers);
        writeBody(conn, body);

        int status = conn.getResponseCode();
        Duration retryAfter = retryAfter(conn);
        InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) {
            return new HttpResponse(status, null, retryAfter);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            JsonElement parsed = JsonParser.parseReader(br);
            return new HttpResponse(status, parsed.isJsonObject() ? parsed.getAsJsonObject() : null, retryAfter);
        } catch (Exception parseFailure) {
            return new HttpResponse(status, null, retryAfter);
        }
    }

    /**
     * Reads a {@code Retry-After} delay. Only the delta-seconds form is read; the HTTP-date form is legal
     * but is not what these services send, and guessing at a clock skew to interpret one would be worse
     * than reporting that no delay was given.
     *
     * @param conn the connection to read from
     * @return the delay the service asked for, or null when it asked for none
     */
    private static Duration retryAfter(HttpURLConnection conn) {
        String header = conn.getHeaderField("Retry-After");
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notSeconds) {
            return null;
        }
    }
}
