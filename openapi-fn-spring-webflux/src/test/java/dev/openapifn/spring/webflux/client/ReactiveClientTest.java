package dev.openapifn.spring.webflux.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.http.Headers;
import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.spring.FilePart;
import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.client.ClientCall;
import dev.openapifn.spring.client.ClientValidationException;
import dev.openapifn.spring.webflux.app.ReactiveTestApplication;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = ReactiveTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"openapi-fn.clients.pets.validate=true", "openapi-fn.clients.configured.base-url=http://configured.example.com"})
class ReactiveClientTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final ParameterSerializer.Spec LIMIT = ParameterSerializer.Spec.of("limit", ParameterLocation.QUERY);
    private static final ParameterSerializer.Spec FORMAT = ParameterSerializer.Spec.of("format", ParameterLocation.QUERY);
    private static final ParameterSerializer.Spec COUNT = ParameterSerializer.Spec.of("count", ParameterLocation.QUERY);
    private static final ParameterSerializer.Spec INVALID = ParameterSerializer.Spec.of("invalid", ParameterLocation.QUERY);
    private static final ParameterSerializer.Spec MODE = ParameterSerializer.Spec.of("mode", ParameterLocation.QUERY);
    private static final ParameterSerializer.Spec KEY = ParameterSerializer.Spec.of("X-Key", ParameterLocation.HEADER);
    private static final TypeReference<Map<String, Integer>> EVENT = new TypeReference<>() { };

    @LocalServerPort
    int port;

    @Autowired
    OpenApiWebClients clients;

    @Autowired
    OpenApiRuntimes runtimes;

    private OpenApiWebClient plain() {
        return new OpenApiWebClient(WebClient.create(), "http://localhost:" + port + "/api/", MAPPER);
    }

    private OpenApiWebClient validating() {
        return clients.http("pets", "http://localhost:" + port + "/api");
    }

    @Test
    void decodesResponses() {
        for (var client : List.of(plain(), validating())) {
            assertThat(client.baseUrl()).isEqualTo("http://localhost:" + port + "/api");
            var listed = client.exchange(ClientCall.of("GET", "/pets").param(LIMIT, 2), response -> response.buffered()
                .map(buffered -> buffered.status() + ":" + response.header("X-Total", Integer.class) + ":" + response.header("X-Ids",
                    new TypeReference<List<Integer>>() { }) + ":" + response.contentType().orElseThrow() + ":"
                    + buffered.body(new TypeReference<List<Map<String, Object>>>() { }))).block();
            assertThat(listed).isEqualTo("200:2:[1, 2]:application/json:[{id=1, name=Rex}]");
            assertThat(client.exchange(ClientCall.of("GET", "/pets/404"), response -> response.buffered().map(buffered -> buffered.status())).block())
                .isEqualTo(404);
            var created = client.exchange(ClientCall.of("POST", "/pets").param(KEY, "k").body("application/json", Map.of("name", "Rex")),
                response -> response.buffered().map(buffered -> buffered.status())).block();
            assertThat(created).isEqualTo(201);
            assertThat(client.exchange(ClientCall.of("GET", "/events").param(FORMAT, "json").param(COUNT, 2).accept("application/json"),
                response -> response.items(EVENT).collectList()).block()).containsExactly(Map.of("n", 0), Map.of("n", 1));
            assertThat(client.exchange(ClientCall.of("GET", "/events").param(FORMAT, "sse").param(COUNT, 2).accept("text/event-stream"),
                response -> response.items(new TypeReference<Map<String, String>>() { }).collectList()).block())
                .containsExactly(Map.of("event", "tick", "data", "0"), Map.of("event", "tick", "data", "1"));
        }
        var adopted = validating().to("http://localhost:" + port + "/hooks/adopted").exchange(ClientCall.of("POST", "").operation("petAdopted")
            .body("application/json", Map.of("name", "Rex")), response -> response.buffered().map(buffered -> buffered.body(new TypeReference<Map<String, String>>() { }))).block();
        assertThat(adopted).isEqualTo(Map.of("name", "Rex"));
    }

    @Test
    void responsesArriveItemByItem() {
        for (var client : List.of(plain(), validating())) {
            var call = ClientCall.of("GET", "/events").param(FORMAT, "slow").param(COUNT, 3).accept("application/jsonl");
            var arrivals = client.exchange(call, response -> response.items(EVENT).map(item -> System.nanoTime()).collectList()).block();
            assertThat(Duration.ofNanos(arrivals.getLast() - arrivals.getFirst())).isGreaterThan(Duration.ofMillis(400));
        }
    }

    @Test
    void requestsAreSentItemByItem() {
        for (var client : List.of(plain(), validating())) {
            var events = Flux.interval(Duration.ofMillis(300)).take(3).map(i -> Map.of("n", i));
            var call = ClientCall.of("POST", "/events").param(MODE, "times").body("application/jsonl", events);
            var spread = client.exchange(call, response -> response.buffered().map(buffered -> buffered.body(Integer.class))).block();
            assertThat(spread).isGreaterThan(400);
        }
        var sum = plain().exchange(ClientCall.of("POST", "/events").body("application/x-ndjson", Stream.of(Map.of("n", 2), Map.of("n", 3))),
            response -> response.buffered().map(buffered -> buffered.body(Integer.class))).block();
        assertThat(sum).isEqualTo(5);
        var collected = plain().exchange(ClientCall.of("POST", "/events").body("application/json", Flux.just(Map.of("n", 4), Map.of("n", 5))),
            response -> response.buffered().map(buffered -> buffered.body(Integer.class))).block();
        assertThat(collected).isEqualTo(9);
    }

    @Test
    void validatesRequestsAndResponses() {
        var client = validating();
        assertThatThrownBy(() -> client.exchange(ClientCall.of("POST", "/pets").param(KEY, "k").body("application/json", Map.of()),
            response -> response.buffered()).block())
            .isInstanceOf(ClientValidationException.class).hasMessageStartingWith("Request POST http://localhost:" + port + "/api/pets violates")
            .hasMessageContaining("request.body.schema.required");
        assertThatThrownBy(() -> client.exchange(ClientCall.of("GET", "/broken"), response -> response.buffered()).block())
            .isInstanceOf(ClientValidationException.class).hasMessageContaining("response.body.schema.required");
        var items = client.exchange(ClientCall.of("GET", "/events").param(INVALID, 1).accept("application/jsonl"), response -> response.items(EVENT)
            .onErrorResume(ClientValidationException.class, e -> Flux.just(Map.of("error", 1))).collectList()).block();
        assertThat(items).containsExactly(Map.of("n", 0), Map.of("error", 1));
        assertThatThrownBy(() -> client.exchange(ClientCall.of("GET", "/events").param(ParameterSerializer.Spec.of("headless", ParameterLocation.QUERY), true)
            .accept("application/jsonl"), response -> response.items(EVENT).collectList()).block())
            .isInstanceOf(ClientValidationException.class).hasMessageContaining("response.header.missing");
        assertThatThrownBy(() -> client.exchange(ClientCall.of("POST", "/events").body("application/jsonl", Flux.just(Map.of("n", 1), Map.of("n", -1))),
            response -> response.buffered()).block())
            .isInstanceOf(ClientValidationException.class).hasMessageContaining("request.body.schema.minimum");
        assertThatThrownBy(() -> client.exchange(ClientCall.of("GET", "/nowhere"), response -> response.buffered()).block())
            .isInstanceOf(ClientValidationException.class).hasMessageContaining("request.path.missing");
        // A sequential type the operation does not describe is parsed without validation.
        var undescribed = client.exchange(ClientCall.of("GET", "/events").param(FORMAT, "mixed").accept("multipart/mixed"),
            response -> response.items(Object.class).count()).block();
        assertThat(undescribed).isEqualTo(2);
    }

    @Test
    void coversTheEdgesOfSending() {
        assertThat(clients.http("configured", "http://default.example.com").baseUrl()).isEqualTo("http://configured.example.com");
        var mixed = plain().exchange(ClientCall.of("POST", "/events").body("multipart/mixed", Flux.just(Map.of("n", 1),
            FilePart.of("f.png", "image/png", new byte[] {65}))), response -> response.buffered().map(buffered -> buffered.status()))
            .block();
        assertThat(mixed).isEqualTo(415);
        assertThatThrownBy(() -> new OpenApiWebClient(WebClient.create(), "http://localhost:1", MAPPER).exchange(ClientCall.of("GET", "/x"),
            response -> response.buffered()).block()).isInstanceOf(WebClientRequestException.class);
        var client = validating();
        assertThatThrownBy(() -> client.exchange(ClientCall.of("POST", "/pets").param(KEY, "k").body("application/json", new byte[0]),
            response -> response.buffered()).block()).isInstanceOf(ClientValidationException.class).hasMessageContaining("request.body.missing");
        assertThat(client.exchange(ClientCall.of("GET", "/pets").operation("listPets"), response -> response.buffered().map(buffered -> buffered.status()))
            .block()).isEqualTo(200);
        assertThatThrownBy(() -> client.to("http://localhost:" + port + "/hooks/adopted").exchange(ClientCall.of("POST", "").operation("petAdopted")
            .body("application/jsonl", Flux.just(Map.of("name", "Rex"))), response -> response.buffered()).block())
            .isInstanceOf(ClientValidationException.class).hasMessageContaining("request.contentType.notAllowed");
        // Logging instead of failing, an undescribed sequential response is parsed as it is.
        var logging = new OpenApiWebClient(WebClient.create(), "http://localhost:" + port + "/api", MAPPER, runtimes.get("pets").validator(), false);
        assertThat(logging.exchange(ClientCall.of("GET", "/pets/3"), response -> response.items(new TypeReference<Map<String, String>>() { }).collectList())
            .block()).containsExactly(Map.of("name", "Rex"));
        assertThat(logging.exchange(ClientCall.of("GET", "/broken"), response -> response.buffered().map(buffered -> buffered.status())).block())
            .isEqualTo(200);
    }

    @Test
    void decodesItemsWithoutValidation() {
        var lines = new ReactiveClientResponse(200, Headers.of("Content-Type", "application/jsonl"), body("{\"n\":1}\n{\n"), MAPPER, null);
        assertThatThrownBy(() -> lines.items(EVENT).collectList().block()).isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("Cannot decode application/jsonl content: item 0 must be valid JSON");
        var untyped = new ReactiveClientResponse(204, Headers.EMPTY, Flux.empty(), MAPPER, null);
        assertThat(untyped.items(Integer.class).collectList().block()).isEmpty();
        assertThat(untyped.header("X-Missing", String.class)).isNull();
        var delimited = new ReactiveClientResponse(200, Headers.of("X-Codes", "1|2"), Flux.empty(), MAPPER, null);
        assertThat(delimited.hasContentType("text/plain")).isFalse();
        assertThat(new ReactiveClientResponse(200, Headers.of("Content-Type", "text/plain"), Flux.empty(), MAPPER, null).hasContentType("text/*")).isTrue();
        assertThat(delimited.header("X-Codes", Integer[].class, "|")).containsExactly(1, 2);
        assertThat(delimited.header("X-Codes", new TypeReference<List<Integer>>() { }, "|")).containsExactly(1, 2);
        assertThat(untyped.contentType()).isEmpty();
        assertThat(untyped.headers()).isEqualTo(Headers.EMPTY);
        assertThat(untyped.status()).isEqualTo(204);
    }

    private static Flux<DataBuffer> body(String text) {
        return Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decodersSeeEveryStatus() {
        var status = plain().exchange(ClientCall.of("GET", "/pets").param(LIMIT, 500), response -> response.buffered().then(Mono.just(response.status())))
            .block();
        assertThat(status).isEqualTo(400);
    }
}
