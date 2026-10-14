package com.example.galaxy.mock;

import com.example.galaxy.api.CallbacksClient;
import com.example.galaxy.api.CreatePlanetRequest;
import com.example.galaxy.api.PlanetCreatedRequest;
import com.example.galaxy.api.PlanetCreationFailedRequest;
import com.example.galaxy.api.PlanetExplodedRequest;
import com.example.galaxy.api.PostNewPlanetRequest;
import com.example.galaxy.api.WebhooksClient;
import com.example.galaxy.model.Planet;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * The provider side of callbacks and webhooks: calls back the URLs a planet was created with
 * ({@code planetCreated}, {@code planetCreationFailed}, {@code planetExploded}) and sends the
 * {@code newPlanet} webhook to subscribers, using the generated clients.
 */
@Component
public class PlanetNotifier {

    private final CallbacksClient callbacks;
    private final WebhooksClient webhooks;
    private final List<URI> subscribers = new CopyOnWriteArrayList<>();

    public PlanetNotifier(CallbacksClient callbacks, WebhooksClient webhooks) {
        this.callbacks = callbacks;
        this.webhooks = webhooks;
    }

    public void subscribe(URI subscriber) {
        subscribers.add(subscriber);
    }

    /** Calls back the URL the request named ({@code $request.body#/successCallbackUrl}). */
    void created(CreatePlanetRequest request, Planet planet) {
        if (planet.successCallbackUrl() != null) {
            callbacks.planetCreated(callbacks.planetCreatedUrl(request), new PlanetCreatedRequest(planet));
        }
        subscribers.forEach(subscriber -> webhooks.postNewPlanet(subscriber, new PostNewPlanetRequest(planet)));
    }

    /** The payload is a planet as the API sends it, which always has an id; a rejected one gets 0. */
    void creationFailed(CreatePlanetRequest request) {
        callbacks.planetCreationFailed(callbacks.planetCreationFailedUrl(request), new PlanetCreationFailedRequest(request.body().withId(0L)));
    }

    /** The planet as stored is what its creation request (as updated since) described. */
    void exploded(Planet planet) {
        if (planet.successCallbackUrl() != null) {
            callbacks.planetExploded(callbacks.planetExplodedUrl(new CreatePlanetRequest(planet)), new PlanetExplodedRequest(planet));
        }
    }
}
