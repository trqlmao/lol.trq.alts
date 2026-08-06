package lol.trq.alts.account;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;
import lol.trq.alts.net.NetworkScope;

/**
 * Concrete {@link EntitlementService}: reads the {@code items[]} of {@code /entitlements/mcstore} into
 * the product-id set.
 *
 * <p>Only the product names are read. The endpoint also returns per-item signatures and source data,
 * but the set of names is the whole answer to "what does this account own", and the extra fields are
 * noise a host does not act on.
 *
 * @author trq
 * @since 1.0.0
 */
final class EntitlementServiceImpl implements EntitlementService {

    private final AccountHttp http;
    private final AccountEndpoints endpoints;

    EntitlementServiceImpl(AccountHttp http, AccountEndpoints endpoints) {
        this.http = http;
        this.endpoints = endpoints;
    }

    @Override
    public Entitlements fetch() throws AccountException {
        JsonObject body = http.get(endpoints.entitlements(), NetworkScope.Purpose.PROFILE);
        Set<String> products = new LinkedHashSet<>();
        if (body.has("items") && body.get("items").isJsonArray()) {
            for (JsonElement item : body.getAsJsonArray("items")) {
                if (item.isJsonObject() && item.getAsJsonObject().has("name")) {
                    products.add(item.getAsJsonObject().get("name").getAsString());
                }
            }
        }
        return new Entitlements(products);
    }
}
