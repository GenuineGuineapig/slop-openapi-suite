package dev.openapifn.spring;

import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.ContentType;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.ItemFramer;
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
import dev.openapifn.schema.Schema;
import dev.openapifn.validation.IssueKeys;
import dev.openapifn.validation.Level;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OpenApiValidator.RequestValidation;
import dev.openapifn.validation.OperationMatch;
import dev.openapifn.validation.Route;
import dev.openapifn.validation.ValidationIssue;
import dev.openapifn.validation.ValidationReport;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.json.JsonMapper;

/**
 * The runtime of one API description, independent of the web stack: binds requests for generated
 * controllers (validating them) and maps their {@link ApiResponse}s to HTTP replies, including
 * header serialization and content negotiation among the described media types. The servlet and
 * reactive adapters connect it to Spring MVC and WebFlux.
 */
public final class OpenApiRuntime {

    /** Request attribute under which the validation filter stores the {@link RequestValidation}. */
    public static final String VALIDATION_ATTRIBUTE = OpenApiRuntime.class.getName() + ".validation";

    /**
     * Request attribute marking a request rejected while it was bound (an invalid streamed item, for
     * example), whose response is a problem rather than a response of the operation.
     */
    public static final String REJECTED_ATTRIBUTE = OpenApiRuntime.class.getName() + ".rejected";

    private final String name;
    private final OpenApiDocument api;
    private final OpenApiValidator validator;
    private final JsonMapper mapper;
    private final boolean failOnInvalidRequest;
    private final BodyWriter writer;
    private final Map<String, ApiOperation> receivers;

    public OpenApiRuntime(String name, OpenApiDocument api, OpenApiValidator validator, JsonMapper mapper, boolean failOnInvalidRequest) {
        this(name, api, validator, mapper, failOnInvalidRequest, Map.of());
    }

    /**
     * @param receivers the operation ids of the webhooks and callbacks the application receives, by
     *     the path their controllers are mapped to (these operations have no path to route by)
     */
    public OpenApiRuntime(String name, OpenApiDocument api, OpenApiValidator validator, JsonMapper mapper, boolean failOnInvalidRequest,
                          Map<String, String> receivers) {
        this.name = name;
        this.api = api;
        this.validator = validator;
        this.mapper = mapper;
        this.failOnInvalidRequest = failOnInvalidRequest;
        this.writer = new BodyWriter(new SchemaShapes(api.schemas()));
        var operations = new java.util.LinkedHashMap<String, ApiOperation>();
        receivers.forEach((path, id) -> operations.put(path, operation(id)));
        this.receivers = Map.copyOf(operations);
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

    /** The operation (path, webhook or callback) an identifier names, see {@link OpenApiDocument#operation(String)}. */
    public ApiOperation operation(String id) {
        return api.operation(id)
            .orElseThrow(() -> new IllegalArgumentException("API '" + name + "' has no operation '" + id + "'"));
    }

    // ---------------------------------------------------------------------------------------------
    // Requests

    /** Whether {@code request} targets an operation of this description: a described path or a receiver. */
    public boolean routes(HttpRequest request) {
        return receivers.containsKey(request.path()) || !(validator.route(request) instanceof Route.NotFound);
    }

    /** Validates {@code request} against the operation it targets: a receiver's webhook or callback, or the routed path operation. */
    public RequestValidation validate(HttpRequest request) {
        return validate(request, false);
    }

    /**
     * Validates everything but the body of a request whose body is streamed (see {@link ItemStreams#sequential}),
     * like {@link #validate}; the items are validated as they are bound, see {@link RequestBinding#items}.
     */
    public RequestValidation validateHead(HttpRequest request) {
        return validate(request, true);
    }

    private RequestValidation validate(HttpRequest request, boolean head) {
        var receiver = receivers.get(request.path());
        if (receiver == null) {
            return head ? validator.validateRequestHead(request) : validator.validateRequest(request);
        }
        if (!receiver.method().equals(request.method())) {
            var issue = new ValidationIssue(IssueKeys.REQUEST_OPERATION_NOT_ALLOWED, Level.ERROR, request.method() + " is not allowed for "
                + request.path() + "; allowed: [" + receiver.method() + "]", "method");
            return new RequestValidation(new ValidationReport(List.of(issue)), Optional.empty(), Map.of(), Optional.empty());
        }
        var match = new OperationMatch(receiver, Map.of());
        return head ? validator.validateRequestHead(match, request) : validator.validateRequest(match, request);
    }

    /**
     * Validates and parses a request for the operation {@code operationId}, reusing an earlier
     * validation of the same operation (by the validation filter). Web-stack adapters such as
     * {@link dev.openapifn.spring.servlet.ServletRuntime} call this.
     *
     * @param message the request; without its body when the body is streamed
     * @param pattern the path pattern the request was mapped by, which yields the raw path values
     *     (else the description routes the path)
     * @param validated an earlier validation, typically a request attribute; ignored unless it is a
     *     {@link RequestValidation} of this operation
     * @param streamed the body of a sequential media type, read as its items are bound; {@code null}
     *     when {@code message} carries the body
     * @throws OpenApiValidationException when the request is invalid (and failing is enabled)
     */
    public RequestBinding bind(HttpRequest message, String operationId, @Nullable String pattern, @Nullable Object validated,
                               @Nullable Supplier<InputStream> streamed) {
        var validation = validate(message, operationId, pattern, validated, streamed != null);
        if (streamed == null) {
            return binding(validation, message);
        }
        var contentType = message.contentType().orElseThrow();
        return new RequestBinding(validation, message, mapper, new RequestBinding.Items(streamed, ContentType.parse(contentType).orElseThrow(),
            validator.requestItems(validation.match().orElseThrow(), contentType), failOnInvalidRequest));
    }

    /**
     * Validates a request for the operation {@code operationId}, reusing an earlier validation of
     * the same operation; see {@link #bind}.
     *
     * @param head whether only the head of the request is validated, its body being streamed
     * @throws OpenApiValidationException when the request is invalid (and failing is enabled)
     */
    public RequestValidation validate(HttpRequest message, String operationId, @Nullable String pattern, @Nullable Object validated, boolean head) {
        var operation = operation(operationId);
        var match = new OperationMatch(operation, pathValues(message, operation, pattern));
        var validation = validated instanceof RequestValidation cached && cached.match().filter(earlier -> earlier.operation().equals(operation)).isPresent()
            ? cached
            : head ? validator.validateRequestHead(match, message) : validator.validateRequest(match, message);
        if (failOnInvalidRequest && validation.report().hasErrors()) {
            throw new OpenApiValidationException(validation.report());
        }
        return validation;
    }

    /** The binding of a validated request whose {@code message} carries its body (if any). */
    public RequestBinding binding(RequestValidation validation, HttpRequest message) {
        return new RequestBinding(validation, message, mapper);
    }

    /** Raw path values from the mapping pattern, falling back to the validator's router. */
    private Map<String, String> pathValues(HttpRequest message, ApiOperation operation, @Nullable String pattern) {
        return Optional.ofNullable(pattern).flatMap(mapped -> PathTemplate.compile(mapped).match(message.path()))
            .or(() -> validator.route(HttpRequest.of(operation.method(), message.path())) instanceof Route.Found found
                ? Optional.of(found.match().pathValues()) : Optional.empty())
            .orElse(Map.of());
    }

    /** Whether {@code failOnInvalidRequest} is set: invalid requests fail binding. */
    public boolean failsOnInvalidRequests() {
        return failOnInvalidRequest;
    }

    // ---------------------------------------------------------------------------------------------
    // Responses

    /**
     * An HTTP response for an {@link ApiResponse}: its status, its headers (serialized as described,
     * {@code Content-Type} included) and its body, not yet encoded.
     *
     * @param contentType the negotiated content type, when there is a body
     * @param schema the schema of the body's media type, if described
     * @param body the body as the operation returned it
     */
    public record Reply(int status, HttpHeaders headers, @Nullable ContentType contentType, Optional<Schema> schema, @Nullable Object body) {
    }

    /**
     * The reply to {@code response} of operation {@code operationId}: header serialization and
     * content negotiation among the described media types for {@code accept}.
     */
    public Reply reply(@Nullable String accept, String operationId, ApiResponse response) {
        var declared = operation(operationId).operation().responses().forStatus(response.status());
        var headers = new HttpHeaders();
        response.headers().forEach((header, value) -> {
            if (value != null) {
                headers.add(header, headerValue(declared, header, value));
            }
        });
        var body = response.body();
        if (body == null) {
            return new Reply(response.status(), headers, null, Optional.empty(), null);
        }
        var content = declared.map(Response::content).orElse(Map.of());
        var negotiated = Optional.ofNullable(response.contentType()).orElseGet(() -> negotiate(accept, content, body));
        var parsed = ContentType.parse(negotiated).orElseThrow(() -> new IllegalArgumentException("Invalid media type '" + negotiated + "'"));
        var mediaType = parsed.type().equals("multipart") && parsed.parameter("boundary").isEmpty()
            ? negotiated + "; boundary=" + BodyWriter.boundary() : negotiated;
        var type = ContentType.parse(mediaType).orElseThrow();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType(mediaType));
        var schema = ContentType.bestMatch(content.keySet(), type).map(content::get).flatMap(MediaType::schema);
        return new Reply(response.status(), headers, type, schema, body);
    }

    /** A header value serialized as described (explode, collection format), else in the {@code simple} style. */
    private String headerValue(Optional<Response> declared, String header, Object value) {
        var spec = declared.flatMap(r -> r.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(header)).findFirst())
            .map(e -> ParameterSerializer.Spec.of(e.getValue().asParameter(header)))
            .orElseGet(() -> new ParameterSerializer.Spec(header, ParameterLocation.HEADER, ParameterStyle.SIMPLE, false, false));
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

    /** The encoded body of {@code reply} as a whole: the content of files, or the serialized value (streams are collected). */
    public byte[] encode(Reply reply) {
        return encode(Objects.requireNonNull(reply.body()), Objects.requireNonNull(reply.contentType()), reply.schema());
    }

    private byte[] encode(Object body, ContentType type, Optional<Schema> schema) {
        return switch (body) {
            case FilePart file -> file.content();
            case byte[] bytes -> bytes;
            case Stream<?> items -> {
                try (items) {
                    yield encode(items.toList(), type, schema);
                }
            }
            default -> type.isJson() ? mapper.writeValueAsBytes(body) : writer.write(toJson(body), type, schema);
        };
    }

    /**
     * One encoded item of a body of a sequential media type (see {@link ItemFramer#isSequential}),
     * for writing items as they are produced; files become positional parts as they are.
     */
    public byte[] encodeItem(Object item, ContentType type) {
        if (item instanceof FilePart file && type.type().equals("multipart")) {
            return BodyWriter.part(type.parameter("boundary").orElseThrow(), BodyWriter.part(file.contentType(), file.content()));
        }
        return BodyWriter.item(toJson(item), type);
    }

    private JsonValue toJson(Object value) {
        return JacksonJson.toJsonValue(mapper.valueToTree(value));
    }
}
