package com.example.train;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.train.api.BookingsClient;
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
import com.example.train.api.GetStationsRequest;
import com.example.train.api.GetStationsResponse;
import com.example.train.api.GetTripsRequest;
import com.example.train.api.GetTripsResponse;
import com.example.train.api.PaymentsClient;
import com.example.train.api.QueryStationsRequest;
import com.example.train.api.QueryStationsResponse;
import com.example.train.api.StationsClient;
import com.example.train.api.SubscribeTripRequest;
import com.example.train.api.SubscribeTripResponse;
import com.example.train.api.TripsClient;
import com.example.train.mock.BookingNotifier;
import com.example.train.mock.BookingWebhookReceiver;
import com.example.train.mock.MockTrips;
import com.example.train.mock.Timetable;
import com.example.train.model.Booking;
import com.example.train.model.BookingPayment;
import com.example.train.model.Station;
import com.example.train.model.StationQuery;
import com.example.train.model.TripUpdate;
import dev.openapifn.spring.client.OpenApiClients;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The generated clients call the generated controllers over HTTP and the mocks in
 * {@code com.example.train.mock} answer; requests and responses are validated on both sides.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "openapi-fn.clients.train.validate=true")
@Import(TrainTravelApiIntegrationTest.Token.class)
class TrainTravelApiIntegrationTest {

    /** The API is protected by OAuth 2; every generated client sends this bearer token. */
    @TestConfiguration
    static class Token {

        @Bean
        RestClientCustomizer bearerToken() {
            return builder -> builder.defaultHeaders(headers -> headers.setBearerAuth("mock-token"));
        }
    }

    static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2025-06-03T08:00:00+02:00");

    @LocalServerPort
    int port;

    @Autowired
    OpenApiClients clients;

    @Autowired
    BookingNotifier notifier;

    @Autowired
    BookingWebhookReceiver receiver;

    StationsClient stations;
    TripsClient trips;
    BookingsClient bookings;
    PaymentsClient payments;

    @BeforeEach
    void clients() {
        var http = clients.http(TrainSpec.NAME, "http://localhost:" + port);
        stations = new StationsClient(http);
        trips = new TripsClient(http);
        bookings = new BookingsClient(http);
        payments = new PaymentsClient(http);
    }

    @Test
    @SuppressWarnings("deprecation")
    void findStations() {
        var found = stations.getStations(GetStationsRequest.builder().search("hauptbahnhof").build());
        assertThat(found).isInstanceOfSatisfying(GetStationsResponse.Ok.class, ok -> {
            assertThat(ok.body().data()).containsExactly(Timetable.BERLIN, Timetable.HAMBURG);
            assertThat(ok.cacheControl()).isEqualTo("max-age=3600");
        });
        // QUERY is the HTTP method for safe requests with a body (OpenAPI 3.2).
        var queried = stations.queryStations(new QueryStationsRequest(null, null, new StationQuery(null, null, "FR")));
        assertThat(queried).isInstanceOfSatisfying(QueryStationsResponse.Ok.class,
            ok -> assertThat(ok.body().data()).extracting(Station::name).containsExactly("Paris Gare du Nord"));
    }

    @Test
    void findTripsAndFollowOne() {
        var found = trips.getTrips(new GetTripsRequest(null, null, Timetable.BERLIN.id(), Timetable.PARIS.id(), DEPARTURE, true, null));
        var trip = ((GetTripsResponse.Ok) found).body().data().getFirst();
        assertThat(trip.operator()).isEqualTo("Eurostar");
        assertThat(trip.departureTime()).isEqualTo(DEPARTURE.plusHours(1));
        assertThat(trip.price()).isEqualByComparingTo("59.90");

        // A custom SUBSCRIBE method (OpenAPI 3.2 additionalOperations) streaming JSON Lines: each update arrives as it is produced.
        var updates = (SubscribeTripResponse.Ok) trips.subscribeTrip(new SubscribeTripRequest(trip.id()));
        var arrivals = new java.util.ArrayList<Long>();
        try (var stream = updates.body()) {
            assertThat(stream.peek(update -> arrivals.add(System.nanoTime())).map(TripUpdate::type))
                .containsExactly(TripUpdate.Type.DELAY, TripUpdate.Type.PLATFORM, TripUpdate.Type.DEPARTED);
        }
        assertThat(java.time.Duration.ofNanos(arrivals.getLast() - arrivals.getFirst())).isGreaterThan(MockTrips.UPDATE_INTERVAL);
    }

    @Test
    void bookPayAndCancel() {
        notifier.subscribe(URI.create("http://localhost:" + port + "/webhooks/newBooking"));
        var tripId = MockTrips.tripId(Timetable.BERLIN.id(), Timetable.PARIS.id(), 1);
        var request = new CreateBookingRequest(Booking.builder().tripId(tripId).passengerName("Ada Lovelace").hasBicycle(true).build());
        var response = bookings.createBooking(request);
        assertThat(response).isInstanceOf(CreateBookingResponse.Created.class);
        var created = ((CreateBookingResponse.Created) response).body();
        assertThat(created.links().self()).isEqualTo(URI.create("https://api.example.com/bookings/" + created.id()));
        assertThat(bookings.createBooking(request)).isInstanceOf(CreateBookingResponse.Conflict.class);

        // The booking was announced through the newBooking webhook, which this application also receives.
        assertThat(receiver.received()).anySatisfy(body -> {
            assertThat(body.id()).isEqualTo(created.id());
            assertThat(body.passengerName()).isEqualTo("Ada Lovelace");
        });

        assertThat(bookings.getBookings(new GetBookingsRequest(null, null))).isInstanceOfSatisfying(GetBookingsResponse.Ok.class,
            ok -> assertThat(ok.body().data()).extracting(Booking::id).contains(created.id()));
        assertThat(bookings.getBooking(new GetBookingRequest(created.id()))).isInstanceOfSatisfying(GetBookingResponse.Ok.class,
            ok -> assertThat(ok.body().hasBicycle()).isTrue());

        var card = BookingPayment.Source.Card.builder().name("Ada Lovelace").number("4242424242424242").cvc("123").expMonth(12L).expYear(2030L)
            .addressLine1("1 Main Street").addressCountry("GB").build();
        var paid = payments.createBookingPayment(new CreateBookingPaymentRequest(created.id(),
            BookingPayment.builder().amount(new BigDecimal("59.90")).currency(BookingPayment.Currency.GBP).source(card).build()));
        assertThat(paid).isInstanceOfSatisfying(CreateBookingPaymentResponse.Ok.class, ok -> {
            assertThat(ok.body().status()).isEqualTo(BookingPayment.Status.SUCCEEDED);
            assertThat(ok.body().source()).isInstanceOfSatisfying(BookingPayment.Source.Card.class, masked -> {
                assertThat(masked.number()).isEqualTo("************4242");
                assertThat(masked.cvc()).isNull();
                assertThat(masked.object()).isEqualTo("card");
            });
        });

        assertThat(bookings.deleteBooking(new DeleteBookingRequest(created.id()))).isEqualTo(DeleteBookingResponse.noContent());
        assertThat(bookings.getBooking(new GetBookingRequest(created.id()))).isInstanceOfSatisfying(GetBookingResponse.NotFound.class,
            notFound -> assertThat(notFound.body().status()).isEqualTo(404));
    }

    @Test
    void requestsThatBreakTheContractAreRejected() throws Exception {
        var http = HttpClient.newHttpClient();
        var anonymous = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/bookings")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        var invalid = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/bookings"))
            .header("Authorization", "Bearer mock-token").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"trip_id\": \"tomorrow\", \"passenger_name\": \"Ada\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("request.body.schema.format");
    }
}
