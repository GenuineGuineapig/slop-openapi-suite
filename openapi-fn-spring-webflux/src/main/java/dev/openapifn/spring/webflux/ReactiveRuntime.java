package dev.openapifn.spring.webflux;

import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.ContentType;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.ItemFramer;
import dev.openapifn.spring.ApiResponse;
import dev.openapifn.spring.ItemStreams;
import dev.openapifn.spring.OpenApiRuntime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Connects an {@link OpenApiRuntime} to Spring WebFlux: generated reactive controllers bind
 * requests and write their responses through it.
 */
public final class ReactiveRuntime {

    /** Exchange attribute under which the validation filter stores the request it read, body included. */
    public static final String MESSAGE_ATTRIBUTE = ReactiveRuntime.class.getName() + ".message";

    private final OpenApiRuntime runtime;

    public ReactiveRuntime(OpenApiRuntime runtime) {
        this.runtime = runtime;
    }

    public OpenApiRuntime runtime() {
        return runtime;
    }

    /**
     * Validates and parses the request of {@code exchange} for the operation {@code operationId},
     * reusing the validation filter's result when present. The body is read first, unless it is of
     * a sequential media type: {@link ReactiveBinding#items} streams it.
     *
     * @return the binding, or an {@link dev.openapifn.spring.OpenApiValidationException} when the request is invalid
     *     (and failing is enabled)
     */
    public Mono<ReactiveBinding> bind(ServerWebExchange exchange, String operationId) {
        var request = exchange.getRequest();
        var contentType = request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        var streamed = ItemStreams.sequential(contentType);
        var read = exchange.<Object>getAttribute(MESSAGE_ATTRIBUTE);
        var message = streamed ? Mono.just(ReactiveMessages.head(request))
            : read instanceof HttpRequest cached ? Mono.just(cached) : ReactiveMessages.read(request);
        var pattern = Optional.ofNullable(exchange.<Object>getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).map(Object::toString).orElse(null);
        var validated = exchange.<Object>getAttribute(OpenApiRuntime.VALIDATION_ATTRIBUTE);
        return message.map(m -> {
            var validation = runtime.validate(m, operationId, pattern, validated, streamed);
            var items = streamed ? new ReactiveBinding.Items(request.getBody(), ContentType.parse(Objects.requireNonNull(contentType)).orElseThrow(),
                runtime.validator().requestItems(validation.match().orElseThrow(), contentType), runtime.failsOnInvalidRequests()) : null;
            return new ReactiveBinding(runtime.binding(validation, m), runtime.mapper(), items);
        });
    }

    /**
     * Which of {@code operationIds} (webhooks or callbacks a controller receives at one path with one
     * method) the request of {@code exchange} is, see {@link OpenApiRuntime#receiver}. The body is
     * read once: the binding reuses it.
     */
    public Mono<String> receiver(ServerWebExchange exchange, String... operationIds) {
        var request = exchange.getRequest();
        var streamed = ItemStreams.sequential(request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        var read = exchange.<Object>getAttribute(MESSAGE_ATTRIBUTE);
        var message = streamed ? Mono.just(ReactiveMessages.head(request))
            : read instanceof HttpRequest cached ? Mono.just(cached) : ReactiveMessages.read(request).doOnNext(m -> exchange.getAttributes().put(MESSAGE_ATTRIBUTE, m));
        var validated = exchange.<Object>getAttribute(OpenApiRuntime.VALIDATION_ATTRIBUTE);
        return message.map(m -> runtime.receiver(m, validated, streamed, List.of(operationIds)));
    }

    /**
     * Writes {@code response} of operation {@code operationId} to the exchange. A {@link Publisher}
     * (or {@link Stream}) body of a sequential media type is written item by item, flushing each;
     * other publishers are collected and written whole.
     */
    public Mono<Void> respond(ServerWebExchange exchange, String operationId, ApiResponse response) {
        var reply = runtime.reply(exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT), operationId, response);
        var out = exchange.getResponse();
        out.setStatusCode(HttpStatusCode.valueOf(reply.status()));
        reply.headers().forEach((name, values) -> out.getHeaders().put(name, values));
        var body = reply.body();
        if (body == null) {
            return out.setComplete();
        }
        var type = Objects.requireNonNull(reply.contentType());
        Flux<?> items = body instanceof Publisher<?> publisher ? Flux.from(publisher) : body instanceof Stream<?> stream ? Flux.fromStream(stream) : null;
        if (items == null) {
            return write(out, runtime.encode(reply));
        }
        if (!ItemFramer.isSequential(type)) {
            return items.collectList().flatMap(list -> write(out, runtime.encode(new OpenApiRuntime.Reply(reply.status(), reply.headers(), type,
                reply.schema(), list))));
        }
        var end = BodyWriter.end(type);
        Flux<Mono<DataBuffer>> encoded = items.map(item -> Mono.just(out.bufferFactory().wrap(runtime.encodeItem(item, type))));
        return out.writeAndFlushWith(end.length == 0 ? encoded : encoded.concatWith(Mono.just(Mono.just(out.bufferFactory().wrap(end)))));
    }

    private static Mono<Void> write(ServerHttpResponse out, byte[] body) {
        return out.writeWith(Mono.just(out.bufferFactory().wrap(body)));
    }
}
