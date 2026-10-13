package dev.openapifn.spring;

import dev.openapifn.http.ContentType;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.Multipart;
import dev.openapifn.json.JsonValue;
import dev.openapifn.validation.Level;
import dev.openapifn.validation.OpenApiValidator.RequestValidation;
import dev.openapifn.validation.ValidationIssue;
import dev.openapifn.validation.ValidationReport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
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
 * Jackson. Generated controllers construct their request records from it.
 */
public final class RequestBinding {

    private final RequestValidation validation;
    private final HttpRequest request;
    private final JsonMapper mapper;

    RequestBinding(RequestValidation validation, HttpRequest request, JsonMapper mapper) {
        this.validation = validation;
        this.request = request;
        this.mapper = mapper;
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
        var bytes = request.body();
        if (bytes.isEmpty()) {
            return null;
        }
        var contentType = request.contentType().flatMap(ContentType::parse);
        var json = contentType.filter(ContentType::isJson).isPresent();
        var raw = type.getRawClass();
        if (!json && raw == byte[].class) {
            return (T) bytes.get();
        }
        if (!json && raw == FilePart.class) {
            return (T) new FilePart(null, request.contentType().orElse(FilePart.OCTET_STREAM), bytes.get());
        }
        if (!json && raw == String.class && contentType.filter(t -> !t.isXml() && !t.type().equals("multipart")).isPresent()) {
            return (T) new String(bytes.get(), StandardCharsets.UTF_8);
        }
        var value = validation.body();
        if (value.isEmpty()) {
            return null;
        }
        var node = JacksonJson.toJsonNode(value.get());
        var boundary = contentType.filter(t -> t.essence().equals("multipart/form-data")).flatMap(t -> t.parameter("boundary"));
        if (boundary.isPresent() && node instanceof ObjectNode object) {
            embedFiles(object, Multipart.parse(bytes.get(), boundary.get()).toOptional().orElse(List.of()));
        }
        return convert(node, type, "body");
    }

    /** Replaces binary parts (those with a file name or a non-text content type) by {@link FilePart}s. */
    private static void embedFiles(ObjectNode object, List<Multipart.Part> parts) {
        for (var part : parts) {
            var type = part.contentType().flatMap(ContentType::parse);
            var binary = part.filename().isPresent() || type.filter(t -> !t.type().equals("text") && !t.isJson()).isPresent();
            var name = part.name().orElse("");
            if (!binary || !object.has(name)) {
                continue;
            }
            var file = JacksonJson.embed(new FilePart(part.filename().orElse(null), part.contentType().orElse(FilePart.OCTET_STREAM), part.body()));
            if (object.get(name) instanceof ArrayNode array) {
                replaceFirstString(array, file);
            } else {
                object.set(name, file);
            }
        }
    }

    private static void replaceFirstString(ArrayNode array, JsonNode file) {
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).isString()) {
                array.set(i, file);
                return;
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
