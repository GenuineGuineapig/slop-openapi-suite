package dev.openapifn.spring.webflux.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.spring.client.ClientCall;
import dev.openapifn.spring.client.ClientValidationException;
import dev.openapifn.validation.OpenApiValidator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Streamed request bodies are validated item by item, and as a whole at their end. */
class ReactiveRequestStreamTest {

    private static final OpenApiValidator VALIDATOR = OpenApiValidator.of(new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(
        URI.create("mem:/api.yaml"), """
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /events:
                post:
                  requestBody:
                    required: true
                    content:
                      application/jsonl:
                        itemSchema: {type: object, required: [n]}
                        schema: {type: array, minItems: 2}
                      multipart/mixed:
                        itemSchema: {type: object, required: [n]}
                  responses:
                    '204': {description: received}
            """).orElseThrow(d -> new AssertionError(d.toString())));

    private final List<String> sent = new ArrayList<>();

    private OpenApiWebClient client() {
        var web = WebClient.builder().exchangeFunction(request -> {
            var mock = new MockClientHttpRequest(request.method(), request.url());
            return request.writeTo(mock, ExchangeStrategies.withDefaults())
                .then(Mono.defer(() -> mock.getBodyAsString().doOnNext(sent::add)))
                .then(Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build()));
        }).build();
        return new OpenApiWebClient(web, "https://h", JsonMapper.builder().build(), VALIDATOR, true);
    }

    private Integer send(String contentType, Flux<?> items) {
        return client().<Integer>exchange(ClientCall.of("POST", "/events").body(contentType, items),
            response -> response.buffered().map(buffered -> buffered.status())).block();
    }

    @Test
    void sequencesAreCheckedAtTheirEnd() {
        assertThat(send("application/jsonl", Flux.just(Map.of("n", 1), Map.of("n", 2)))).isEqualTo(204);
        assertThat(sent).containsExactly("{\"n\":1}\n{\"n\":2}\n");
        assertThatThrownBy(() -> send("application/jsonl", Flux.just(Map.of("n", 1)))).isInstanceOf(ClientValidationException.class)
            .hasMessageContaining("request.body.schema.minItems");
        assertThatThrownBy(() -> send("application/jsonl", Flux.just(Map.of("n", 1), Map.of()))).isInstanceOf(ClientValidationException.class)
            .hasMessageContaining("request.body.schema.required");
    }

    @Test
    void multipartItemsEndWithTheNextDelimiter() {
        assertThat(send("multipart/mixed", Flux.just(Map.of("n", 1), Map.of("n", 2)))).isEqualTo(204);
        assertThat(sent.getLast()).contains("{\"n\":1}", "{\"n\":2}").endsWith("--\r\n");
        assertThat(sent.getLast().getBytes(StandardCharsets.UTF_8).length).isPositive();
    }
}
