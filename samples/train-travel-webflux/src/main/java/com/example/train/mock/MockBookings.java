package com.example.train.mock;

import com.example.train.api.BookingsApi;
import com.example.train.api.CreateBookingPaymentRequest;
import com.example.train.api.CreateBookingPaymentResponse;
import com.example.train.api.CreateBookingRequest;
import com.example.train.api.CreateBookingResponse;
import com.example.train.api.DeleteBookingRequest;
import com.example.train.api.DeleteBookingResponse;
import com.example.train.api.GetBookingRequest;
import com.example.train.api.GetBookingResponse;
import com.example.train.api.GetBookingsRequest;
import com.example.train.api.GetBookingsResponse;
import com.example.train.api.PaymentsApi;
import com.example.train.model.Booking;
import com.example.train.model.BookingPayment;
import com.example.train.model.LinksBooking;
import com.example.train.model.LinksSelf;
import com.example.train.model.Problem;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Mock bookings and payments kept in memory; new bookings are announced to webhook subscribers. */
@Component
public class MockBookings implements BookingsApi, PaymentsApi {

    private final Map<UUID, Booking> bookings = new ConcurrentSkipListMap<>();
    private final BookingNotifier notifier;

    public MockBookings(BookingNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public Mono<GetBookingsResponse> getBookings(GetBookingsRequest request) {
        var page = MockStations.page(java.util.List.copyOf(bookings.values()), request.page(), request.limit());
        return Mono.just(GetBookingsResponse.ok(new GetBookingsResponse.OkBody(page, new GetBookingsResponse.OkBody.Links(URI.create(Timetable.BASE
            + "/bookings"), null, null)), "no-store", null));
    }

    @Override
    public Mono<CreateBookingResponse> createBooking(CreateBookingRequest request) {
        var draft = request.body();
        if (bookings.values().stream().anyMatch(b -> b.tripId().equals(draft.tripId()) && b.passengerName().equals(draft.passengerName()))) {
            return Mono.just(CreateBookingResponse.conflict(problem("conflict", "Conflict", draft.passengerName() + " is already booked on this trip", 409),
                null));
        }
        var booking = draft.withId(UUID.randomUUID());
        bookings.put(booking.id(), booking);
        // Answers once the subscribers have been told, without blocking a thread.
        return notifier.newBooking(booking).thenReturn(CreateBookingResponse.created(new CreateBookingResponse.CreatedBody(booking.id(), booking.tripId(),
            booking.passengerName(), booking.hasBicycle(), booking.hasDog(), self(booking.id()))));
    }

    @Override
    public Mono<GetBookingResponse> getBooking(GetBookingRequest request) {
        var booking = bookings.get(request.bookingId());
        if (booking == null) {
            return Mono.just(GetBookingResponse.notFound(notFound(request.bookingId()), null));
        }
        return Mono.just(GetBookingResponse.ok(new GetBookingResponse.OkBody(booking.id(), booking.tripId(), booking.passengerName(), booking.hasBicycle(),
            booking.hasDog(), self(booking.id())), "no-store", null));
    }

    @Override
    public Mono<DeleteBookingResponse> deleteBooking(DeleteBookingRequest request) {
        return Mono.just(bookings.remove(request.bookingId()) != null ? DeleteBookingResponse.noContent()
            : DeleteBookingResponse.notFound(notFound(request.bookingId()), null));
    }

    /** Takes the payment; card security codes and addresses are write-only and never echoed back. */
    @Override
    public Mono<CreateBookingPaymentResponse> createBookingPayment(CreateBookingPaymentRequest request) {
        var payment = request.body();
        if (!bookings.containsKey(request.bookingId())) {
            return Mono.just(CreateBookingPaymentResponse.badRequest(problem("bad-request", "Bad Request", "No booking " + request.bookingId(), 400), null));
        }
        var source = switch (payment.source()) {
            case BookingPayment.Source.Card card -> card.withCvc(null).withAddressLine1(null).withAddressLine2(null)
                .withNumber("************" + card.number().substring(card.number().length() - 4));
            case BookingPayment.Source.BankAccount account -> account;
            case null -> null;
        };
        return Mono.just(CreateBookingPaymentResponse.ok(new CreateBookingPaymentResponse.OkBody(UUID.randomUUID(), payment.amount(), payment.currency(),
            source, BookingPayment.Status.SUCCEEDED, new LinksBooking(URI.create(Timetable.BASE + "/bookings/" + request.bookingId()))), "no-store", null));
    }

    private static LinksSelf self(UUID id) {
        return new LinksSelf(URI.create(Timetable.BASE + "/bookings/" + id));
    }

    private static Problem notFound(UUID id) {
        return problem("not-found", "Not Found", "No booking " + id, 404);
    }

    private static Problem problem(String type, String title, String detail, int status) {
        return new Problem("https://example.com/errors/" + type, title, detail, null, status);
    }
}
