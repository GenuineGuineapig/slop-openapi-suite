package dev.openapifn.spring.client;

import dev.openapifn.http.BodyParser;
import dev.openapifn.http.ContentType;
import dev.openapifn.http.Headers;
import dev.openapifn.http.ItemFramer;
import dev.openapifn.http.SchemaShapes;
import dev.openapifn.json.Json;
import dev.openapifn.json.JsonString;
import dev.openapifn.model.MediaType;
import dev.openapifn.schema.SchemaRegistry;
import dev.openapifn.spring.FilePart;
import dev.openapifn.spring.ItemStreams;
import dev.openapifn.spring.JacksonJson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * A received response, decoded on demand into the types of generated response records. The body
 * of a sequential media type is not read until it is decoded, so that {@link #items} can stream it.
 */
public final class ClientResponse {

    private static final BodyParser PARSER = new BodyParser(new SchemaShapes(SchemaRegistry.empty()));
    private static final ContentType OCTET_STREAM = ContentType.parse(FilePart.OCTET_STREAM).orElseThrow();

    private final int status;
    private final Headers headers;
    private final JsonMapper mapper;
    private final @Nullable InputStream input;
    private final Runnable close;
    private byte @Nullable [] body;
    private boolean streaming;

    public ClientResponse(int status, Headers headers, byte[] body, JsonMapper mapper) {
        this.status = status;
        this.headers = headers;
        this.mapper = mapper;
        this.input = null;
        this.close = () -> { };
        this.body = body.clone();
    }

    /**
     * A response whose body is read on demand.
     *
     * @param input the body
     * @param close releases the response, once its body is read or no longer needed
     */
    public ClientResponse(int status, Headers headers, InputStream input, Runnable close, JsonMapper mapper) {
        this.status = status;
        this.headers = headers;
        this.mapper = mapper;
        this.input = input;
        this.close = close;
    }

    public int status() {
        return status;
    }

    public Headers headers() {
        return headers;
    }

    public byte[] bytes() {
        return content().clone();
    }

    /** The body, read now if it has not been read yet. */
    private byte[] content() {
        if (body == null) {
            try (var in = java.util.Objects.requireNonNull(input)) {
                body = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                close.run();
            }
        }
        return body;
    }

    /**
     * The items of a body of a sequential media type (see {@link ItemFramer}), converted to
     * {@code type}. An unread body is streamed: items are decoded as they arrive, and closing the
     * stream releases the response, so use it in a try-with-resources block. Other bodies are
     * decoded as an array of items.
     */
    public <T> Stream<T> items(Class<T> type) {
        return items(mapper.constructType(type));
    }

    public <T> Stream<T> items(TypeReference<T> type) {
        return items(mapper.constructType(type));
    }

    private <T> Stream<T> items(JavaType type) {
        var contentType = contentType().flatMap(ContentType::parse).filter(ItemFramer::isSequential);
        if (contentType.isEmpty()) {
            List<T> list = body(mapper.getTypeFactory().constructCollectionType(List.class, type));
            return list == null ? Stream.empty() : list.stream();
        }
        var sequential = contentType.get();
        var source = body != null ? new ByteArrayInputStream(body) : java.util.Objects.requireNonNull(input);
        streaming = body == null;
        return ItemStreams.read(source, ItemFramer.of(sequential).orElseThrow(), frame -> PARSER.item(frame, sequential, MediaType.EMPTY, 0)
                .orElseThrow(error -> new IllegalStateException("Cannot decode " + sequential.essence() + " content: " + error)))
            .onClose(close).map(value -> mapper.treeToValue(JacksonJson.toJsonNode(value), type));
    }

    /** Releases the response unless {@link #items} is streaming its body. */
    void release() {
        if (!streaming) {
            close.run();
        }
    }

    public Optional<String> contentType() {
        return headers.first("Content-Type");
    }

    /** The body converted to {@code type}: JSON is read with Jackson, other content as text, bytes or a {@link FilePart}. */
    public <T> @Nullable T body(Class<T> type) {
        return body(mapper.constructType(type));
    }

    public <T> @Nullable T body(TypeReference<T> type) {
        return body(mapper.constructType(type));
    }

    @SuppressWarnings("unchecked")
    private <T> @Nullable T body(JavaType type) {
        var body = content();
        if (body.length == 0) {
            return null;
        }
        var contentType = contentType().flatMap(ContentType::parse);
        var raw = type.getRawClass();
        if (raw == FilePart.class) {
            return (T) new FilePart(null, contentType().orElse(FilePart.OCTET_STREAM), body);
        }
        if (raw == byte[].class && contentType.filter(ContentType::isJson).isEmpty()) {
            return (T) body.clone();
        }
        if (contentType.filter(ContentType::isJson).isPresent()) {
            return mapper.readValue(body, type);
        }
        if (raw == String.class) {
            return (T) new String(body, StandardCharsets.UTF_8);
        }
        var parsed = PARSER.parse(body, contentType.orElse(OCTET_STREAM), MediaType.EMPTY);
        return mapper.treeToValue(JacksonJson.toJsonNode(parsed.toOptional().orElseThrow(() ->
            new IllegalStateException("Cannot decode " + contentType().orElse("") + " content: " + parsed.failure().orElse("")))), type);
    }

    /** The header {@code name} converted to {@code type} ({@code simple} style), or {@code null} when absent. */
    public <T> @Nullable T header(String name, Class<T> type) {
        return header(name, mapper.constructType(type), ",");
    }

    public <T> @Nullable T header(String name, TypeReference<T> type) {
        return header(name, mapper.constructType(type), ",");
    }

    /** A header whose list items are separated by {@code delimiter} (a Swagger 2.0 collection format) rather than commas. */
    public <T> @Nullable T header(String name, Class<T> type, String delimiter) {
        return header(name, mapper.constructType(type), delimiter);
    }

    public <T> @Nullable T header(String name, TypeReference<T> type, String delimiter) {
        return header(name, mapper.constructType(type), delimiter);
    }

    private <T> @Nullable T header(String name, JavaType type, String delimiter) {
        var raw = headers.all(name);
        if (raw.isEmpty()) {
            return null;
        }
        var isList = type.isCollectionLikeType() || type.isArrayType();
        var joined = String.join(",", raw);
        var value = isList ? Json.of(List.of(joined.split(java.util.regex.Pattern.quote(delimiter))).stream().map(String::trim).toList())
            : JsonString.of(joined);
        return mapper.treeToValue(JacksonJson.toJsonNode(value), type);
    }
}
