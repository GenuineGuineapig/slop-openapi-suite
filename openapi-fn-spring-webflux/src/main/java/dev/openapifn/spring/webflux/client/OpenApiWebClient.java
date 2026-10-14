package dev.openapifn.spring.webflux.client;

import dev.openapifn.http.BodyParser;
import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.ContentType;
import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.http.ItemFramer;
import dev.openapifn.http.SchemaShapes;
import dev.openapifn.json.JsonValue;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.model.MediaType;
import dev.openapifn.schema.SchemaRegistry;
import dev.openapifn.spring.client.ClientCall;
import dev.openapifn.spring.client.ClientCodec;
import dev.openapifn.spring.client.ClientValidationException;
import dev.openapifn.spring.client.OpenApiHttpClient;
import dev.openapifn.spring.webflux.ReactiveMessages;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OpenApiValidator.RequestValidation;
import dev.openapifn.validation.OperationMatch;
import dev.openapifn.validation.ValidationReport;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends {@link ClientCall}s with a {@link WebClient} and hands the {@link ReactiveClientResponse}
 * to a decoder; the reactive counterpart of {@link OpenApiHttpClient}. Every status is passed to
 * the decoder: described error responses are results, not errors. A {@link Publisher} (or
 * {@link Stream}) body of a sequential media type is sent item by item.
 *
 * <p>With a validator, requests are validated before they are sent (a streamed body item by item
 * as it is produced) and responses as they are buffered or streamed: violations are
 * {@link ClientValidationException}s for requests, and for responses too unless they are only
 * logged.
 */
public final class OpenApiWebClient {

    private static final Log LOG = LogFactory.getLog(OpenApiWebClient.class);
    private static final BodyParser PARSER = new BodyParser(new SchemaShapes(SchemaRegistry.empty()));

    private final WebClient webClient;
    private final String baseUrl;
    private final JsonMapper mapper;
    private final ClientCodec codec;
    private final @Nullable OpenApiValidator validator;
    private final boolean failOnInvalidResponse;

    public OpenApiWebClient(WebClient webClient, String baseUrl, JsonMapper mapper) {
        this(webClient, baseUrl, mapper, null, true);
    }

    /**
     * @param validator validates requests and responses, if given
     * @param failOnInvalidResponse whether invalid responses are errors (else they are logged)
     */
    public OpenApiWebClient(WebClient webClient, String baseUrl, JsonMapper mapper, @Nullable OpenApiValidator validator, boolean failOnInvalidResponse) {
        this.webClient = webClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
        this.codec = new ClientCodec(mapper);
        this.validator = validator;
        this.failOnInvalidResponse = failOnInvalidResponse;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** A client sending to {@code baseUrl} instead, e.g. the URL a webhook subscriber or callback registered. */
    public OpenApiWebClient to(String baseUrl) {
        return new OpenApiWebClient(webClient, baseUrl, mapper, validator, failOnInvalidResponse);
    }

    /** Sends {@code call} and decodes the response. */
    public <R> Mono<R> exchange(ClientCall call, Function<ReactiveClientResponse, Mono<R>> decoder) {
        return Mono.defer(() -> {
            var body = call.body();
            if (body == null) {
                return send(call, Optional.empty(), null, decoder);
            }
            var source = body.value() instanceof Publisher<?> publisher ? Flux.from(publisher)
                : body.value() instanceof Stream<?> stream ? Flux.fromStream(stream) : null;
            var itemType = source == null ? Optional.<ContentType>empty() : codec.itemType(body);
            if (itemType.isPresent()) {
                return send(call, itemType, source, decoder);
            }
            if (source != null) {
                return source.collectList().flatMap(list -> send(call.body(body.contentType(), list), Optional.empty(), null, decoder));
            }
            return send(call, Optional.empty(), null, decoder);
        });
    }

    /** Sends a call whose body is encoded whole, or streamed when {@code itemType} is present. */
    private <R> Mono<R> send(ClientCall call, Optional<ContentType> itemType, @Nullable Flux<?> items, Function<ReactiveClientResponse, Mono<R>> decoder) {
        var uri = codec.uri(baseUrl, call);
        var headers = codec.headers(call);
        var encoded = itemType.isEmpty() && call.body() != null ? codec.encode(call.body()) : null;
        if (encoded != null) {
            headers = headers.with("Content-Type", encoded.contentType());
        }
        if (itemType.isPresent()) {
            headers = headers.with("Content-Type", itemType.get().toString());
        }
        var match = validateRequest(call, uri, headers, encoded == null ? null : encoded.body(), itemType.isPresent());
        var request = webClient.method(HttpMethod.valueOf(call.method())).uri(uri);
        headers.values().forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        if (encoded != null) {
            request.body(BodyInserters.fromValue(encoded.body()));
        }
        if (itemType.isPresent()) {
            var type = itemType.get();
            var validated = validatedItems(match, type, java.util.Objects.requireNonNull(items).map(item -> codec.encodeItem(item, type)));
            var end = BodyWriter.end(type);
            request.body(BodyInserters.fromDataBuffers(validated.concatWith(end.length == 0 ? Flux.empty() : Flux.just(end))
                .map(bytes -> (DataBuffer) DefaultDataBufferFactory.sharedInstance.wrap(bytes))));
        }
        return request.retrieve().onStatus(status -> true, response -> Mono.empty()).toEntityFlux(DataBuffer.class)
            .onErrorMap(WebClientRequestException.class, e -> e.getCause() instanceof ClientValidationException invalid ? invalid : e)
            .flatMap(entity -> decoder.apply(new ReactiveClientResponse(entity.getStatusCode().value(), ReactiveMessages.headers(entity.getHeaders()),
                java.util.Objects.requireNonNullElse(entity.getBody(), Flux.<DataBuffer>empty()), mapper, match.map(this::validation).orElse(null))));
    }

    /** Validates a request (or its head, for a streamed body), throwing on violations; the matched operation when validating. */
    private Optional<OperationMatch> validateRequest(ClientCall call, URI uri, Headers headers, byte @Nullable [] body, boolean head) {
        if (validator == null) {
            return Optional.empty();
        }
        var message = new HttpRequest(call.method(), uri.getRawPath(), Optional.ofNullable(uri.getRawQuery()).orElse(""), headers,
            Optional.ofNullable(body).filter(bytes -> bytes.length > 0));
        var operation = Optional.ofNullable(call.operationId()).flatMap(id -> validator.api().operation(id)).filter(op -> op.kind() != ApiOperation.Kind.PATH);
        RequestValidation validation = operation.map(op -> new OperationMatch(op, Map.of()))
            .map(match -> head ? validator.validateRequestHead(match, message) : validator.validateRequest(match, message))
            .orElseGet(() -> head ? validator.validateRequestHead(message) : validator.validateRequest(message));
        if (validation.report().hasErrors()) {
            throw new ClientValidationException("Request " + call.method() + " " + uri + " violates the API description", validation.report());
        }
        return validation.match();
    }

    /** The encoded items of a streamed request body, validated as they are produced when validating. */
    private Flux<byte[]> validatedItems(Optional<OperationMatch> match, ContentType type, Flux<byte[]> items) {
        var validator = match.flatMap(m -> java.util.Objects.requireNonNull(this.validator).requestItems(m, type.toString()));
        if (validator.isEmpty()) {
            return items;
        }
        var framer = validator.get().framer();
        return items.doOnNext(bytes -> framer.feed(bytes).forEach(frame -> {
            var report = validator.get().validate(frame).report();
            if (report.hasErrors()) {
                throw new ClientValidationException("A request item violates the API description", report);
            }
        }));
    }

    private ReactiveClientResponse.Validation validation(OperationMatch match) {
        var validator = java.util.Objects.requireNonNull(this.validator);
        return new ReactiveClientResponse.Validation() {
            @Override
            public void whole(int status, Headers headers, byte[] body) {
                check(validator.validateResponse(match, new HttpResponse(status, headers, Optional.of(body).filter(bytes -> bytes.length > 0))));
            }

            @Override
            public Function<ItemFramer.Frame, Optional<JsonValue>> items(int status, Headers headers, ContentType contentType) {
                check(validator.validateResponseHead(match, new HttpResponse(status, headers, Optional.empty())));
                var items = validator.responseItems(match, status, contentType.toString());
                if (items.isEmpty()) {
                    return frame -> PARSER.item(frame, contentType, MediaType.EMPTY, 0).toOptional().flatMap(Function.identity());
                }
                return frame -> {
                    var item = items.get().validate(frame);
                    check(item.report());
                    return item.value();
                };
            }
        };
    }

    /** Throws or logs the violations of a response (or of an item of it). */
    private void check(ValidationReport report) {
        if (report.hasErrors() && failOnInvalidResponse) {
            throw new ClientValidationException("A response violates the API description", report);
        }
        if (!report.isEmpty()) {
            LOG.warn("A response violates the API description:\n" + report);
        }
    }
}
