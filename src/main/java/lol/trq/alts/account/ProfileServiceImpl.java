package lol.trq.alts.account;

import com.google.gson.Gson;
import lol.trq.alts.net.NetworkScope;

/**
 * Concrete {@link ProfileService}: one bearer GET, deserialised into a {@link PlayerProfile}.
 *
 * @author trq
 * @since 1.0.0
 */
final class ProfileServiceImpl implements ProfileService {

    private final AccountHttp http;
    private final AccountEndpoints endpoints;
    private final Gson gson;

    ProfileServiceImpl(AccountHttp http, AccountEndpoints endpoints, Gson gson) {
        this.http = http;
        this.endpoints = endpoints;
        this.gson = gson;
    }

    @Override
    public PlayerProfile fetch() throws AccountException {
        return gson.fromJson(http.get(endpoints.profile(), NetworkScope.Purpose.PROFILE), PlayerProfile.class);
    }
}
