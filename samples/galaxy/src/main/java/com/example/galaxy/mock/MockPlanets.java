package com.example.galaxy.mock;

import com.example.galaxy.api.CreatePlanetRequest;
import com.example.galaxy.api.CreatePlanetResponse;
import com.example.galaxy.api.DeletePlanetRequest;
import com.example.galaxy.api.DeletePlanetResponse;
import com.example.galaxy.api.GetAllDataRequest;
import com.example.galaxy.api.GetAllDataResponse;
import com.example.galaxy.api.GetPlanetRequest;
import com.example.galaxy.api.GetPlanetResponse;
import com.example.galaxy.api.PlanetsApi;
import com.example.galaxy.api.UpdatePlanetRequest;
import com.example.galaxy.api.UpdatePlanetResponse;
import com.example.galaxy.api.UploadImageRequest;
import com.example.galaxy.api.UploadImageResponse;
import com.example.galaxy.model.BadRequestError;
import com.example.galaxy.model.ImageUploadedMessage;
import com.example.galaxy.model.NotFoundError;
import com.example.galaxy.model.PaginatedResource;
import com.example.galaxy.model.Planet;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Mock planets in memory; creating one calls back the creator and notifies webhook subscribers. */
@Component
public class MockPlanets implements PlanetsApi {

    private final Map<Long, Planet> planets = new ConcurrentSkipListMap<>(Galaxy.PLANETS.stream().collect(Collectors.toMap(Planet::id, p -> p)));
    private final AtomicLong ids = new AtomicLong(100);
    private final PlanetNotifier notifier;

    public MockPlanets(PlanetNotifier notifier) {
        this.notifier = notifier;
    }

    /** A page of planets: {@code PaginatedResource<Planet>}, generated from a generic JSON Schema ({@code $dynamicRef}). */
    @Override
    public GetAllDataResponse getAllData(GetAllDataRequest request) {
        var limit = request.limit() != null ? request.limit() : 10;
        var offset = request.offset() != null ? request.offset() : 0;
        var all = List.copyOf(planets.values());
        var page = all.stream().skip(offset).limit(limit).toList();
        var next = offset + limit < all.size() ? "/planets?limit=" + limit + "&offset=" + (offset + limit) : null;
        var body = new PaginatedResource<>(page, new PaginatedResource.Meta(limit, offset, (long) all.size(), next));
        return GetAllDataResponse.ok(body, UUID.randomUUID(), all.size(), (int) (offset / limit) + 1, (int) limit, "max-age=60");
    }

    @Override
    public CreatePlanetResponse createPlanet(CreatePlanetRequest request) {
        var draft = request.body();
        if (draft == null || draft.name().isBlank()) {
            if (draft != null && draft.failureCallbackUrl() != null) {
                notifier.creationFailed(request);
            }
            return CreatePlanetResponse.badRequest(new BadRequestError("https://galaxy.scalar.com/errors/bad-request", "Bad Request", 400L,
                "A planet needs a name"), UUID.randomUUID());
        }
        var planet = draft.withId(ids.incrementAndGet()).withLastUpdated(OffsetDateTime.now());
        planets.put(planet.id(), planet);
        notifier.created(request, planet);
        return CreatePlanetResponse.created(planet, UUID.randomUUID(), URI.create("https://galaxy.scalar.com/planets/" + planet.id()), 42);
    }

    @Override
    public GetPlanetResponse getPlanet(GetPlanetRequest request) {
        var planet = planets.get(request.planetId());
        return planet != null ? GetPlanetResponse.ok(planet, UUID.randomUUID(), "\"planet-" + planet.id() + "\"", planet.lastUpdated(), "max-age=60")
            : GetPlanetResponse.notFound(notFound(request.planetId()));
    }

    @Override
    public UpdatePlanetResponse updatePlanet(UpdatePlanetRequest request) {
        if (!planets.containsKey(request.planetId()) || request.body() == null) {
            return UpdatePlanetResponse.notFound(notFound(request.planetId()));
        }
        var updated = request.body().withId(request.planetId()).withLastUpdated(OffsetDateTime.now());
        planets.put(updated.id(), updated);
        return UpdatePlanetResponse.ok(updated);
    }

    @Override
    public DeletePlanetResponse deletePlanet(DeletePlanetRequest request) {
        var removed = planets.remove(request.planetId());
        if (removed == null) {
            return DeletePlanetResponse.notFound(notFound(request.planetId()));
        }
        notifier.exploded(removed);
        return DeletePlanetResponse.noContent();
    }

    /** Accepts a multipart image upload and describes it. */
    @Override
    public UploadImageResponse uploadImage(UploadImageRequest request) {
        if (!planets.containsKey(request.planetId())) {
            return UploadImageResponse.notFound(notFound(request.planetId()));
        }
        var image = request.body() != null ? request.body().image() : null;
        var size = image != null ? image.content().length : 0;
        var type = image != null ? image.contentType() : "application/octet-stream";
        return UploadImageResponse.ok(new ImageUploadedMessage("Image uploaded successfully",
            "https://cdn.scalar.com/images/planets/" + request.planetId() + ".png", OffsetDateTime.now(), size, type), UUID.randomUUID(), "no-store");
    }

    private static NotFoundError notFound(long id) {
        return new NotFoundError("https://galaxy.scalar.com/errors/not-found", "Not Found", 404L, "No planet " + id);
    }
}
