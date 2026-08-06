package lol.trq.alts.account;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import lol.trq.alts.net.NetworkScope;

/**
 * Concrete {@link CapeService}: a PUT to show a cape, a DELETE to hide, and the owned list read off the
 * profile.
 *
 * @author trq
 * @since 1.0.0
 */
final class CapeServiceImpl implements CapeService {

    private final AccountHttp http;
    private final AccountEndpoints endpoints;
    private final Gson gson;
    private final ProfileService profile;

    CapeServiceImpl(AccountHttp http, AccountEndpoints endpoints, Gson gson, ProfileService profile) {
        this.http = http;
        this.endpoints = endpoints;
        this.gson = gson;
        this.profile = profile;
    }

    @Override
    public List<Cape> owned() throws AccountException {
        return profile.fetch().capes();
    }

    @Override
    public PlayerProfile setActive(String capeId) throws AccountException {
        JsonObject request = new JsonObject();
        request.addProperty("capeId", capeId);
        JsonObject response =
                http.sendJson("PUT", endpoints.activeCape(), request.toString(), NetworkScope.Purpose.PROFILE);
        return gson.fromJson(response, PlayerProfile.class);
    }

    @Override
    public void hide() throws AccountException {
        http.sendJson("DELETE", endpoints.activeCape(), null, NetworkScope.Purpose.PROFILE);
    }
}
