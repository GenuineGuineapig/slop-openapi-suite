package com.example.train.mock;

import com.example.train.api.NewBookingRequest;
import com.example.train.api.NewBookingResponse;
import com.example.train.api.WebhooksApi;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** The subscriber side of the {@code newBooking} webhook, received at {@code /webhooks/newBooking}. */
@Component
public class BookingWebhookReceiver implements WebhooksApi {

    private final List<NewBookingRequest.Body> received = new CopyOnWriteArrayList<>();

    @Override
    public NewBookingResponse newBooking(NewBookingRequest request) {
        received.add(request.body());
        return NewBookingResponse.ok();
    }

    public List<NewBookingRequest.Body> received() {
        return List.copyOf(received);
    }
}
