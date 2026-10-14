package dev.openapifn.spring.client;

import dev.openapifn.http.ParameterSerializer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A request to send: method, path template and parameter values with their serialization specs,
 * plus an optional body. Built immutably by generated clients; {@code null} values are skipped.
 *
 * @param method HTTP method
 * @param pathTemplate path template such as {@code /pets/{petId}}
 * @param parameters parameter values in declaration order
 * @param body the body, if any
 * @param acceptTypes acceptable media types
 * @param operationId the operation the call belongs to, which identifies webhooks and callbacks for validation (they have no path)
 */
public record ClientCall(String method, String pathTemplate, List<Param> parameters, @Nullable Body body, List<String> acceptTypes,
                         @Nullable String operationId) {

    /**
     * A parameter value.
     *
     * @param spec how the parameter is serialized
     * @param value the Java value
     */
    public record Param(ParameterSerializer.Spec spec, Object value) {
    }

    /**
     * A body: a Java value serialized as {@code contentType}, or {@link Part}s for
     * {@code multipart/form-data}.
     *
     * @param contentType the media type
     * @param value the value (for multipart, a list of parts)
     */
    public record Body(String contentType, Object value) {
    }

    /**
     * A multipart part; {@link dev.openapifn.spring.FilePart}s become binary parts, other values are
     * serialized as {@code contentType} (JSON when it is a JSON type, text otherwise).
     *
     * @param name part name
     * @param value the value
     * @param contentType the content type of non-file values, or {@code null} for text
     */
    public record Part(String name, Object value, @Nullable String contentType) {

        public static @Nullable Part of(String name, @Nullable Object value) {
            return value == null ? null : new Part(name, value, null);
        }

        public static @Nullable Part of(String name, @Nullable Object value, String contentType) {
            return value == null ? null : new Part(name, value, contentType);
        }
    }

    public ClientCall {
        parameters = List.copyOf(parameters);
        acceptTypes = List.copyOf(acceptTypes);
    }

    public static ClientCall of(String method, String pathTemplate) {
        return new ClientCall(method, pathTemplate, List.of(), null, List.of(), null);
    }

    /** A copy with a parameter value; {@code null} values are ignored. */
    public ClientCall param(ParameterSerializer.Spec spec, @Nullable Object value) {
        if (value == null) {
            return this;
        }
        var copy = new ArrayList<>(parameters);
        copy.add(new Param(spec, value));
        return new ClientCall(method, pathTemplate, copy, body, acceptTypes, operationId);
    }

    /** A copy with a body; a {@code null} value means no body. */
    public ClientCall body(String contentType, @Nullable Object value) {
        return new ClientCall(method, pathTemplate, parameters, value == null ? null : new Body(contentType, value), acceptTypes, operationId);
    }

    /** A copy with a {@code multipart/form-data} body of the non-null parts. */
    public ClientCall multipart(List<@Nullable Part> parts) {
        return new ClientCall(method, pathTemplate, parameters, new Body("multipart/form-data", parts.stream().filter(Objects::nonNull).toList()),
            acceptTypes, operationId);
    }

    public ClientCall accept(String... mediaTypes) {
        return new ClientCall(method, pathTemplate, parameters, body, List.of(mediaTypes), operationId);
    }

    /** A copy that names its operation. */
    public ClientCall operation(String id) {
        return new ClientCall(method, pathTemplate, parameters, body, acceptTypes, id);
    }
}
