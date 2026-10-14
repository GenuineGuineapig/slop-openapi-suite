package com.example.train.mock;

import com.example.train.api.NewBookingRequest;
import com.example.train.api.WebhooksClient;
import com.example.train.model.Booking;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The provider side of the {@code newBooking} webhook: sends every new booking to the subscribers
 * (configured in {@code train.webhook-subscribers} or added with {@link #subscribe}), using the
 * generated {@link WebhooksClient}.
 */
@Component
public class BookingNotifier {

    private final WebhooksClient webhooks;
    private final List<URI> subscribers;

    public BookingNotifier(WebhooksClient webhooks, @Value("${train.webhook-subscribers:}") List<URI> subscribers) {
        this.webhooks = webhooks;
        this.subscribers = new CopyOnWriteArrayList<>(subscribers);
    }

    public void subscribe(URI subscriber) {
        subscribers.add(subscriber);
    }

    void newBooking(Booking booking) {
        var payload = new NewBookingRequest.Body(booking.id(), booking.tripId(), booking.passengerName(), booking.hasBicycle(), booking.hasDog(),
            new NewBookingRequest.Body.Links(URI.create(Timetable.BASE + "/bookings/" + booking.id()), null, null));
        subscribers.forEach(subscriber -> webhooks.newBooking(subscriber, new NewBookingRequest(payload)));
    }
}
