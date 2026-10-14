package dev.openapifn.spring.webflux;

import dev.openapifn.http.ContentType;
import dev.openapifn.http.ItemFramer;
import dev.openapifn.json.JsonValue;
import dev.openapifn.spring.Accept;
import dev.openapifn.spring.ItemStreams;
import dev.openapifn.spring.JacksonJson;
import dev.openapifn.spring.OpenApiValidationException;
import dev.openapifn.spring.RequestBinding;
import dev.openapifn.validation.Level;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OpenApiValidator.RequestValidation;
import dev.openapifn.validation.ValidationIssue;
import dev.openapifn.validation.ValidationReport;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parameters and body of a validated request in a WebFlux application, converted to Java
 * types on demand. Bodies of sequential media types are streamed: {@link #items} reads and
 * validates them item by item as the {@link Flux} is consumed.
 */
public final class ReactiveBinding {

    /**
     * A streamed body.
     *
     * @param body its content
     * @param contentType its content type, a sequential one
     * @param validator the validator of its items, when its media type is described
     * @param failOnInvalid whether an invalid item ends the flux with an {@link OpenApiValidationException}
     */
    record Items(Flux<DataBuffer> body, ContentType contentType, Optional<OpenApiValidator.ItemValidator> validator, boolean failOnInvalid) {
    }

    private final RequestBinding binding;
    private final JsonMapper mapper;
    private final @Nullable Items streamed;

    ReactiveBinding(RequestBinding binding, JsonMapper mapper, @Nullable Items streamed) {
        this.binding = binding;
        this.mapper = mapper;
        this.streamed = streamed;
    }

    public RequestValidation validation() {
        return binding.validation();
    }

    /** The parameter {@code name} in {@code in} ({@code path}, {@code query}, ...), or {@code null} when absent. */
    public <T> @Nullable T parameter(String in, String name, Class<T> type) {
        return binding.parameter(in, name, type);
    }

    public <T> @Nullable T parameter(String in, String name, TypeReference<T> type) {
        return binding.parameter(in, name, type);
    }

    /** The media types the client accepts. */
    public Accept accept() {
        return binding.accept();
    }

    /** The request body as {@code type} if it is of {@code mediaType}, else {@code null}; see {@link RequestBinding#body(String, Class)}. */
    public <T> @Nullable T body(String mediaType, Class<T> type) {
        return binding.body(mediaType, type);
    }

    public <T> @Nullable T body(String mediaType, TypeReference<T> type) {
        return binding.body(mediaType, type);
    }

    /** The items of the body if it is of {@code mediaType}, else {@code null}; see {@link #items(Class)}. */
    public <T> @Nullable Flux<T> items(String mediaType, Class<T> type) {
        return binding.hasContentType(mediaType) ? items(type) : null;
    }

    public <T> @Nullable Flux<T> items(String mediaType, TypeReference<T> type) {
        return binding.hasContentType(mediaType) ? items(type) : null;
    }

    /** The request body, or {@code null} when there is none; a streamed body is read with {@link #items}. */
    public <T> @Nullable T body(Class<T> type) {
        return binding.body(type);
    }

    public <T> @Nullable T body(TypeReference<T> type) {
        return binding.body(type);
    }

    /**
     * The items of the body, converted to {@code type}. A streamed body (of a sequential media type)
     * is read as the flux is consumed, and each item is validated as it arrives: an invalid item
     * ends the flux with an {@link OpenApiValidationException} (when failing on invalid requests is
     * enabled). Other bodies are read as an array of items.
     */
    public <T> Flux<T> items(Class<T> type) {
        return items(mapper.constructType(type), () -> binding.items(type));
    }

    public <T> Flux<T> items(TypeReference<T> type) {
        return items(mapper.constructType(type), () -> binding.items(type));
    }

    private <T> Flux<T> items(JavaType type, Supplier<Stream<? extends T>> whole) {
        var items = streamed;
        if (items == null) {
            return Flux.fromStream(whole);
        }
        return Flux.defer(() -> {
            var framer = ItemFramer.of(items.contentType()).orElseThrow();
            var parse = ItemStreams.requestItems(items.contentType(), items.validator(), items.failOnInvalid());
            return items.body().concatMapIterable(buffer -> framer.feed(ReactiveMessages.bytes(buffer)))
                .concatWith(Mono.fromSupplier(framer::finish))
                .concatMap(frame -> Mono.justOrEmpty(parse.apply(frame)))
                .map(value -> convert(value, type));
        });
    }

    private <T> T convert(JsonValue value, JavaType type) {
        try {
            return mapper.treeToValue(JacksonJson.toJsonNode(value), type);
        } catch (JacksonException e) {
            throw new OpenApiValidationException(new ValidationReport(List.of(new ValidationIssue("request.binding", Level.ERROR,
                "cannot convert to " + type.getRawClass().getSimpleName() + ": " + Optional.ofNullable(e.getOriginalMessage()).orElse(""), "body"))));
        }
    }
}
