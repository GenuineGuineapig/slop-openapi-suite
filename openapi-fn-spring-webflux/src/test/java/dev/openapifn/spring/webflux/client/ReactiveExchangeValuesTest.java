package dev.openapifn.spring.webflux.client;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonString;
import dev.openapifn.spring.client.ClientCall;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

class ReactiveExchangeValuesTest {

    private static final OpenApiWebClient CLIENT = new OpenApiWebClient(WebClient.create(), "https://api.example.com/", JsonMapper.builder().build());

    @Test
    void streamedBodiesSelectNothing() {
        var streamed = ClientCall.of("POST", "/events").body("application/jsonl", Flux.just(1, 2));
        var values = CLIENT.values(streamed, 200, Flux.just(3), "X-Count", 2);
        assertThat(values.context().url()).isEqualTo("https://api.example.com/events");
        assertThat(values.context().requestBody()).isEmpty();
        assertThat(values.context().responseBody()).isEmpty();
        assertThat(values.context().responseHeaders()).containsEntry("x-count", JsonNumber.of(2));
        assertThat(CLIENT.values(streamed).context().requestBody()).isEmpty();
    }

    @Test
    void otherBodiesAreSelectedFrom() {
        var call = ClientCall.of("POST", "/pets").body("application/json", Map.of("name", "Rex"));
        var values = CLIENT.values(call, 201, Map.of("id", 7));
        assertThat(values.value("\"$request.body#/name\"", String.class)).isEqualTo("Rex");
        assertThat(values.value("\"$response.body#/id\"", Long.class)).isEqualTo(7L);
        assertThat(CLIENT.values(ClientCall.of("GET", "/pets")).context().requestBody()).isEmpty();
        assertThat(CLIENT.values(call).context().requestBody()).contains(new JsonObject(Map.of("name", JsonString.of("Rex"))));
    }
}
