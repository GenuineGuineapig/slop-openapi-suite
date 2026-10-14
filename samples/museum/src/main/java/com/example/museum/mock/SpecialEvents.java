package com.example.museum.mock;

import com.example.museum.api.CreateSpecialEventRequest;
import com.example.museum.api.CreateSpecialEventResponse;
import com.example.museum.api.DeleteSpecialEventRequest;
import com.example.museum.api.DeleteSpecialEventResponse;
import com.example.museum.api.EventsApi;
import com.example.museum.api.GetSpecialEventRequest;
import com.example.museum.api.GetSpecialEventResponse;
import com.example.museum.api.ListSpecialEventsRequest;
import com.example.museum.api.ListSpecialEventsResponse;
import com.example.museum.api.UpdateSpecialEventRequest;
import com.example.museum.api.UpdateSpecialEventResponse;
import com.example.museum.model.Error;
import com.example.museum.model.SpecialEvent;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Mock special events kept in memory, seeded with two events. */
@Component
public class SpecialEvents implements EventsApi {

    public static final UUID DINOSAURS = UUID.fromString("3be6453c-03eb-4357-ae5a-984a0e574a54");
    public static final UUID SEA_CREATURES = UUID.fromString("dad4bce8-f5cb-4078-a211-995864315e39");

    private final Map<UUID, SpecialEvent> events = new ConcurrentHashMap<>(Map.of(
        DINOSAURS, new SpecialEvent(DINOSAURS, "Mermaid Treasure Identification and Analysis", "Under the seaweed",
            "Join us as we review and classify a rare collection of 20 thingamabobs, gadgets, gizmos, whoosits, and whatsits.",
            List.of(LocalDate.of(2025, 9, 5), LocalDate.of(2025, 9, 8)), 30f),
        SEA_CREATURES, new SpecialEvent(SEA_CREATURES, "Time Traveler Tea Party", "Temporal Tearoom",
            "Sip tea with important historical figures.", List.of(LocalDate.of(2025, 11, 18), LocalDate.of(2025, 11, 25)), 60f)));

    @Override
    public ListSpecialEventsResponse listSpecialEvents(ListSpecialEventsRequest request) {
        var limit = request.limit() != null ? request.limit() : 10;
        var page = request.page() != null ? request.page() : 1;
        var matching = events.values().stream()
            .filter(event -> event.dates().stream().anyMatch(date -> within(date, request.startDate(), request.endDate())))
            .sorted((a, b) -> a.dates().getFirst().compareTo(b.dates().getFirst()))
            .skip((long) (page - 1) * limit)
            .limit(limit)
            .toList();
        return ListSpecialEventsResponse.ok(matching);
    }

    @Override
    public CreateSpecialEventResponse createSpecialEvent(CreateSpecialEventRequest request) {
        var created = request.body().withEventId(UUID.randomUUID());
        events.put(created.eventId(), created);
        return CreateSpecialEventResponse.created(created);
    }

    @Override
    public GetSpecialEventResponse getSpecialEvent(GetSpecialEventRequest request) {
        return find(request.eventId()).<GetSpecialEventResponse>map(GetSpecialEventResponse::ok)
            .orElseGet(() -> GetSpecialEventResponse.notFound(notFound()));
    }

    @Override
    public UpdateSpecialEventResponse updateSpecialEvent(UpdateSpecialEventRequest request) {
        return find(request.eventId()).<UpdateSpecialEventResponse>map(event -> {
            var fields = request.body();
            var updated = new SpecialEvent(event.eventId(), or(fields.name(), event.name()), or(fields.location(), event.location()),
                or(fields.eventDescription(), event.eventDescription()), or(fields.dates(), event.dates()), or(fields.price(), event.price()));
            events.put(updated.eventId(), updated);
            return UpdateSpecialEventResponse.ok(updated);
        }).orElseGet(() -> UpdateSpecialEventResponse.notFound(notFound()));
    }

    @Override
    public DeleteSpecialEventResponse deleteSpecialEvent(DeleteSpecialEventRequest request) {
        return events.remove(request.eventId()) != null ? DeleteSpecialEventResponse.noContent() : DeleteSpecialEventResponse.notFound(notFound());
    }

    private Optional<SpecialEvent> find(UUID id) {
        return Optional.ofNullable(events.get(id));
    }

    private static boolean within(LocalDate date, LocalDate start, LocalDate end) {
        return (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end));
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static Error notFound() {
        return new Error("object", "Event not found");
    }
}
