package dev.openapifn.spring.client;

import dev.openapifn.http.ContentType;
import dev.openapifn.http.Headers;
import dev.openapifn.json.Json;
import dev.openapifn.json.JsonString;
import dev.openapifn.spring.FilePart;
import dev.openapifn.spring.JacksonJson;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/** A received response, decoded on demand into the types of generated response records. */
public final class ClientResponse {

    private final int status;
    private final Headers headers;
    private final byte[] body;
    private final JsonMapper mapper;

    public ClientResponse(int status, Headers headers, byte[] body, JsonMapper mapper) {
        this.status = status;
        this.headers = headers;
        this.body = body.clone();
        this.mapper = mapper;
    }

    public int status() {
        return status;
    }

    public Headers headers() {
        return headers;
    }

    public byte[] bytes() {
        return body.clone();
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
        var text = new String(body, StandardCharsets.UTF_8);
        return raw == String.class ? (T) text : mapper.convertValue(text, type);
    }

    /** The header {@code name} converted to {@code type} ({@code simple} style), or {@code null} when absent. */
    public <T> @Nullable T header(String name, Class<T> type) {
        return header(name, mapper.constructType(type));
    }

    public <T> @Nullable T header(String name, TypeReference<T> type) {
        return header(name, mapper.constructType(type));
    }

    private <T> @Nullable T header(String name, JavaType type) {
        var raw = headers.all(name);
        if (raw.isEmpty()) {
            return null;
        }
        var isList = type.isCollectionLikeType() || type.isArrayType();
        var joined = String.join(",", raw);
        var value = isList ? Json.of(List.of(joined.split(",")).stream().map(String::trim).toList()) : JsonString.of(joined);
        return mapper.treeToValue(JacksonJson.toJsonNode(value), type);
    }
}
