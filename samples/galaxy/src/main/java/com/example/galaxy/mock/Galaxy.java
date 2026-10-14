package com.example.galaxy.mock;

import com.example.galaxy.model.Planet;
import java.time.OffsetDateTime;
import java.util.List;

/** The planets the mock galaxy starts with. */
public final class Galaxy {

    public static final Planet EARTH = Planet.builder()
        .id(1L).name("Earth").description("The only known planet with life").type(Planet.Type.TERRESTRIAL).habitabilityIndex(1f)
        .physicalProperties(Planet.PhysicalProperties.builder().mass(1f).radius(1f).gravity(1f)
            .temperature(Planet.PhysicalProperties.Temperature.builder().min(184f).max(330f).average(288f).build()).build())
        .atmosphere(List.of(Planet.AtmosphereItem.builder().compound("N2").percentage(78f).build(),
            Planet.AtmosphereItem.builder().compound("O2").percentage(21f).build()))
        .tags(List.of("solar-system", "habitable"))
        .discoveredAt(OffsetDateTime.parse("1600-01-01T00:00:00Z"))
        .lastUpdated(OffsetDateTime.parse("2025-01-01T00:00:00Z"))
        .build();

    public static final Planet MARS = Planet.builder()
        .id(2L).name("Mars").description("The red planet").type(Planet.Type.TERRESTRIAL).habitabilityIndex(0.68f)
        .tags(List.of("solar-system"))
        .lastUpdated(OffsetDateTime.parse("2025-01-01T00:00:00Z"))
        .build();

    public static final Planet JUPITER = Planet.builder()
        .id(3L).name("Jupiter").description("A gas giant with a great red spot").type(Planet.Type.GAS_GIANT).habitabilityIndex(0f)
        .tags(List.of("solar-system", "giant"))
        .lastUpdated(OffsetDateTime.parse("2025-01-01T00:00:00Z"))
        .build();

    public static final List<Planet> PLANETS = List.of(EARTH, MARS, JUPITER);

    private Galaxy() {
    }
}
