package dev.openapifn.spring;

import dev.openapifn.http.ContentType;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.ItemFramer;
import dev.openapifn.http.Multipart;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonValue;
import dev.openapifn.validation.Level;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OpenApiValidator.RequestValidation;
import dev.openapifn.validation.ValidationIssue;
import dev.openapifn.validation.ValidationReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The parameters and body of a validated request, converted to Java types on demand with
 * Jackson. Generated controllers construct their request records from it. Bodies of sequential
 * media types are streamed: {@link #items} reads and validates them item by item.
 */
public final class RequestBinding {

    /**
     * A streamed body.
     *
     * @param input the source of its bytes, read once
     * @param contentType its content type, a sequential one
     * @param validator the validator of its items, when its media type is described
     * @param failOnInvalid whether an invalid item ends the stream with an {@link OpenApiValidationException}
     */
    record Items(Supplier<InputStream> input, ContentType contentType, Optional<OpenApiValidator.ItemValidator> validator, boolean failOnInvalid) {
    }

    private final RequestValidation validation;
    private final HttpRequest request;
    private final JsonMapper mapper;
    private final Optional<Items> streamed;

    RequestBinding(RequestValidation validation, HttpRequest request, JsonMapper mapper) {
        this(validation, request, mapper, Optional.empty());
    }

    RequestBinding(RequestValidation validation, HttpRequest request, JsonMapper mapper, Items streamed) {
        this(validation, request, mapper, Optional.of(streamed));
    }

    private RequestBinding(RequestValidation validation, HttpRequest request, JsonMapper mapper, Optional<Items> streamed) {
        this.validation = validation;
        this.request = request;
        this.mapper = mapper;
        this.streamed = streamed;
    }

    public RequestValidation validation() {
        return validation;
    }

    /** The parameter {@code name} in {@code in} ({@code path}, {@code query}, ...), or {@code null} when absent. */
    public <T> @Nullable T parameter(String in, String name, Class<T> type) {
        return parameter(in, name, mapper.constructType(type));
    }

    public <T> @Nullable T parameter(String in, String name, TypeReference<T> type) {
        return parameter(in, name, mapper.constructType(type));
    }

    private <T> @Nullable T parameter(String in, String name, JavaType type) {
        var value = validation.parameters().get(in + "." + name);
        return value == null ? null : convert(JacksonJson.toJsonNode(value), type, in + "." + name);
    }

    /** The request body, or {@code null} when there is none. */
    public <T> @Nullable T body(Class<T> type) {
        return body(mapper.constructType(type));
    }

    public <T> @Nullable T body(TypeReference<T> type) {
        return body(mapper.constructType(type));
    }

    @SuppressWarnings("unchecked")
    private <T> @Nullable T body(JavaType type) {
        var raw = type.getRawClass();
        var whole = raw == byte[].class || raw == FilePart.class || raw == String.class;
        if (streamed.isPresent() && !whole) {
            try (var values = values(streamed.get())) {
                return convert(JacksonJson.toJsonNode(new JsonArray(values.toList())), type, "body");
            }
        }
        var bytes = streamed.map(items -> readAll(items.input().get())).filter(content -> content.length > 0).or(request::body);
        if (bytes.isEmpty()) {
            return null;
        }
        var contentType = request.contentType().flatMap(ContentType::parse);
        var json = contentType.filter(ContentType::isJson).isPresent();
        if (!json && raw == byte[].class) {
            return (T) bytes.get();
        }
        if (!json && raw == FilePart.class) {
            return (T) new FilePart(null, request.contentType().orElse(FilePart.OCTET_STREAM), bytes.get());
        }
        if (!json && raw == String.class) {
            return (T) new String(bytes.get(), StandardCharsets.UTF_8);
        }
        var value = validation.body();
        if (value.isEmpty()) {
            return null;
        }
        var node = JacksonJson.toJsonNode(value.get());
        var boundary = contentType.filter(t -> t.essence().equals("multipart/form-data")).flatMap(t -> t.parameter("boundary"));
        if (boundary.isPresent()) {
            embedFiles((ObjectNode) node, Multipart.parse(bytes.get(), boundary.get()).toOptional().orElse(List.of()));
        }
        return convert(node, type, "body");
    }

    /**
     * The items of the body, converted to {@code type}. A streamed body (of a sequential media type)
     * is read as the stream is consumed, and each item is validated as it arrives: an invalid item
     * ends the stream with an {@link OpenApiValidationException} (when failing on invalid requests
     * is enabled). Other bodies are read at once, as an array of items.
     */
    public <T> Stream<T> items(Class<T> type) {
        return items(mapper.constructType(type));
    }

    public <T> Stream<T> items(TypeReference<T> type) {
        return items(mapper.constructType(type));
    }

    private <T> Stream<T> items(JavaType type) {
        if (streamed.isPresent()) {
            return values(streamed.get()).map(value -> convert(JacksonJson.toJsonNode(value), type, "body"));
        }
        List<T> list = body(mapper.getTypeFactory().constructCollectionType(List.class, type));
        return list == null ? Stream.empty() : list.stream();
    }

    private static Stream<JsonValue> values(Items items) {
        return ItemStreams.read(items.input().get(), ItemFramer.of(items.contentType()).orElseThrow(),
            ItemStreams.requestItems(items.contentType(), items.validator(), items.failOnInvalid()));
    }

    private static byte[] readAll(InputStream input) {
        try (input) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Replaces binary parts (those with a file name or a non-text content type) by
     * {@link FilePart}s. The n-th part of a name corresponds to the n-th array element; a single
     * value keeps the first part.
     */
    private static void embedFiles(ObjectNode object, List<Multipart.Part> parts) {
        var counters = new HashMap<String, Integer>();
        for (var part : parts) {
            var name = part.name().orElse("");
            var index = counters.merge(name, 1, Integer::sum) - 1;
            var type = part.contentType().flatMap(ContentType::parse);
            if (part.filename().isEmpty() && type.filter(t -> !t.type().equals("text") && !t.isJson()).isEmpty()) {
                continue;
            }
            var file = JacksonJson.embed(new FilePart(part.filename().orElse(null), part.contentType().orElse(FilePart.OCTET_STREAM), part.body()));
            if (object.get(name) instanceof ArrayNode array) {
                array.set(index, file);
            } else if (index == 0) {
                object.set(name, file);
            }
        }
    }

    private <T> T convert(JsonNode node, JavaType type, String location) {
        try {
            return mapper.treeToValue(node, type);
        } catch (JacksonException e) {
            throw new OpenApiValidationException(new ValidationReport(List.of(new ValidationIssue("request.binding", Level.ERROR,
                "cannot convert to " + type.getRawClass().getSimpleName() + ": " + Optional.ofNullable(e.getOriginalMessage()).orElse(""), location))));
        }
    }
}
