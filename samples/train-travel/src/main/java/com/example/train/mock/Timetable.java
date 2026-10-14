package com.example.train.mock;

import com.example.train.model.Station;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The mock network: a few stations and the trips between them. */
public final class Timetable {

    /** Links in responses are absolute URLs (the description requires {@code format: uri}). */
    public static final String BASE = "https://api.example.com";

    public static final Station BERLIN = new Station(UUID.fromString("efdbb9d1-02c2-4bc3-afb7-6788d8782b1e"), "Berlin Hauptbahnhof",
        "Invalidenstraße 10557 Berlin, Germany", "DE", "Europe/Berlin");
    public static final Station PARIS = new Station(UUID.fromString("b2e783e1-c824-4d63-b37a-d8d698862f1d"), "Paris Gare du Nord",
        "18 Rue de Dunkerque 75010 Paris, France", "FR", "Europe/Paris");
    public static final Station AMSTERDAM = new Station(UUID.fromString("b6e5b2a3-9f3a-4c3e-8f3a-6c4c3e8f3a6c"), "Amsterdam Centraal",
        "Stationsplein, 1012 AB Amsterdam, Netherlands", "NL", "Europe/Amsterdam");
    public static final Station HAMBURG = new Station(UUID.fromString("3c2a1b0f-9e8d-4c7b-a6f5-e4d3c2b1a0f9"), "Hamburg Hauptbahnhof",
        "Hachmannplatz 16, 20099 Hamburg, Germany", "DE", "Europe/Berlin");

    public static final List<Station> STATIONS = List.of(BERLIN, PARIS, AMSTERDAM, HAMBURG);

    /** The operator and fare of a connection. */
    public record Connection(String operator, BigDecimal price, boolean bicycles, boolean dogs) {
    }

    /** Every pair of stations is connected twice a day: a fast train and a slower one that takes bicycles and dogs. */
    public static List<Connection> connections() {
        return List.of(new Connection("Deutsche Bahn", new BigDecimal("89.90"), false, true),
            new Connection("Eurostar", new BigDecimal("59.90"), true, true));
    }

    private Timetable() {
    }
}
