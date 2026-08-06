package lol.trq.alts.account;

import com.google.gson.JsonObject;
import java.util.Map;
import lol.trq.alts.net.HttpUtil;
import lol.trq.alts.net.NetworkScope;

/**
 * Shared request plumbing for the account services: attach the bearer token, route by scope, and turn a
 * non-2xx into an {@link AccountException} carrying its status and — where the service sent one — its
 * {@code errorMessage}.
 *
 * <p>Package-private. Every service builds on this so the auth header, the error mapping, and the
 * account-scoped routing live in one place rather than being repeated per call.
 *
 * @author trq
 * @since 1.0.0
 */
final class AccountHttp {

    private final String token;
    private final String accountUuid;

    AccountHttp(String token, String accountUuid) {
        this.token = token;
        this.accountUuid = accountUuid;
    }

    /** The bearer token, for a service that classifies a raw response itself rather than through {@link #send}. */
    String token() {
        return token;
    }

    /** The account UUID, for building a scope on such a call. */
    String accountUuid() {
        return accountUuid;
    }

    /** Returns the bearer header this account's calls carry. */
    private Map<String, String> auth() {
        return Map.of("Authorization", "Bearer " + token);
    }

    private NetworkScope scope(NetworkScope.Purpose purpose) {
        return NetworkScope.forAccount(purpose, accountUuid, null);
    }

    /**
     * GETs a resource, returning its JSON body.
     *
     * @param url the target URL
     * @param purpose the request purpose, for routing
     * @return the parsed body
     * @throws AccountException if the request was refused or failed
     */
    JsonObject get(String url, NetworkScope.Purpose purpose) throws AccountException {
        return body(send("GET", url, null, null, purpose));
    }

    /**
     * Sends a JSON-bodied request, returning the response body (which may be null on a 204).
     *
     * @param method the HTTP method
     * @param url the target URL
     * @param json the JSON request body, or null for none
     * @param purpose the request purpose
     * @return the parsed response body, or null when there was none
     * @throws AccountException if the request was refused or failed
     */
    JsonObject sendJson(String method, String url, String json, NetworkScope.Purpose purpose) throws AccountException {
        return send(method, url, json == null ? null : "application/json", json == null ? null : bytes(json), purpose);
    }

    /**
     * Sends a request carrying a raw body of a given content type — for the multipart skin upload.
     *
     * @param method the HTTP method
     * @param url the target URL
     * @param contentType the full Content-Type, boundary included
     * @param body the raw body
     * @param purpose the request purpose
     * @return the parsed response body, or null
     * @throws AccountException if the request was refused or failed
     */
    JsonObject sendRaw(String method, String url, String contentType, byte[] body, NetworkScope.Purpose purpose)
            throws AccountException {
        return send(method, url, contentType, body, purpose);
    }

    private JsonObject send(String method, String url, String contentType, byte[] body, NetworkScope.Purpose purpose)
            throws AccountException {
        HttpUtil.HttpResponse response;
        try {
            response = HttpUtil.sendForStatus(method, url, contentType, auth(), body, scope(purpose));
        } catch (Exception transport) {
            throw new AccountException(method + " " + describe(url) + ": " + transport.getMessage(), transport);
        }
        if (!response.successful()) {
            throw new AccountException(method + " " + describe(url) + ": " + reason(response), response.status());
        }
        return response.body();
    }

    /**
     * Requires a non-null body, for a GET whose response the caller must be able to read.
     *
     * @param body the response body
     * @return the body
     * @throws AccountException when the body was absent or not JSON
     */
    private static JsonObject body(JsonObject body) throws AccountException {
        if (body == null) {
            throw new AccountException("empty or non-JSON response", 0);
        }
        return body;
    }

    /**
     * Builds a failure description, preferring the service's own {@code errorMessage} over the bare
     * status so the message says what went wrong rather than only that something did.
     *
     * @param response the rejected response
     * @return a short reason
     */
    private static String reason(HttpUtil.HttpResponse response) {
        JsonObject body = response.body();
        if (body != null) {
            for (String field : new String[] {"errorMessage", "error", "details"}) {
                if (body.has(field) && body.get(field).isJsonPrimitive()) {
                    return body.get(field).getAsString();
                }
            }
        }
        return "status " + response.status();
    }

    /** Strips the query string from a URL so an error message names the endpoint, never a token in a query. */
    private static String describe(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
