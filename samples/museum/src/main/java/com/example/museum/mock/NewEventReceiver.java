package com.example.museum.mock;

import com.example.museum.api.PublishNewEventRequest;
import com.example.museum.api.PublishNewEventResponse;
import com.example.museum.api.WebhooksApi;
import com.example.museum.model.SpecialEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** Receives the {@code publishNewEvent} webhook (at {@code /webhooks/publishNewEvent}) and remembers the announced events. */
@Component
public class NewEventReceiver implements WebhooksApi {

    private final List<SpecialEvent> received = new CopyOnWriteArrayList<>();

    @Override
    public PublishNewEventResponse publishNewEvent(PublishNewEventRequest request) {
        if (request.body() != null) {
            received.add(request.body());
        }
        return PublishNewEventResponse.accepted();
    }

    public List<SpecialEvent> received() {
        return List.copyOf(received);
    }
}
