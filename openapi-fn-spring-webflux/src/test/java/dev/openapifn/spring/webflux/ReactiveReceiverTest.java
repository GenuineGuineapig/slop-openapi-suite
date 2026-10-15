package dev.openapifn.spring.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.http.HttpRequest;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.validation.OpenApiValidator;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Webhooks sent to one URL with one method are told apart by their requests, which are read once. */
class ReactiveReceiverTest {

    private static final ReactiveRuntime RUNTIME = runtime();

    private static ReactiveRuntime runtime() {
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
            openapi: 3.2.0
            info: {title: t, version: v}
            webhooks:
              shipped:
                post:
                  operationId: shipped
                  requestBody: {required: true, content: {application/json: {schema: {type: object, required: [trackingId]}}}}
                  responses: {'204': {description: ok}}
              cancelled:
                post:
                  operationId: cancelled
                  requestBody: {required: true, content: {application/json: {schema: {type: object, required: [reason]}}}}
                  responses: {'204': {description: ok}}
              events:
                post:
                  operationId: events
                  requestBody: {content: {application/jsonl: {itemSchema: {type: object}}}}
                  responses: {'204': {description: ok}}
            """).orElseThrow(d -> new AssertionError(d.toString()));
        return new ReactiveRuntime(new OpenApiRuntime("orders", api, OpenApiValidator.of(api), JsonMapper.builder().build(), true, List.of(
            Map.entry("/hooks/orders", "shipped"), Map.entry("/hooks/orders", "cancelled"), Map.entry("/hooks/events", "events"))));
    }

    @Test
    void receiversAreChosenByRequest() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/hooks/orders").header("Content-Type", "application/json")
            .body("{\"reason\": \"late\"}"));
        assertThat(RUNTIME.receiver(exchange, "shipped", "cancelled").block()).isEqualTo("cancelled");
        assertThat(exchange.<Object>getAttribute(ReactiveRuntime.MESSAGE_ATTRIBUTE)).isInstanceOf(HttpRequest.class);
        assertThat(RUNTIME.receiver(exchange, "shipped", "cancelled").block()).as("from the message read before").isEqualTo("cancelled");
        assertThat(RUNTIME.bind(exchange, "cancelled").block().body(new TypeReference<Map<String, String>>() { })).containsEntry("reason", "late");
        var streamed = MockServerWebExchange.from(MockServerHttpRequest.post("/hooks/events").header("Content-Type", "application/jsonl").body("{}\n"));
        assertThat(RUNTIME.receiver(streamed, "events").block()).isEqualTo("events");
        assertThat(streamed.<Object>getAttribute(ReactiveRuntime.MESSAGE_ATTRIBUTE)).isNull();
    }
}
