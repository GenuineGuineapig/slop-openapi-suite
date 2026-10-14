package com.example.train.mock;

import com.example.train.api.GetTripsRequest;
import com.example.train.api.GetTripsResponse;
import com.example.train.api.SubscribeTripRequest;
import com.example.train.api.SubscribeTripResponse;
import com.example.train.api.TripsApi;
import com.example.train.model.TripUpdate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Mock trips: every connection of {@link Timetable} departs at the requested time and the next hour. */
@Component
public class MockTrips implements TripsApi {

    /** The time between two live updates of a trip. */
    public static final java.time.Duration UPDATE_INTERVAL = java.time.Duration.ofMillis(300);

    @Override
    public GetTripsResponse getTrips(GetTripsRequest request) {
        var connections = Timetable.connections();
        var trips = java.util.stream.IntStream.range(0, connections.size())
            .mapToObj(index -> {
                var connection = connections.get(index);
                var departure = request.date().plusHours(index);
                var id = tripId(request.origin(), request.destination(), index);
                return new GetTripsResponse.OkBody.DataItem(id, request.origin().toString(), request.destination().toString(), departure,
                    departure.plusHours(5), connection.operator(), connection.price(), connection.bicycles(), connection.dogs(),
                    URI.create(Timetable.BASE + "/trips/" + id));
            })
            .filter(trip -> !Boolean.TRUE.equals(request.bicycles()) || trip.bicyclesAllowed())
            .filter(trip -> !Boolean.TRUE.equals(request.dogs()) || trip.dogsAllowed())
            .toList();
        return GetTripsResponse.ok(new GetTripsResponse.OkBody(MockStations.page(trips, request.page(), request.limit()),
            new GetTripsResponse.OkBody.Links(URI.create(Timetable.BASE + "/trips"), null, null)), "max-age=60", null);
    }

    /**
     * Live updates of a trip: a delay, a platform change and the departure, produced one by one
     * (a pause stands in for waiting on the next real event). Each update is sent, and validated,
     * as soon as it is produced.
     */
    @Override
    public SubscribeTripResponse subscribeTrip(SubscribeTripRequest request) {
        var at = java.time.OffsetDateTime.parse("2024-02-01T09:12:00Z");
        var updates = Stream.of(
            TripUpdate.builder().type(TripUpdate.Type.DELAY).tripId(request.id()).occurredAt(at).minutes(12).reason("Signal failure near Hannover"),
            TripUpdate.builder().type(TripUpdate.Type.PLATFORM).tripId(request.id()).occurredAt(at.plusMinutes(6)).platform("7"),
            TripUpdate.builder().type(TripUpdate.Type.DEPARTED).tripId(request.id()).occurredAt(at.plusHours(1)));
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        return SubscribeTripResponse.ok(updates.map(update -> {
            if (!first.getAndSet(false)) {
                pause();
            }
            return update.build();
        }), "no-cache", null);
    }

    private static void pause() {
        try {
            Thread.sleep(UPDATE_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A stable trip id for a connection. */
    public static UUID tripId(UUID origin, UUID destination, int index) {
        return UUID.nameUUIDFromBytes((origin + "/" + destination + "/" + index).getBytes(StandardCharsets.UTF_8));
    }
}
