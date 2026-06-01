package lol.trq.alts.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Centralized utility for handling HTTP requests to Microsoft, Xbox, and Mojang APIs. Supports JSON
 * and form-encoded payloads with automated header management.
 *
 * @author trq
 * @since 0.1.0
 */
public final class HttpUtil {

    private HttpUtil() {}

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
        return executeRequest(
                urlString, "POST", "application/json", headers, jsonBody.getBytes(StandardCharsets.UTF_8));
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
        return executeRequest(
                urlString,
                "POST",
                "application/x-www-form-urlencoded",
                headers,
                formBody.getBytes(StandardCharsets.UTF_8));
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
        return executeRequest(urlString, "GET", null, headers, null);
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
     * @return the parsed JsonObject from the response stream, or null if status is not 2xx
     * @throws Exception if connection fails or stream reading is interrupted
     */
    private static JsonObject executeRequest(
            String urlString, String method, String contentType, Map<String, String> headers, byte[] body)
            throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);

        if (contentType != null) {
            conn.setRequestProperty("Content-Type", contentType);
        }

        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

        if (headers != null) {
            headers.forEach(conn::setRequestProperty);
        }

        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
        }

        int status = conn.getResponseCode();

        // Validation check for successful response codes (200-299)
        if (status < 200 || status >= 300) {
            return null;
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            return JsonParser.parseReader(br).getAsJsonObject();
        }
    }
}
