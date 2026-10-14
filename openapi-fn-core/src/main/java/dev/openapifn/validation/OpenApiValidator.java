package dev.openapifn.validation;

import dev.openapifn.http.BodyParser;
import dev.openapifn.http.ContentType;
import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.http.Multipart;
import dev.openapifn.http.ParameterParser;
import dev.openapifn.http.SchemaShapes;
import dev.openapifn.http.UrlCodec;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.model.Header;
import dev.openapifn.model.MediaType;
import dev.openapifn.model.Parameter;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.ParameterStyle;
import dev.openapifn.model.Response;
import dev.openapifn.model.SecurityRequirement;
import dev.openapifn.model.SecurityScheme;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.schema.Direction;
import dev.openapifn.schema.Schema;
import dev.openapifn.schema.SchemaError;
import dev.openapifn.schema.SchemaValidator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates HTTP requests and responses against an OpenAPI description: routing, security
 * credentials, parameters of every style, content negotiation, bodies of all supported media
 * types, response status codes and headers. Instances are immutable and thread-safe.
 *
 * <pre>{@code
 * var validator = OpenApiValidator.of(api);
 * var result = validator.validateRequest(HttpRequest.of("GET", "/pets?limit=500"));
 * result.report().hasErrors();
 * }</pre>
 */
public final class OpenApiValidator {

    /**
     * Validator options.
     *
     * @param levels severities per issue key
     * @param schema schema validation options
     * @param requireServerPrefix whether request paths must start with a server URL path
     *     (otherwise paths are also matched without it)
     * @param headFallsBackToGet whether {@code HEAD} requests use the {@code GET} operation when
     *     no {@code HEAD} operation is described
     * @param basePaths additional path prefixes (such as the path an application mounts the API
     *     on) accepted in front of every operation path
     */
    public record Options(LevelPolicy levels, SchemaValidator.Options schema, boolean requireServerPrefix, boolean headFallsBackToGet,
                          List<String> basePaths) {

        public static final Options DEFAULT = new Options(LevelPolicy.DEFAULT, SchemaValidator.Options.DEFAULT, false, true, List.of());

        public Options {
            basePaths = List.copyOf(basePaths);
        }

        public Options withLevels(LevelPolicy value) {
            return new Options(value, schema, requireServerPrefix, headFallsBackToGet, basePaths);
        }

        public Options withSchema(SchemaValidator.Options value) {
            return new Options(levels, value, requireServerPrefix, headFallsBackToGet, basePaths);
        }

        public Options withRequireServerPrefix(boolean value) {
            return new Options(levels, schema, value, headFallsBackToGet, basePaths);
        }

        public Options withHeadFallsBackToGet(boolean value) {
            return new Options(levels, schema, requireServerPrefix, value, basePaths);
        }

        public Options withBasePaths(List<String> value) {
            return new Options(levels, schema, requireServerPrefix, headFallsBackToGet, value);
        }
    }

    /**
     * The result of validating a request, including the values parsed along the way so that
     * they can be reused for data binding.
     *
     * @param report the findings
     * @param match the matched operation, if routing succeeded
     * @param parameters parsed parameter values keyed by {@code in.name}
     * @param body the parsed body, if one was present and parseable
     */
    public record RequestValidation(ValidationReport report, Optional<OperationMatch> match, Map<String, JsonValue> parameters,
                                    Optional<JsonValue> body) {

        public RequestValidation {
            parameters = Map.copyOf(parameters);
        }
    }

    private final OpenApiDocument api;
    private final Options options;
    private final Router router;
    private final SchemaValidator schemas;
    private final SchemaShapes shapes;
    private final ParameterParser parameters;
    private final BodyParser bodies;

    private OpenApiValidator(OpenApiDocument api, Options options) {
        this.api = api;
        this.options = options;
        this.router = new Router(api, options.requireServerPrefix(), options.headFallsBackToGet(), options.basePaths());
        this.schemas = new SchemaValidator(api.schemas(), options.schema());
        this.shapes = new SchemaShapes(api.schemas());
        this.parameters = new ParameterParser(shapes);
        this.bodies = new BodyParser(shapes);
    }

    public static OpenApiValidator of(OpenApiDocument api) {
        return of(api, Options.DEFAULT);
    }

    public static OpenApiValidator of(OpenApiDocument api, Options options) {
        return new OpenApiValidator(api, options);
    }

    public OpenApiDocument api() {
        return api;
    }

    public Options options() {
        return options;
    }

    public Route route(HttpRequest request) {
        return router.route(request.method(), request.path());
    }

    // ---------------------------------------------------------------------------------------------
    // Requests

    public RequestValidation validateRequest(HttpRequest request) {
        return switch (route(request)) {
            case Route.Found found -> validateRequest(found.match(), request);
            case Route.MethodNotAllowed notAllowed -> failed(IssueKeys.REQUEST_OPERATION_NOT_ALLOWED,
                request.method() + " is not allowed for " + notAllowed.path() + "; allowed: " + notAllowed.allowed(), "method");
            case Route.NotFound ignored -> failed(IssueKeys.REQUEST_PATH_MISSING, "no operation is described for path " + request.path(), "path");
        };
    }

    /** Validates a request against the webhook {@code name} (3.1+), which has no path to route by. */
    public RequestValidation validateWebhookRequest(String name, HttpRequest request) {
        return api.webhooks().stream().filter(op -> op.path().equals(name) && op.method().equals(request.method())).findFirst()
            .map(op -> validateRequest(new OperationMatch(op, Map.of()), request))
            .orElseGet(() -> failed(IssueKeys.REQUEST_PATH_MISSING, "no webhook '" + name + "' with method " + request.method(), "webhook"));
    }

    private RequestValidation failed(String key, String message, String location) {
        var issues = new Issues();
        issues.add(key, message, location);
        return new RequestValidation(issues.report(), Optional.empty(), Map.of(), Optional.empty());
    }

    /** Validates {@code request} against an already matched operation. */
    public RequestValidation validateRequest(OperationMatch match, HttpRequest request) {
        var issues = new Issues();
        var operation = match.operation();
        security(operation, request, issues);
        var values = parameters(match, request, issues);
        var body = requestBody(operation, request, issues);
        accept(operation, request, issues);
        return new RequestValidation(issues.report(), Optional.of(match), values, body);
    }

    /** Whether a request carries a credential for a security scheme, and whether it is well formed. */
    private enum Credential {
        PRESENT,
        MALFORMED,
        MISSING
    }

    /** RFC 6750 bearer tokens (token68). */
    private static final java.util.regex.Pattern TOKEN68 = java.util.regex.Pattern.compile("[A-Za-z0-9._~+/-]+=*");

    /**
     * Checks that one of the security requirements is met by well-formed credentials. Verifying
     * them (passwords, token signatures, scopes, client certificates) is left to authentication.
     */
    private void security(ApiOperation operation, HttpRequest request, Issues issues) {
        var requirements = operation.security();
        if (requirements.isEmpty()) {
            return;
        }
        var results = requirements.stream().map(requirement -> credentials(requirement, request)).toList();
        if (results.contains(Credential.PRESENT)) {
            return;
        }
        var alternatives = requirements.stream().map(r -> String.join(" + ", r.schemes().keySet())).collect(Collectors.joining(" or "));
        if (results.contains(Credential.MALFORMED)) {
            issues.add(IssueKeys.REQUEST_SECURITY_INVALID, "malformed credentials; requires " + alternatives, "security");
        } else {
            issues.add(IssueKeys.REQUEST_SECURITY_MISSING, "missing credentials; requires " + alternatives, "security");
        }
    }

    /** All schemes of a requirement: missing if one is missing, else malformed if one is malformed. */
    private Credential credentials(SecurityRequirement requirement, HttpRequest request) {
        var schemes = api.model().components().securitySchemes();
        var results = requirement.schemes().keySet().stream()
            .map(name -> Optional.ofNullable(schemes.get(name)).map(scheme -> credential(scheme, request)).orElse(Credential.PRESENT)).toList();
        return results.contains(Credential.MISSING) ? Credential.MISSING : results.contains(Credential.MALFORMED) ? Credential.MALFORMED : Credential.PRESENT;
    }

    private static Credential credential(SecurityScheme scheme, HttpRequest request) {
        return switch (scheme) {
            case SecurityScheme.ApiKey key -> {
                var value = switch (key.in()) {
                    case HEADER -> request.headers().first(key.name());
                    case COOKIE -> UrlCodec.parseCookies(request.headers().all("Cookie")).stream().filter(c -> c.getKey().equals(key.name()))
                        .map(Map.Entry::getValue).findFirst();
                    default -> UrlCodec.parseQuery(request.query()).stream().filter(q -> q.getKey().equals(key.name())).map(Map.Entry::getValue).findFirst();
                };
                yield value.map(v -> v.isBlank() ? Credential.MALFORMED : Credential.PRESENT).orElse(Credential.MISSING);
            }
            case SecurityScheme.Http http -> authorization(request, http.scheme());
            case SecurityScheme.OAuth2 ignored -> authorization(request, "bearer");
            case SecurityScheme.OpenIdConnect ignored -> authorization(request, "bearer");
            case SecurityScheme.MutualTls ignored -> Credential.PRESENT;
        };
    }

    /** An {@code Authorization} header of {@code scheme} with well-formed credentials (RFC 7617 basic, RFC 6750 bearer, others non-empty). */
    private static Credential authorization(HttpRequest request, String scheme) {
        var header = request.headers().first("Authorization").map(String::strip).orElse("");
        var space = header.indexOf(' ');
        var name = space < 0 ? header : header.substring(0, space);
        if (!name.equalsIgnoreCase(scheme)) {
            return Credential.MISSING;
        }
        var credentials = space < 0 ? "" : header.substring(space + 1).strip();
        var wellFormed = switch (scheme.toLowerCase(Locale.ROOT)) {
            case "basic" -> basicCredentials(credentials);
            case "bearer" -> TOKEN68.matcher(credentials).matches();
            default -> !credentials.isEmpty();
        };
        return wellFormed ? Credential.PRESENT : Credential.MALFORMED;
    }

    /** RFC 7617: base64 of {@code user-id ":" password}. */
    private static boolean basicCredentials(String credentials) {
        try {
            return new String(java.util.Base64.getDecoder().decode(credentials), java.nio.charset.StandardCharsets.UTF_8).contains(":");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Map<String, JsonValue> parameters(OperationMatch match, HttpRequest request, Issues issues) {
        var sources = ParameterParser.Sources.of(request, match.pathValues());
        var declared = match.operation().parameters();
        var queryNames = declared.stream().filter(p -> p.in() == ParameterLocation.QUERY).map(Parameter::name).collect(Collectors.toSet());
        var values = new LinkedHashMap<String, JsonValue>();
        var claimed = new LinkedHashSet<String>();
        for (var parameter : declared) {
            var prefix = IssueKeys.REQUEST_PARAMETER + "." + parameter.in().value();
            var location = parameter.in().value() + "." + parameter.name();
            var others = new LinkedHashSet<>(queryNames);
            others.remove(parameter.name());
            var parsed = parameters.parse(parameter, sources, others);
            if (parsed.isErr()) {
                issues.add(prefix + ".invalid", parsed.failure().orElseThrow(), location);
                continue;
            }
            var value = parsed.toOptional().orElseThrow();
            if (value.isEmpty()) {
                if (parameter.required()) {
                    issues.add(prefix + ".missing", "required " + parameter.in().value() + " parameter '" + parameter.name() + "' is missing", location);
                }
                continue;
            }
            values.put(location, value.get());
            claimQuery(parameter, value.get(), claimed);
            if (parameter.in() == ParameterLocation.QUERY && !parameter.allowEmptyValue() && value.get().equals(JsonString.of(""))) {
                issues.add(prefix + ".emptyValue", "query parameter '" + parameter.name() + "' must not be empty", location);
                continue;
            }
            parameter.effectiveSchema().ifPresent(schema -> issues.schema(prefix + ".schema", location, schemas.validate(schema, value.get())));
        }
        sources.query().stream().map(Map.Entry::getKey).filter(name -> !claimed.contains(name)).distinct()
            .forEach(name -> issues.add(IssueKeys.REQUEST_QUERY_UNKNOWN, "query parameter '" + name + "' is not described", "query." + name));
        return values;
    }

    /** Records which query names a parameter consumed (exploded objects consume their property names). */
    private static void claimQuery(Parameter parameter, JsonValue value, Set<String> claimed) {
        if (parameter.in() != ParameterLocation.QUERY) {
            return;
        }
        claimed.add(parameter.name());
        if (value instanceof JsonObject object) {
            var deep = parameter.style() == ParameterStyle.DEEP_OBJECT;
            object.keys().forEach(key -> claimed.add(deep ? parameter.name() + "[" + key + "]" : parameter.explode() ? key : parameter.name()));
        }
    }

    private Optional<JsonValue> requestBody(ApiOperation operation, HttpRequest request, Issues issues) {
        var content = request.body().filter(bytes -> bytes.length > 0);
        var declared = operation.operation().requestBody();
        if (declared.isEmpty()) {
            content.ifPresent(bytes -> issues.add(IssueKeys.REQUEST_BODY_UNEXPECTED, "the operation does not describe a request body", "body"));
            return Optional.empty();
        }
        if (content.isEmpty()) {
            if (declared.get().required()) {
                issues.add(IssueKeys.REQUEST_BODY_MISSING, "a request body is required", "body");
            }
            return Optional.empty();
        }
        return body(content.get(), request.contentType(), declared.get().content(), operation.requestDirection(), "request", issues);
    }

    private void accept(ApiOperation operation, HttpRequest request, Issues issues) {
        var accept = request.headers().first("Accept");
        var produced = new LinkedHashSet<String>();
        var responses = operation.operation().responses();
        responses.byStatus().values().forEach(response -> produced.addAll(response.content().keySet()));
        responses.defaultResponse().ifPresent(response -> produced.addAll(response.content().keySet()));
        if (accept.isEmpty() || produced.isEmpty()) {
            return;
        }
        var ranges = acceptRanges(accept.get());
        var acceptable = produced.stream().flatMap(type -> ContentType.parse(type).stream())
            .anyMatch(type -> ranges.stream().anyMatch(range -> range.includes(type) || type.includes(range)));
        if (!acceptable) {
            issues.add(IssueKeys.REQUEST_ACCEPT_NOT_ACCEPTABLE, "none of the response media types " + produced + " is acceptable for '"
                + accept.get() + "'", "header.Accept");
        }
    }

    private static List<ContentType> acceptRanges(String accept) {
        return java.util.Arrays.stream(accept.split(","))
            .flatMap(range -> ContentType.parse(range).stream())
            .filter(range -> !range.parameter("q").filter(q -> q.matches("0(\\.0*)?")).isPresent())
            .toList();
    }

    // ---------------------------------------------------------------------------------------------
    // Responses

    /** Routes {@code request} and validates {@code response} against the matched operation. */
    public ValidationReport validateResponse(HttpRequest request, HttpResponse response) {
        return switch (route(request)) {
            case Route.Found found -> validateResponse(found.match(), response);
            case Route.MethodNotAllowed notAllowed -> validateRequest(request).report();
            case Route.NotFound ignored -> validateRequest(request).report();
        };
    }

    public ValidationReport validateResponse(OperationMatch match, HttpResponse response) {
        var issues = new Issues();
        var declared = match.operation().operation().responses().forStatus(response.status());
        if (declared.isEmpty()) {
            issues.add(IssueKeys.RESPONSE_STATUS_UNKNOWN, "status " + response.status() + " is not described for "
                + match.operation().displayName(), "status");
            return issues.report();
        }
        responseHeaders(declared.get(), response.headers(), issues);
        var content = response.body().filter(bytes -> bytes.length > 0);
        var mediaTypes = declared.get().content();
        if (mediaTypes.isEmpty()) {
            content.ifPresent(bytes -> issues.add(IssueKeys.RESPONSE_BODY_UNEXPECTED, "the response " + response.status()
                + " does not describe content", "body"));
        } else if (content.isEmpty()) {
            issues.add(IssueKeys.RESPONSE_BODY_MISSING, "the response " + response.status() + " requires a body", "body");
        } else {
            body(content.get(), response.contentType(), mediaTypes, match.operation().responseDirection(), "response", issues);
        }
        return issues.report();
    }

    /** Validates a request and its response in one go. */
    public ValidationReport validate(HttpRequest request, HttpResponse response) {
        var requestValidation = validateRequest(request);
        return requestValidation.match().map(match -> requestValidation.report().merge(validateResponse(match, response)))
            .orElse(requestValidation.report());
    }

    private void responseHeaders(Response declared, Headers headers, Issues issues) {
        headers(declared.headers(), headers, IssueKeys.RESPONSE_HEADER, "header.", issues);
    }

    /** Validates {@code headers} against header definitions ({@code Content-Type} is described by the media type instead). */
    private void headers(Map<String, Header> definitions, Headers headers, String key, String locationPrefix, Issues issues) {
        definitions.forEach((name, header) -> {
            if (name.equalsIgnoreCase("Content-Type")) {
                return;
            }
            var location = locationPrefix + name;
            var parameter = header.asParameter(name);
            var value = parameters.parse(parameter, new ParameterParser.Sources(Map.of(), List.of(), "", headers, List.of()), Set.of());
            if (value.isErr()) {
                issues.add(key + ".invalid", value.failure().orElseThrow(), location);
                return;
            }
            var parsed = value.toOptional().orElseThrow();
            if (parsed.isEmpty()) {
                if (header.required()) {
                    issues.add(key + ".missing", "required header '" + name + "' is missing", location);
                }
                return;
            }
            parameter.effectiveSchema().ifPresent(schema -> issues.schema(key + ".schema", location, schemas.validate(schema, parsed.get())));
        });
    }

    /**
     * Checks multipart parts against their Encoding Objects: the part's content type must be one
     * the encoding allows, and the headers it describes must conform. Named parts of
     * {@code multipart/form-data} use {@code encoding}; positional parts use {@code prefixEncoding}
     * and {@code itemEncoding}.
     */
    private void parts(byte[] content, ContentType type, MediaType mediaType, String side, Issues issues) {
        var formData = type.subtype().equals("form-data");
        var parts = Multipart.parse(content, type.parameter("boundary").orElseThrow()).toOptional().orElseThrow();
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            var index = i;
            var encoding = formData ? part.name().map(mediaType.encoding()::get)
                : index < mediaType.prefixEncoding().size() ? Optional.of(mediaType.prefixEncoding().get(index)) : mediaType.itemEncoding();
            var location = "body." + (formData ? part.name().orElse("") : Integer.toString(index));
            encoding.ifPresent(declared -> {
                declared.contentType().ifPresent(allowed -> part.contentType().flatMap(ContentType::parse).ifPresent(actual -> {
                    var ranges = java.util.Arrays.stream(allowed.split(",")).map(String::trim).toList();
                    if (ContentType.bestMatch(ranges, actual).isEmpty()) {
                        issues.add(side + ".body.part.contentType", "part content type '" + actual.essence() + "' is not one of " + ranges, location);
                    }
                }));
                headers(declared.headers(), part.headers(), side + ".body.part.header", location + ".", issues);
            });
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Bodies

    private Optional<JsonValue> body(byte[] content, Optional<String> contentTypeHeader, Map<String, MediaType> mediaTypes, Direction direction,
                                     String side, Issues issues) {
        if (contentTypeHeader.isEmpty()) {
            issues.add(side + ".contentType.missing", "the " + side + " has a body but no Content-Type", "header.Content-Type");
            return Optional.empty();
        }
        var contentType = ContentType.parse(contentTypeHeader.get());
        if (contentType.isEmpty()) {
            issues.add(side + ".contentType.invalid", "malformed Content-Type '" + contentTypeHeader.get() + "'", "header.Content-Type");
            return Optional.empty();
        }
        var range = ContentType.bestMatch(mediaTypes.keySet(), contentType.get());
        if (range.isEmpty()) {
            issues.add(side + ".contentType.notAllowed", "Content-Type '" + contentType.get().essence() + "' is not one of "
                + mediaTypes.keySet(), "header.Content-Type");
            return Optional.empty();
        }
        var mediaType = mediaTypes.get(range.get());
        var parsed = bodies.parse(content, contentType.get(), mediaType);
        if (parsed.isErr()) {
            issues.add(side + ".body.invalid", parsed.failure().orElseThrow(), "body");
            return Optional.empty();
        }
        var value = parsed.toOptional().orElseThrow();
        if (contentType.get().type().equals("multipart")) {
            parts(content, contentType.get(), mediaType, side, issues);
        }
        var key = side + ".body.schema";
        if (mediaType.itemSchema().isPresent() && value instanceof JsonArray items) {
            var itemSchema = mediaType.itemSchema().get();
            for (int i = 0; i < items.size(); i++) {
                issues.schemaAt(key, "body", JsonPointer.of(Integer.toString(i)), schemas.validate(itemSchema, items.values().get(i), direction));
            }
        } else {
            mediaType.schema().ifPresent(schema -> issues.schema(key, "body", schemas.validate(schema, value, direction)));
        }
        return Optional.of(value);
    }

    /** Collects issues with levels from the policy, dropping ignored ones. */
    private final class Issues {
        private final List<ValidationIssue> list = new ArrayList<>();

        void add(String key, String message, String location) {
            var level = options.levels().levelFor(key);
            if (level != Level.IGNORE) {
                list.add(new ValidationIssue(key, level, message, location));
            }
        }

        void schema(String keyPrefix, String location, Collection<SchemaError> errors) {
            schemaAt(keyPrefix, location, JsonPointer.ROOT, errors);
        }

        void schemaAt(String keyPrefix, String location, JsonPointer base, Collection<SchemaError> errors) {
            for (var error : errors) {
                var path = base.append(error.instancePath());
                add(keyPrefix + "." + error.keyword(), describe(error), path.isRoot() ? location : location + path);
            }
        }

        private String describe(SchemaError error) {
            if (error.causes().isEmpty()) {
                return error.message();
            }
            return error.message() + ": " + error.causes().stream().map(SchemaError::toString).collect(Collectors.joining("; "));
        }

        ValidationReport report() {
            return new ValidationReport(list);
        }
    }

    /** Validates a value against a schema; exposed for binding layers that parse values themselves. */
    public List<SchemaError> validateValue(Schema schema, JsonValue value, Direction direction) {
        return schemas.validate(schema, value, direction);
    }
}
