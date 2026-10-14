package com.example.galaxy.mock;

import com.example.galaxy.api.CelestialBodiesApi;
import com.example.galaxy.api.CreateCelestialBodyRequest;
import com.example.galaxy.api.CreateCelestialBodyResponse;
import com.example.galaxy.model.CelestialBody;
import com.example.galaxy.model.Planet;
import com.example.galaxy.model.Satellite;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Celestial bodies are planets or satellites, told apart by their {@code type} (a discriminated union). */
@Component
public class MockCelestialBodies implements CelestialBodiesApi {

    private final AtomicLong ids = new AtomicLong(1000);

    @Override
    public CreateCelestialBodyResponse createCelestialBody(CreateCelestialBodyRequest request) {
        CelestialBody created = switch (request.body()) {
            case Planet planet -> planet.withId(ids.incrementAndGet());
            case Satellite satellite -> satellite.withId(ids.incrementAndGet());
        };
        return CreateCelestialBodyResponse.created(created);
    }
}
