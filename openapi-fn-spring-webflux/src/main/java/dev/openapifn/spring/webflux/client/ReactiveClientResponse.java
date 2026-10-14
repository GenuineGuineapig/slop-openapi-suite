package dev.openapifn.spring.webflux.client;

import dev.openapifn.http.BodyParser;
import dev.openapifn.http.ContentType;
import dev.openapifn.http.Headers;
import dev.openapifn.http.ItemFramer;
import dev.openapifn.http.SchemaShapes;
import dev.openapifn.json.JsonValue;
import dev.openapifn.model.MediaType;
import dev.openapifn.schema.SchemaRegistry;
import dev.openapifn.spring.JacksonJson;
import dev.openapifn.spring.client.ClientResponse;
import dev.openapifn.spring.webflux.ReactiveMessages;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * A response received by an {@link OpenApiWebClient}: status and headers at once, the body on
 * demand, either {@linkplain #buffered() whole} or {@linkplain #items item by item}. Its body must
 * be consumed one of these ways.
 */
public final class ReactiveClientResponse {

    private static final BodyParser PARSER = new BodyParser(new SchemaShapes(SchemaRegistry.empty()));

    /** Validation of a response, as it is buffered or streamed. */
    interface Validation {

        /** Checks the whole response. */
        void whole(int status, Headers headers, byte[] body);

        /** Checks the head of a streamed response; returns the parser (and validator) of its items. */
        Function<ItemFramer.Frame, Optional<JsonValue>> items(int status, Headers headers, ContentType contentType);
    }

    private final int status;
    private final Headers headers;
    private final Flux<DataBuffer> body;
    private final JsonMapper mapper;
    private final @Nullable Validation validation;

    ReactiveClientResponse(int status, Headers headers, Flux<DataBuffer> body, JsonMapper mapper, @Nullable Validation validation) {
        this.status = status;
        this.headers = headers;
        this.body = body;
        this.mapper = mapper;
        this.validation = validation;
    }

    public int status() {
        return status;
    }

    public Headers headers() {
        return headers;
    }

    public Optional<String> contentType() {
        return headers.first("Content-Type");
    }

    /** Whether the {@code Content-Type} is one of {@code mediaType}, see {@link ClientResponse#hasContentType}. */
    public boolean hasContentType(String mediaType) {
        return head().hasContentType(mediaType);
    }

    /** The header {@code name} converted to {@code type}, see {@link ClientResponse#header(String, Class)}. */
    public <T> @Nullable T header(String name, Class<T> type) {
        return head().header(name, type);
    }

    public <T> @Nullable T header(String name, TypeReference<T> type) {
        return head().header(name, type);
    }

    /** A header whose list items are separated by {@code delimiter}, see {@link ClientResponse#header(String, Class, String)}. */
    public <T> @Nullable T header(String name, Class<T> type, String delimiter) {
        return head().header(name, type, delimiter);
    }

    public <T> @Nullable T header(String name, TypeReference<T> type, String delimiter) {
        return head().header(name, type, delimiter);
    }

    private ClientResponse head() {
        return new ClientResponse(status, headers, new byte[0], mapper);
    }

    /** The response with its whole body, read (and validated) now. */
    public Mono<ClientResponse> buffered() {
        return DataBufferUtils.join(body).map(ReactiveMessages::bytes).defaultIfEmpty(new byte[0]).map(bytes -> {
            if (validation != null) {
                validation.whole(status, headers, bytes);
            }
            return new ClientResponse(status, headers, bytes, mapper);
        });
    }

    /**
     * The items of a body of a sequential media type (see {@link ItemFramer}), converted to
     * {@code type} and validated as they arrive; other bodies are decoded as an array of items.
     */
    public <T> Flux<T> items(Class<T> type) {
        return items(mapper.constructType(type), buffered -> buffered.items(type));
    }

    public <T> Flux<T> items(TypeReference<T> type) {
        return items(mapper.constructType(type), buffered -> buffered.items(type));
    }

    private <T> Flux<T> items(JavaType type, Function<ClientResponse, java.util.stream.Stream<T>> whole) {
        var sequential = contentType().flatMap(ContentType::parse).filter(ItemFramer::isSequential);
        if (sequential.isEmpty()) {
            return buffered().flatMapMany(response -> Flux.fromStream(whole.apply(response)));
        }
        var contentType = sequential.get();
        return Flux.defer(() -> {
            var framer = ItemFramer.of(contentType).orElseThrow();
            Function<ItemFramer.Frame, Optional<JsonValue>> parse = validation != null ? validation.items(status, headers, contentType)
                : frame -> PARSER.item(frame, contentType, MediaType.EMPTY, 0).orElseThrow(error ->
                    new IllegalStateException("Cannot decode " + contentType.essence() + " content: " + error));
            return body.concatMapIterable(buffer -> framer.feed(ReactiveMessages.bytes(buffer)))
                .concatWith(Mono.fromSupplier(framer::finish))
                .concatMap(frame -> Mono.justOrEmpty(parse.apply(frame)))
                .map(value -> mapper.<T>treeToValue(JacksonJson.toJsonNode(value), type));
        });
    }
}
