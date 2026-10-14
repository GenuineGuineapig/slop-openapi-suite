package dev.openapifn.codegen.api;

import dev.openapifn.codegen.java.Names;
import dev.openapifn.codegen.java.TypeRef;
import dev.openapifn.codegen.java.TypeRef.ClassName;
import dev.openapifn.codegen.model.ModelDef.Property;
import dev.openapifn.codegen.model.TypeMapper;
import dev.openapifn.http.ContentType;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.model.MediaType;
import dev.openapifn.model.Parameter;
import dev.openapifn.model.Response;
import dev.openapifn.schema.Dialect;
import dev.openapifn.schema.Schema;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Java view of one operation: names, typed parameters, the request body and the response
 * variants.
 *
 * @param operation the operation
 * @param id the operation id used at runtime ({@code operationId}, or synthesized)
 * @param method Java method name
 * @param group the API group (first tag)
 * @param request the request record, if the operation has parameters or a body
 * @param parameters typed parameters
 * @param body the request body, if any
 * @param response the sealed response type
 * @param variants the response variants
 */
public record OperationModel(ApiOperation operation, String id, String method, String group, Optional<ClassName> request, List<ParamModel> parameters,
                             Optional<BodyModel> body, ClassName response, List<VariantModel> variants) {

    /**
     * A parameter.
     *
     * @param parameter the parameter
     * @param property its request record component
     */
    public record ParamModel(Parameter parameter, Property property) {
    }

    /**
     * The request body.
     *
     * @param contentType the media type requests are sent with
     * @param mediaType its Media Type Object
     * @param property the request record component ({@code body})
     * @param contentTypes all described media types
     */
    public record BodyModel(String contentType, MediaType mediaType, Property property, List<String> contentTypes) {

        /** Whether the body is sent as named {@code multipart/form-data} parts (other multipart types are positional lists). */
        public boolean formData() {
            return ContentType.parse(contentType).filter(type -> type.essence().equals("multipart/form-data")).isPresent();
        }
    }

    /**
     * A response variant.
     *
     * @param key {@code 200}, {@code 2XX} or {@code default}
     * @param name record name, e.g. {@code Ok}
     * @param response the Response Object
     * @param body the body component, if the response has content
     * @param headers header components by header name
     */
    public record VariantModel(String key, String name, Response response, Optional<Property> body, Map<String, Property> headers) {

        /** Whether the status is fixed (otherwise the record carries a {@code status} component). */
        public boolean fixedStatus() {
            return key.matches("[0-9]{3}");
        }

        /** The {@code static} factory method name, e.g. {@code ok}. */
        public String factory() {
            return name.equals("Default") ? "defaultResponse" : Names.identifier(Character.toLowerCase(name.charAt(0)) + name.substring(1));
        }

    }

    private static final Map<Integer, String> REASONS = Map.ofEntries(
        Map.entry(100, "Continue"), Map.entry(101, "SwitchingProtocols"), Map.entry(200, "Ok"), Map.entry(201, "Created"), Map.entry(202, "Accepted"),
        Map.entry(203, "NonAuthoritativeInformation"), Map.entry(204, "NoContent"), Map.entry(205, "ResetContent"), Map.entry(206, "PartialContent"),
        Map.entry(207, "MultiStatus"), Map.entry(300, "MultipleChoices"), Map.entry(301, "MovedPermanently"), Map.entry(302, "Found"),
        Map.entry(303, "SeeOther"), Map.entry(304, "NotModified"), Map.entry(307, "TemporaryRedirect"), Map.entry(308, "PermanentRedirect"),
        Map.entry(400, "BadRequest"), Map.entry(401, "Unauthorized"), Map.entry(402, "PaymentRequired"), Map.entry(403, "Forbidden"),
        Map.entry(404, "NotFound"), Map.entry(405, "MethodNotAllowed"), Map.entry(406, "NotAcceptable"), Map.entry(408, "RequestTimeout"),
        Map.entry(409, "Conflict"), Map.entry(410, "Gone"), Map.entry(411, "LengthRequired"), Map.entry(412, "PreconditionFailed"),
        Map.entry(413, "ContentTooLarge"), Map.entry(414, "UriTooLong"), Map.entry(415, "UnsupportedMediaType"), Map.entry(416, "RangeNotSatisfiable"),
        Map.entry(417, "ExpectationFailed"), Map.entry(418, "ImATeapot"), Map.entry(421, "MisdirectedRequest"), Map.entry(422, "UnprocessableContent"),
        Map.entry(423, "Locked"), Map.entry(424, "FailedDependency"), Map.entry(425, "TooEarly"), Map.entry(426, "UpgradeRequired"),
        Map.entry(428, "PreconditionRequired"), Map.entry(429, "TooManyRequests"), Map.entry(431, "RequestHeaderFieldsTooLarge"),
        Map.entry(451, "UnavailableForLegalReasons"), Map.entry(500, "InternalServerError"), Map.entry(501, "NotImplemented"),
        Map.entry(502, "BadGateway"), Map.entry(503, "ServiceUnavailable"), Map.entry(504, "GatewayTimeout"), Map.entry(505, "HttpVersionNotSupported"),
        Map.entry(507, "InsufficientStorage"), Map.entry(511, "NetworkAuthenticationRequired"));

    /** Builds the model of a path operation, grouped by its first tag; types are registered with {@code mapper}. */
    public static OperationModel of(ApiOperation operation, String apiPackage, TypeMapper mapper, Set<String> takenMethods) {
        return of(operation, operation.displayName(), operation.operation().tags().stream().findFirst().map(Names::pascal).orElse("Default"),
            operation.method().toLowerCase(java.util.Locale.ROOT) + " " + operation.path(), apiPackage, mapper, takenMethods);
    }

    /**
     * Builds the model of an operation in {@code group}; {@code id} identifies it at runtime and
     * {@code fallbackName} names its Java method when it has no {@code operationId}.
     */
    public static OperationModel of(ApiOperation operation, String id, String group, String fallbackName, String apiPackage, TypeMapper mapper,
                                    Set<String> takenMethods) {
        var method = Names.unique(Names.camel(operation.operationId().orElse(fallbackName)), takenMethods);
        var pascal = Character.toUpperCase(method.charAt(0)) + method.substring(1);
        var requestName = ClassName.of(apiPackage, pascal + "Request");
        var responseName = ClassName.of(apiPackage, pascal + "Response");
        var componentNames = new HashSet<String>(List.of("builder", "body"));
        var parameters = new ArrayList<ParamModel>();
        for (var parameter : operation.parameters()) {
            var candidate = Names.camel(parameter.name());
            var javaName = componentNames.contains(candidate) ? Names.unique(candidate + Names.pascal(parameter.in().value()), componentNames)
                : Names.unique(candidate, componentNames);
            var schema = parameter.effectiveSchema().orElse(empty());
            var type = mapper.type(schema, requestName, Names.pascal(parameter.name()));
            parameters.add(new ParamModel(parameter, new Property(parameter.name(), javaName, type, parameter.required(),
                !parameter.required() || mapper.nullable(schema), schema)));
        }
        var body = operation.operation().requestBody().flatMap(requestBody -> primary(requestBody.content()).map(contentType -> {
            var mediaType = requestBody.content().get(contentType);
            var schema = mediaType.schema().orElse(empty());
            var type = bodyType(contentType, mediaType, mapper, requestName, "Body");
            return new BodyModel(contentType, mediaType, new Property("body", "body", type, requestBody.required(),
                !requestBody.required() || mapper.nullable(schema), schema), List.copyOf(requestBody.content().keySet()));
        }));
        var request = parameters.isEmpty() && body.isEmpty() ? Optional.<ClassName>empty() : Optional.of(requestName);
        return new OperationModel(operation, id, method, group, request, parameters, body, responseName, variants(operation, mapper, responseName));
    }

    private static List<VariantModel> variants(ApiOperation operation, TypeMapper mapper, ClassName responseName) {
        var responses = operation.operation().responses();
        var entries = new ArrayList<Map.Entry<String, Response>>(responses.byStatus().entrySet());
        responses.defaultResponse().ifPresent(response -> entries.add(Map.entry("default", response)));
        var taken = new HashSet<String>();
        var variants = new ArrayList<VariantModel>();
        for (var entry : entries) {
            var key = entry.getKey();
            var name = Names.unique(variantName(key), taken);
            var response = entry.getValue();
            var body = primary(response.content()).map(contentType -> {
                var mediaType = response.content().get(contentType);
                var type = bodyType(contentType, mediaType, mapper, responseName, name + "Body");
                return new Property("body", "body", type, true, mapper.nullable(mediaType.schema().orElse(empty())), mediaType.schema().orElse(empty()));
            });
            var headers = new java.util.LinkedHashMap<String, Property>();
            var headerNames = new HashSet<String>(List.of("body", "status", "headers", "contentType"));
            response.headers().forEach((header, definition) -> {
                if (!header.equalsIgnoreCase("Content-Type")) {
                    var schema = definition.asParameter(header).effectiveSchema().orElse(empty());
                    var type = mapper.type(schema, responseName, name + Names.pascal(header));
                    headers.put(header, new Property(header, Names.unique(Names.camel(header), headerNames), type, definition.required(),
                        !definition.required() || mapper.nullable(schema), schema));
                }
            });
            variants.add(new VariantModel(key, name, response, body, headers));
        }
        return variants;
    }

    private static String variantName(String key) {
        if (key.equals("default")) {
            return "Default";
        }
        if (!key.matches("[0-9]{3}")) {
            return "Status" + key.toUpperCase(java.util.Locale.ROOT);
        }
        return REASONS.getOrDefault(Integer.parseInt(key), "Status" + key);
    }

    /** The media type used for typing and for sending: JSON first, then the first one. */
    static Optional<String> primary(Map<String, MediaType> content) {
        return content.keySet().stream().filter(key -> ContentType.parse(key).filter(ContentType::isJson).isPresent()).findFirst()
            .or(() -> content.keySet().stream().findFirst());
    }

    /**
     * The Java type of a body of {@code contentType}: sequential media types are lists of their
     * item schema, structured and text content follow the schema, other text is a {@code String}
     * and everything else (and {@code format: binary}) a {@code FilePart}.
     */
    static TypeRef bodyType(String contentType, MediaType mediaType, TypeMapper mapper, ClassName owner, String hint) {
        if (mediaType.itemSchema().isPresent()) {
            return TypeRef.LIST.of(mapper.type(mediaType.itemSchema().get(), owner, hint + "Item"));
        }
        var type = ContentType.parse(contentType);
        var text = type.filter(t -> t.type().equals("text") && !t.isXml()).isPresent();
        var structured = type.filter(t -> t.isJson() || t.isXml() || t.type().equals("multipart")
            || t.essence().equals("application/x-www-form-urlencoded")).isPresent();
        var schema = mediaType.schema();
        var binary = schema.map(s -> mapper.registry().deref(s)).flatMap(Schema::format).filter("binary"::equals).isPresent();
        if (schema.isPresent() && !binary && (structured || text)) {
            return mapper.type(schema.get(), owner, hint);
        }
        return text ? TypeRef.STRING : ClassName.of("dev.openapifn.spring", "FilePart");
    }

    private static Schema empty() {
        var uri = URI.create("urn:openapi-fn:any");
        return Schema.at(JsonObject.EMPTY, uri, JsonPointer.ROOT, uri, Dialect.DRAFT_2020_12);
    }

    /** Whether the API provider sends this operation's requests (a webhook or callback) rather than serving them. */
    public boolean outbound() {
        return operation.kind() != ApiOperation.Kind.PATH;
    }

    /** The default path a receiving controller maps a webhook or callback to, e.g. {@code /webhooks/newPet}. */
    public String receiverPath() {
        return "/" + receiverKind() + "/" + method;
    }

    /** {@code webhooks} or {@code callbacks}. */
    public String receiverKind() {
        return operation.kind() == ApiOperation.Kind.WEBHOOK ? "webhooks" : "callbacks";
    }

    /** All request record components in order. */
    public List<Property> requestProperties() {
        var properties = new ArrayList<Property>();
        parameters.forEach(parameter -> properties.add(parameter.property()));
        body.ifPresent(b -> properties.add(b.property()));
        return properties;
    }

    /** Media types the response may have, for {@code Accept}. */
    public List<String> produces() {
        return variants.stream().flatMap(variant -> variant.response().content().keySet().stream()).distinct().toList();
    }
}
