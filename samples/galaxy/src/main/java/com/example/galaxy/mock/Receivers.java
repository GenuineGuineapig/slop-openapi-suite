package com.example.galaxy.mock;

import com.example.galaxy.api.CallbacksApi;
import com.example.galaxy.api.PlanetCreatedRequest;
import com.example.galaxy.api.PlanetCreatedResponse;
import com.example.galaxy.api.PlanetCreationFailedRequest;
import com.example.galaxy.api.PlanetCreationFailedResponse;
import com.example.galaxy.api.PlanetExplodedRequest;
import com.example.galaxy.api.PlanetExplodedResponse;
import com.example.galaxy.api.PostNewPlanetRequest;
import com.example.galaxy.api.PostNewPlanetResponse;
import com.example.galaxy.api.WebhooksApi;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * The consumer side: receives the callbacks (at {@code /callbacks/<name>}) and the webhook (at
 * {@code /webhooks/postNewPlanet}) and records what arrived.
 */
@Component
public class Receivers implements CallbacksApi, WebhooksApi {

    private final List<String> received = new CopyOnWriteArrayList<>();

    @Override
    public PlanetCreatedResponse planetCreated(PlanetCreatedRequest request) {
        received.add("created:" + request.body().name());
        return PlanetCreatedResponse.ok();
    }

    @Override
    public PlanetCreationFailedResponse planetCreationFailed(PlanetCreationFailedRequest request) {
        received.add("failed:" + request.body().name());
        return PlanetCreationFailedResponse.ok();
    }

    @Override
    public PlanetExplodedResponse planetExploded(PlanetExplodedRequest request) {
        received.add("exploded:" + request.body().name());
        return PlanetExplodedResponse.ok();
    }

    @Override
    public PostNewPlanetResponse postNewPlanet(PostNewPlanetRequest request) {
        received.add("webhook:" + request.body().name());
        return PostNewPlanetResponse.ok();
    }

    public List<String> received() {
        return List.copyOf(received);
    }
}
