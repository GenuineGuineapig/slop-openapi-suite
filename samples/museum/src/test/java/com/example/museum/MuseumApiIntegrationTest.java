package com.example.museum;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.museum.api.BuyMuseumTicketsRequest;
import com.example.museum.api.BuyMuseumTicketsResponse;
import com.example.museum.api.CreateSpecialEventRequest;
import com.example.museum.api.CreateSpecialEventResponse;
import com.example.museum.api.DeleteSpecialEventRequest;
import com.example.museum.api.DeleteSpecialEventResponse;
import com.example.museum.api.EventsClient;
import com.example.museum.api.GetMuseumHoursRequest;
import com.example.museum.api.GetMuseumHoursResponse;
import com.example.museum.api.GetSpecialEventRequest;
import com.example.museum.api.GetSpecialEventResponse;
import com.example.museum.api.GetTicketCodeRequest;
import com.example.museum.api.GetTicketCodeResponse;
import com.example.museum.api.ListSpecialEventsRequest;
import com.example.museum.api.ListSpecialEventsResponse;
import com.example.museum.api.OperationsClient;
import com.example.museum.api.PublishNewEventRequest;
import com.example.museum.api.PublishNewEventResponse;
import com.example.museum.api.TicketsClient;
import com.example.museum.api.UpdateSpecialEventRequest;
import com.example.museum.api.UpdateSpecialEventResponse;
import com.example.museum.api.WebhooksClient;
import com.example.museum.mock.NewEventReceiver;
import com.example.museum.mock.SpecialEvents;
import com.example.museum.model.BuyMuseumTickets;
import com.example.museum.model.MuseumDailyHours;
import com.example.museum.model.SpecialEvent;
import com.example.museum.model.SpecialEventFields;
import com.example.museum.model.TicketType;
import dev.openapifn.spring.client.OpenApiClients;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
 * The generated clients call the generated controllers over HTTP. The server validates requests
 * and responses, the clients validate what they send and receive, and the mock implementations
 * in {@code com.example.museum.mock} answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "openapi-fn.clients.museum.validate=true")
@Import(MuseumApiIntegrationTest.Credentials.class)
class MuseumApiIntegrationTest {

    /** The API requires HTTP basic authentication; every generated client sends these credentials. */
    @TestConfiguration
    static class Credentials {

        @Bean
        RestClientCustomizer basicAuthentication() {
            return builder -> builder.defaultHeaders(headers -> headers.setBasicAuth("visitor", "secret"));
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    OpenApiClients clients;

    @Autowired
    NewEventReceiver receiver;

    OperationsClient operations;
    EventsClient events;
    TicketsClient tickets;
    WebhooksClient webhooks;

    @BeforeEach
    void clients() {
        var http = clients.http(MuseumSpec.NAME, "http://localhost:" + port);
        operations = new OperationsClient(http);
        events = new EventsClient(http);
        tickets = new TicketsClient(http);
        webhooks = new WebhooksClient(http);
    }

    @Test
    void museumHoursSkipMondays() {
        var hours = operations.getMuseumHours(new GetMuseumHoursRequest(LocalDate.of(2025, 6, 2), null, 3));
        assertThat(hours).isEqualTo(GetMuseumHoursResponse.ok(List.of(
            new MuseumDailyHours(LocalDate.of(2025, 6, 3), "09:00", "18:00"),
            new MuseumDailyHours(LocalDate.of(2025, 6, 4), "09:00", "18:00"),
            new MuseumDailyHours(LocalDate.of(2025, 6, 5), "09:00", "18:00"))));
    }

    @Test
    void specialEventLifecycle() {
        var listed = events.listSpecialEvents(new ListSpecialEventsRequest(LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 30), null, null));
        assertThat(listed).isInstanceOfSatisfying(ListSpecialEventsResponse.Ok.class,
            ok -> assertThat(ok.body()).extracting(SpecialEvent::eventId).containsExactly(SpecialEvents.DINOSAURS));

        var draft = new SpecialEvent(null, "Night at the Museum", "Main hall", "Flashlights only.", List.of(LocalDate.of(2025, 10, 31)), 25f);
        var created = (CreateSpecialEventResponse.Created) events.createSpecialEvent(new CreateSpecialEventRequest(draft));
        var id = created.body().eventId();
        assertThat(created.body()).isEqualTo(draft.withEventId(id));

        var updated = events.updateSpecialEvent(new UpdateSpecialEventRequest(id, SpecialEventFields.builder().price(30f).build()));
        assertThat(updated).isInstanceOfSatisfying(UpdateSpecialEventResponse.Ok.class, ok -> assertThat(ok.body().price()).isEqualTo(30f));
        assertThat(events.getSpecialEvent(new GetSpecialEventRequest(id))).isEqualTo(GetSpecialEventResponse.ok(draft.withEventId(id).withPrice(30f)));

        assertThat(events.deleteSpecialEvent(new DeleteSpecialEventRequest(id))).isEqualTo(DeleteSpecialEventResponse.noContent());
        assertThat(events.getSpecialEvent(new GetSpecialEventRequest(id))).isInstanceOfSatisfying(GetSpecialEventResponse.NotFound.class,
            notFound -> assertThat(notFound.body().title()).isEqualTo("Event not found"));
    }

    @Test
    void ticketsComeWithAQrCode() {
        var bought = tickets.buyMuseumTickets(new BuyMuseumTicketsRequest(new BuyMuseumTickets("visitor@example.com", null,
            LocalDate.of(2025, 6, 3), TicketType.GENERAL, null)));
        var confirmation = ((BuyMuseumTicketsResponse.Created) bought).body();
        assertThat(confirmation.message()).isEqualTo("Museum general entry ticket for 2025-06-03 was successfully purchased");

        var code = tickets.getTicketCode(new GetTicketCodeRequest(confirmation.ticketId()));
        assertThat(code).isInstanceOfSatisfying(GetTicketCodeResponse.Ok.class, ok -> {
            assertThat(ok.body().contentType()).isEqualTo("image/png");
            assertThat(ok.body().content()).startsWith(0x89, 'P', 'N', 'G');
        });
        assertThat(tickets.getTicketCode(new GetTicketCodeRequest(UUID.randomUUID()))).isInstanceOf(GetTicketCodeResponse.NotFound.class);
        assertThat(tickets.buyMuseumTickets(new BuyMuseumTicketsRequest(new BuyMuseumTickets(null, null, LocalDate.of(2025, 6, 3),
            TicketType.EVENT, null)))).isInstanceOf(BuyMuseumTicketsResponse.BadRequest.class);
    }

    @Test
    void newEventsArePublishedToWebhookReceivers() {
        var event = new SpecialEvent(UUID.randomUUID(), "Lantern Walk", "Garden", "Bring a lantern.", List.of(LocalDate.of(2025, 12, 1)), 5f);
        var published = webhooks.publishNewEvent(URI.create("http://localhost:" + port + "/webhooks/publishNewEvent"), new PublishNewEventRequest(event));
        assertThat(published).isEqualTo(PublishNewEventResponse.accepted());
        assertThat(receiver.received()).contains(event);
    }

    @Test
    void requestsThatBreakTheContractAreRejected() throws Exception {
        var http = HttpClient.newHttpClient();
        var anonymous = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/museum-hours")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        var invalid = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/special-events"))
            .header("Authorization", "Basic dmlzaXRvcjpzZWNyZXQ=").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\": \"No details\", \"price\": \"free\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("request.body.schema.required", "request.body.schema.type");
    }
}
