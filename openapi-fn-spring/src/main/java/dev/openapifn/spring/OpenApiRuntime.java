package dev.openapifn.spring;

import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.ContentType;
import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.http.PathTemplate;
import dev.openapifn.http.SchemaShapes;
import dev.openapifn.json.JsonValue;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.model.MediaType;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.ParameterStyle;
import dev.openapifn.model.Response;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.spring.servlet.ServletMessages;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OpenApiValidator.RequestValidation;
import dev.openapifn.validation.OperationMatch;
import dev.openapifn.validation.Route;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * The runtime of one API description: binds requests for generated controllers (validating
 * them) and maps their {@link ApiResponse}s to HTTP, including header serialization and content
 * negotiation among the described media types.
 */
public final class OpenApiRuntime {

    /** Request attribute under which the validation filter stores the {@link RequestValidation}. */
    public static final String VALIDATION_ATTRIBUTE = OpenApiRuntime.class.getName() + ".validation";

    private final String name;
    private final OpenApiDocument api;
    private final OpenApiValidator validator;
    private final JsonMapper mapper;
    private final boolean failOnInvalidRequest;
    private final BodyWriter writer;

    public OpenApiRuntime(String name, OpenApiDocument api, OpenApiValidator validator, JsonMapper mapper, boolean failOnInvalidRequest) {
        this.name = name;
        this.api = api;
        this.validator = validator;
        this.mapper = mapper;
        this.failOnInvalidRequest = failOnInvalidRequest;
        this.writer = new BodyWriter(new SchemaShapes(api.schemas()));
    }

    public String name() {
        return name;
    }

    public OpenApiDocument api() {
        return api;
    }

    public OpenApiValidator validator() {
        return validator;
    }

    public JsonMapper mapper() {
        return mapper;
    }

    /**
     * The operation (path, webhook or callback) identified by its {@code operationId}, or by
     * {@code METHOD path} when it has none.
     */
    public ApiOperation operation(String id) {
        return api.allOperations().stream().filter(operation -> operation.displayName().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("API '" + name + "' has no operation '" + id + "'"));
    }

    // ---------------------------------------------------------------------------------------------
    // Requests

    /**
     * Validates and parses {@code request} for the operation {@code operationId}, reusing the
     * result of the validation filter when present.
     *
     * @throws OpenApiValidationException when the request is invalid (and failing is enabled)
     */
    public RequestBinding bind(HttpServletRequest request, String operationId) {
        var operation = operation(operationId);
        var message = ServletMessages.toHttpRequest(request);
        var validation = request.getAttribute(VALIDATION_ATTRIBUTE) instanceof RequestValidation cached
            && cached.match().filter(match -> match.operation().equals(operation)).isPresent()
            ? cached
            : validator.validateRequest(new OperationMatch(operation, pathValues(request, operation)), message);
        if (failOnInvalidRequest && validation.report().hasErrors()) {
            throw new OpenApiValidationException(validation.report());
        }
        return new RequestBinding(validation, message, mapper);
    }

    /** Raw path values from the handler mapping's pattern, falling back to the validator's router. */
    private Map<String, String> pathValues(HttpServletRequest request, ApiOperation operation) {
        var path = ServletMessages.path(request);
        return Optional.ofNullable(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
            .flatMap(pattern -> PathTemplate.compile(pattern.toString()).match(path))
            .or(() -> validator.route(dev.openapifn.http.HttpRequest.of(operation.method(), path)) instanceof Route.Found found
                ? Optional.of(found.match().pathValues()) : Optional.empty())
            .orElse(Map.of());
    }

    // ---------------------------------------------------------------------------------------------
    // Responses

    /** Maps {@code response} of operation {@code operationId} to a {@link ResponseEntity}. */
    public ResponseEntity<Object> respond(HttpServletRequest request, String operationId, ApiResponse response) {
        var declared = operation(operationId).operation().responses().forStatus(response.status());
        var builder = ResponseEntity.status(response.status());
        response.headers().forEach((header, value) -> {
            if (value != null) {
                builder.header(header, headerValue(declared, header, value));
            }
        });
        var body = response.body();
        if (body == null) {
            return builder.build();
        }
        var content = declared.map(Response::content).orElse(Map.of());
        var negotiated = Optional.ofNullable(response.contentType())
            .orElseGet(() -> negotiate(request.getHeader("Accept"), content, body));
        var parsed = ContentType.parse(negotiated).orElseThrow(() -> new IllegalArgumentException("Invalid media type '" + negotiated + "'"));
        var mediaType = parsed.type().equals("multipart") && parsed.parameter("boundary").isEmpty()
            ? negotiated + "; boundary=" + BodyWriter.boundary() : negotiated;
        var type = ContentType.parse(mediaType).orElseThrow();
        var schema = ContentType.bestMatch(content.keySet(), type).map(content::get).flatMap(MediaType::schema);
        return builder.contentType(org.springframework.http.MediaType.parseMediaType(mediaType)).body(serialize(body, type, schema));
    }

    private String headerValue(Optional<Response> declared, String header, Object value) {
        var explode = declared.map(r -> r.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(header))
            .anyMatch(e -> e.getValue().explode())).orElse(false);
        var spec = new ParameterSerializer.Spec(header, ParameterLocation.HEADER, ParameterStyle.SIMPLE, explode, false);
        return ParameterSerializer.header(spec, toJson(value));
    }

    /**
     * Chooses among the described media types: the first one the {@code Accept} header allows
     * (or the first described one). Wildcard media types resolve to the type of a
     * {@link FilePart} body or to {@code application/octet-stream}.
     */
    private static String negotiate(@Nullable String accept, Map<String, MediaType> content, Object body) {
        var acceptable = Arrays.stream(Objects.requireNonNullElse(accept, "*/*").split(","))
            .flatMap(range -> ContentType.parse(range).stream()).toList();
        var chosen = content.keySet().stream()
            .filter(key -> ContentType.parse(key).filter(type -> acceptable.stream().anyMatch(range -> range.includes(type) || type.includes(range))).isPresent())
            .findFirst()
            .or(() -> content.keySet().stream().findFirst())
            .orElse("application/json");
        if (!chosen.contains("*")) {
            return chosen;
        }
        return body instanceof FilePart file ? file.contentType() : FilePart.OCTET_STREAM;
    }

    private Object serialize(Object body, ContentType type, Optional<dev.openapifn.schema.Schema> schema) {
        return switch (body) {
            case FilePart file -> file.content();
            case byte[] bytes -> bytes;
            default -> type.isJson() ? mapper.writeValueAsBytes(body) : writer.write(toJson(body), type, schema);
        };
    }

    private JsonValue toJson(Object value) {
        return JacksonJson.toJsonValue(mapper.valueToTree(value));
    }
}
