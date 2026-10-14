package dev.openapifn.spring.webflux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.OpenApiValidationException;
import dev.openapifn.spring.ResponseMode;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OperationMatch;
import dev.openapifn.validation.ValidationReport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class ReactiveSupportTest {

    private static final OpenApiRuntime RUNTIME;

    static {
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /events:
                get:
                  operationId: events
                  responses:
                    '200':
                      description: events
                      content:
                        application/jsonl: {itemSchema: {type: object, required: [n]}}
                        text/plain: {schema: {type: string, maxLength: 3}}
                post:
                  operationId: publish
                  requestBody:
                    content:
                      application/json: {schema: {type: array, items: {type: object}}}
                  responses:
                    '204': {description: none}
            """).orElseThrow(d -> new AssertionError(d.toString()));
        RUNTIME = new OpenApiRuntime("events", api, OpenApiValidator.of(api), JsonMapper.builder().build(), true);
    }

    private static final OperationMatch MATCH = RUNTIME.validator().validateRequest(dev.openapifn.http.HttpRequest.of("GET", "/events")).match()
        .orElseThrow();

    private final List<ValidationReport> logged = new ArrayList<>();

    private ValidatingResponse response(MockServerWebExchange exchange, boolean fail) {
        return new ValidatingResponse(exchange, RUNTIME.validator(), MATCH, fail, logged::add);
    }

    private static DataBuffer buffer(String text) {
        return DefaultDataBufferFactory.sharedInstance.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validatesItemsWrittenWithoutFlushing() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/events"));
        var response = response(exchange, true);
        response.getHeaders().add("Content-Type", "application/jsonl");
        response.writeWith(Flux.just(buffer("{\"n\":1}\n{\"n\""), buffer(":2}\n{}\n{\"n\":3}\n"))).block();
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("{\"n\":1}\n{\"n\":2}\n");
        assertThat(logged).hasSize(1);
    }

    @Test
    void writesFlushedChunksOfOtherTypesWhole() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/events"));
        var response = response(exchange, true);
        response.getHeaders().add("Content-Type", "text/plain");
        response.writeAndFlushWith(Flux.just(Mono.just(buffer("ab")), Mono.just(buffer("c")))).block();
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("abc");
        var untyped = MockServerWebExchange.from(MockServerHttpRequest.get("/events"));
        response(untyped, false).writeWith(Flux.just(buffer("x"))).block();
        assertThat(logged).singleElement().satisfies(report -> assertThat(report.errors()).isNotEmpty());
        assertThat(untyped.getResponse().getBodyAsString().block()).isEqualTo("x");
    }

    @Test
    void passesRejectedAndCommittedResponsesOn() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/events"));
        exchange.getAttributes().put(OpenApiRuntime.REJECTED_ATTRIBUTE, new ValidationReport(List.of()));
        var rejected = response(exchange, true);
        rejected.setStatusCode(HttpStatus.BAD_REQUEST);
        rejected.writeAndFlushWith(Flux.just(Mono.just(buffer("problem")))).block();
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("problem");
        rejected.setComplete().block();
        var committed = MockServerWebExchange.from(MockServerHttpRequest.get("/events"));
        var response = response(committed, true);
        response.getHeaders().add("Content-Type", "text/plain");
        response.writeWith(Flux.just(buffer("ok"))).block();
        response.setComplete().block();
        assertThat(committed.getResponse().getBodyAsString().block()).isEqualTo("ok");
        assertThat(logged).isEmpty();
    }

    @Test
    void endsStreamsAfterTheValidItemsOfAChunk() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/events"));
        var response = response(exchange, true);
        response.getHeaders().add("Content-Type", "application/jsonl");
        response.writeAndFlushWith(Flux.just(Mono.just(buffer("{\"n\":1}\n{}\n{\"n\":3}\n")), Mono.just(buffer("{\"n\":4}\n")))).block();
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("{\"n\":1}\n");
        var empty = MockServerWebExchange.from(MockServerHttpRequest.get("/events"));
        var none = response(empty, true);
        none.getHeaders().add("Content-Type", "application/jsonl");
        none.writeAndFlushWith(Flux.<Mono<DataBuffer>>error(new IllegalStateException("broken"))).onErrorResume(e -> Mono.empty()).block();
        assertThat(empty.getResponse().getStatusCode()).isNull();
    }

    @Test
    void filterSettingsAndCachedBodies() {
        var filter = new OpenApiValidationWebFilter(new OpenApiRuntimes(List.of(RUNTIME)), new OpenApiValidationWebFilter.Settings(true, true,
            ResponseMode.LOG, false, List.of(), 1000, 7));
        assertThat(filter.getOrder()).isEqualTo(7);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/events").header("Content-Type", "application/json").body("[{}]"));
        var read = new ArrayList<String>();
        filter.filter(exchange, forwarded -> DataBufferUtilsJoin.text(forwarded.getRequest().getBody()).doOnNext(read::add).then(
            forwarded.getRequest().getBody().count().doOnNext(count -> read.add(count.toString())).then())).block();
        assertThat(read).containsExactly("[{}]", "1");
        var empty = MockServerWebExchange.from(MockServerHttpRequest.post("/events"));
        filter.filter(empty, forwarded -> forwarded.getRequest().getBody().count().doOnNext(count -> read.add("empty:" + count)).then()).block();
        assertThat(read).contains("empty:0");
        var lenient = new OpenApiValidationWebFilter(new OpenApiRuntimes(List.of(RUNTIME)), new OpenApiValidationWebFilter.Settings(false, true,
            ResponseMode.LOG, false, List.of(), 1000, 0));
        var unmatched = MockServerWebExchange.from(MockServerHttpRequest.patch("/events"));
        lenient.filter(unmatched, forwarded -> Mono.fromRunnable(() -> read.add("passed:" + (forwarded.getResponse() instanceof ValidatingResponse)))).block();
        assertThat(read).contains("passed:false");
    }

    @Test
    void bindsWithoutTheFilter() {
        var runtime = new ReactiveRuntime(RUNTIME);
        assertThat(runtime.runtime()).isSameAs(RUNTIME);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/events").header("Content-Type", "application/json").body("[{\"a\":1}]"));
        var binding = runtime.bind(exchange, "publish").block();
        assertThat(binding.validation().report().isEmpty()).isTrue();
        assertThat(binding.items(Map.class).collectList().block()).containsExactly(Map.of("a", 1));
        assertThatThrownBy(() -> binding.items(Integer.class).collectList().block()).isInstanceOf(OpenApiValidationException.class);
        var accepting = MockServerWebExchange.from(MockServerHttpRequest.post("/events").header("Content-Type", "application/json")
            .header("Accept", "text/plain").body("[{\"a\":2}]"));
        var typed = runtime.bind(accepting, "publish").block();
        assertThat(typed.accept().accepts("text/plain")).isTrue();
        assertThat((List<?>) typed.body("application/json", List.class)).hasSize(1);
        assertThat(typed.body("text/plain", new tools.jackson.core.type.TypeReference<List<Map<String, Integer>>>() { })).isNull();
        assertThat(typed.items("application/json", Map.class).collectList().block()).containsExactly(Map.of("a", 2));
        assertThat(typed.items("text/plain", Map.class)).isNull();
        assertThat(typed.items("application/json", new tools.jackson.core.type.TypeReference<Map<String, Integer>>() { }).collectList().block())
            .containsExactly(Map.of("a", 2));
        assertThat(typed.items("text/plain", new tools.jackson.core.type.TypeReference<Map<String, Integer>>() { })).isNull();
        var lines = MockServerWebExchange.from(MockServerHttpRequest.post("/events").header("Content-Type", "application/jsonl").body("1\n"));
        var lenient = new ReactiveRuntime(new OpenApiRuntime("events", RUNTIME.api(), RUNTIME.validator(), RUNTIME.mapper(), false));
        assertThatThrownBy(() -> lenient.bind(lines, "publish").block().items(Map.class).collectList().block())
            .isInstanceOfSatisfying(OpenApiValidationException.class, e -> assertThat(e.report().issues()).singleElement()
                .satisfies(issue -> assertThat(issue.key()).isEqualTo("request.binding")));
    }

    /** Joins a body into text. */
    private static final class DataBufferUtilsJoin {

        static Mono<String> text(Flux<DataBuffer> body) {
            return org.springframework.core.io.buffer.DataBufferUtils.join(body).map(ReactiveMessages::bytes).map(bytes -> new String(bytes,
                StandardCharsets.UTF_8));
        }
    }
}
