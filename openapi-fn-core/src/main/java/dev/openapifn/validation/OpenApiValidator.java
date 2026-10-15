package dev.openapifn.validation;

import dev.openapifn.fn.Result;
import dev.openapifn.http.BodyParser;
import dev.openapifn.http.ContentType;
import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.http.ItemFramer;
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
import dev.openapifn.model.Encoding;
import dev.openapifn.model.Header;
import dev.openapifn.model.MediaType;
import dev.openapifn.model.Parameter;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.ParameterStyle;
import dev.openapifn.model.Response;
import dev.openapifn.model.SecurityRequirement;
import dev.openapifn.model.SecurityScheme;
import dev.openapifn.security.Credential;
import dev.openapifn.security.CredentialVerifier;
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
     * @param verifier verifies the credentials requests present (by default none is: credentials are
     *     checked for being present and well formed)
     */
    public record Options(LevelPolicy levels, SchemaValidator.Options schema, boolean requireServerPrefix, boolean headFallsBackToGet,
                          List<String> basePaths, CredentialVerifier verifier) {

        public static final Options DEFAULT = new Options(LevelPolicy.DEFAULT, SchemaValidator.Options.DEFAULT, false, true, List.of(),
            CredentialVerifier.NONE);

        public Options {
            basePaths = List.copyOf(basePaths);
        }

        public Options withLevels(LevelPolicy value) {
            return new Options(value, schema, requireServerPrefix, headFallsBackToGet, basePaths, verifier);
        }

        public Options withSchema(SchemaValidator.Options value) {
            return new Options(levels, value, requireServerPrefix, headFallsBackToGet, basePaths, verifier);
        }

        public Options withRequireServerPrefix(boolean value) {
            return new Options(levels, schema, value, headFallsBackToGet, basePaths, verifier);
        }

        public Options withHeadFallsBackToGet(boolean value) {
            return new Options(levels, schema, requireServerPrefix, value, basePaths, verifier);
        }

        public Options withBasePaths(List<String> value) {
            return new Options(levels, schema, requireServerPrefix, headFallsBackToGet, value, verifier);
        }

        public Options withVerifier(CredentialVerifier value) {
            return new Options(levels, schema, requireServerPrefix, headFallsBackToGet, basePaths, value);
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

    private OpenApiValidator(OpenApiValidator validator, Options options) {
        this.api = validator.api;
        this.options = options;
        this.router = validator.router;
        this.schemas = validator.schemas;
        this.shapes = validator.shapes;
        this.parameters = validator.parameters;
        this.bodies = validator.bodies;
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

    /**
     * This validator, verifying credentials with {@code verifier} first (and with the configured
     * verifier for those it declines), such as one knowing who the current request authenticated
     * as. Cheap: everything else is shared.
     */
    public OpenApiValidator withVerifier(CredentialVerifier verifier) {
        return new OpenApiValidator(this, options.withVerifier(verifier.orElse(options.verifier())));
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
        return validateRequest(match, request, false);
    }

    /**
     * Validates everything of a request but its body, for requests whose body is streamed: the
     * {@code Content-Type} is checked, and the items of the body are validated as they arrive with
     * the {@link #requestItems} validator.
     */
    public RequestValidation validateRequestHead(HttpRequest request) {
        return route(request) instanceof Route.Found found ? validateRequestHead(found.match(), request) : validateRequest(request);
    }

    /** Validates everything of a request but its body against an already matched operation, see {@link #validateRequestHead(HttpRequest)}. */
    public RequestValidation validateRequestHead(OperationMatch match, HttpRequest request) {
        return validateRequest(match, request, true);
    }

    private RequestValidation validateRequest(OperationMatch match, HttpRequest request, boolean streamed) {
        var issues = new Issues();
        var operation = match.operation();
        security(operation, request, issues);
        var values = parameters(match, request, issues);
        var body = streamed ? streamedRequestBody(operation, request, issues) : requestBody(operation, request, issues);
        accept(operation, request, issues);
        return new RequestValidation(issues.report(), Optional.of(match), values, body);
    }

    /**
     * A validator for the items of a streamed request body of {@code contentType}, if the operation
     * describes that media type and it is sequential (see {@link ItemFramer#isSequential}).
     */
    public Optional<ItemValidator> requestItems(OperationMatch match, String contentType) {
        return match.operation().operation().requestBody()
            .flatMap(body -> items(body.content(), contentType, match.operation().requestDirection(), "request"));
    }

    /** A validator for the items of a streamed response body, see {@link #requestItems}. */
    public Optional<ItemValidator> responseItems(OperationMatch match, int status, String contentType) {
        return match.operation().operation().responses().forStatus(status)
            .flatMap(response -> items(response.content(), contentType, match.operation().responseDirection(), "response"));
    }

    private Optional<ItemValidator> items(Map<String, MediaType> content, String contentType, Direction direction, String side) {
        return ContentType.parse(contentType).filter(ItemFramer::isSequential)
            .flatMap(type -> ContentType.bestMatch(content.keySet(), type).map(range -> new ItemValidator(type, content.get(range), direction, side)));
    }

    /** How well a request meets a security requirement, from best to worst. */
    private enum Standing {
        SATISFIED(""),
        /** Verified credentials lacking required scopes or roles. */
        FORBIDDEN(IssueKeys.REQUEST_SECURITY_FORBIDDEN),
        /** Credentials the verifier rejected. */
        REJECTED(IssueKeys.REQUEST_SECURITY_INVALID),
        MALFORMED(IssueKeys.REQUEST_SECURITY_INVALID),
        MISSING(IssueKeys.REQUEST_SECURITY_MISSING);

        private final String key;

        Standing(String key) {
            this.key = key;
        }
    }

    /** A standing, with what explains it. */
    private record Check(Standing standing, String detail) {
        static final Check SATISFIED = new Check(Standing.SATISFIED, "");

        Check worse(Check other) {
            return other.standing.compareTo(standing) > 0 ? other : this;
        }
    }

    /** RFC 6750 bearer tokens (token68). */
    private static final java.util.regex.Pattern TOKEN68 = java.util.regex.Pattern.compile("[A-Za-z0-9._~+/-]+=*");

    /**
     * Checks that one of the security requirements is met: by credentials that are present and well
     * formed, that the {@link CredentialVerifier} accepts, and that grant the required scopes (or
     * roles). Missing and rejected credentials answer 401, missing scopes 403.
     */
    private void security(ApiOperation operation, HttpRequest request, Issues issues) {
        var requirements = operation.security();
        if (requirements.isEmpty()) {
            return;
        }
        var best = requirements.stream().map(requirement -> requirement(requirement, request)).min(java.util.Comparator.comparing(Check::standing))
            .orElseThrow();
        var alternatives = requirements.stream().map(r -> String.join(" + ", r.schemes().keySet())).collect(Collectors.joining(" or "));
        if (best.standing() != Standing.SATISFIED) {
            issues.add(best.standing().key, best.detail() + "; requires " + alternatives, "security");
        }
    }

    /** Every scheme of a requirement must be met: the requirement stands as its worst scheme. */
    private Check requirement(SecurityRequirement requirement, HttpRequest request) {
        var schemes = api.model().components().securitySchemes();
        var check = Check.SATISFIED;
        for (var entry : requirement.schemes().entrySet()) {
            var scheme = schemes.get(entry.getKey());
            if (scheme != null) {
                check = check.worse(scheme(entry.getKey(), scheme, entry.getValue(), request));
            }
        }
        return check;
    }

    private Check scheme(String name, SecurityScheme scheme, List<String> scopes, HttpRequest request) {
        if (scheme instanceof SecurityScheme.MutualTls && request.clientCertificates().isEmpty()) {
            return Check.SATISFIED; // TLS was terminated before the application (by a proxy), which checked the client certificate.
        }
        return credential(name, scheme, request).fold(credential -> verify(credential, scopes),
            standing -> new Check(standing, standing == Standing.MISSING ? "missing credentials" : "malformed credentials"));
    }

    /** What the verifier says of {@code credential}; credentials it declines are accepted as they are. */
    private Check verify(Credential credential, List<String> scopes) {
        return options.verifier().verify(credential).map(result -> result.fold(
                grant -> grant.scopes().containsAll(scopes) ? Check.SATISFIED : new Check(Standing.FORBIDDEN, "insufficient scopes: '"
                    + credential.scheme() + "' grants " + new java.util.TreeSet<>(grant.scopes()) + " but " + scopes + " are required"),
                reason -> new Check(Standing.REJECTED, "credentials for '" + credential.scheme() + "' rejected: " + reason)))
            .orElse(Check.SATISFIED);
    }

    /** The credential a request presents for a scheme, or whether it is missing or malformed. */
    private static Result<Credential, Standing> credential(String name, SecurityScheme scheme, HttpRequest request) {
        return switch (scheme) {
            case SecurityScheme.ApiKey key -> {
                var value = switch (key.in()) {
                    case HEADER -> request.headers().first(key.name());
                    case COOKIE -> UrlCodec.parseCookies(request.headers().all("Cookie")).stream().filter(c -> c.getKey().equals(key.name()))
                        .map(Map.Entry::getValue).findFirst();
                    default -> UrlCodec.parseQuery(request.query()).stream().filter(q -> q.getKey().equals(key.name())).map(Map.Entry::getValue).findFirst();
                };
                yield value.map(v -> v.isBlank() ? Result.<Credential, Standing>err(Standing.MALFORMED) : Result.<Credential, Standing>ok(new Credential.ApiKey(name, v)))
                    .orElse(Result.err(Standing.MISSING));
            }
            case SecurityScheme.Http http -> authorization(name, request, http.scheme());
            case SecurityScheme.OAuth2 ignored -> authorization(name, request, "bearer");
            case SecurityScheme.OpenIdConnect ignored -> authorization(name, request, "bearer");
            case SecurityScheme.MutualTls ignored -> {
                var chain = request.clientCertificates().orElseThrow();
                yield chain.isEmpty() ? Result.err(Standing.MISSING) : Result.ok(new Credential.ClientCertificate(name, chain));
            }
        };
    }

    /** An {@code Authorization} header of {@code scheme} with well-formed credentials (RFC 7617 basic, RFC 6750 bearer, others non-empty). */
    private static Result<Credential, Standing> authorization(String name, HttpRequest request, String scheme) {
        var header = request.headers().first("Authorization").map(String::strip).orElse("");
        var space = header.indexOf(' ');
        var authScheme = space < 0 ? header : header.substring(0, space);
        if (!authScheme.equalsIgnoreCase(scheme)) {
            return Result.err(Standing.MISSING);
        }
        var credentials = space < 0 ? "" : header.substring(space + 1).strip();
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "basic" -> basic(name, credentials);
            case "bearer" -> TOKEN68.matcher(credentials).matches() ? Result.ok(new Credential.Bearer(name, credentials)) : Result.err(Standing.MALFORMED);
            default -> credentials.isEmpty() ? Result.err(Standing.MALFORMED) : Result.ok(new Credential.Authorization(name, scheme, credentials));
        };
    }

    /** RFC 7617: base64 of {@code user-id ":" password}. */
    private static Result<Credential, Standing> basic(String name, String credentials) {
        try {
            var decoded = new String(java.util.Base64.getDecoder().decode(credentials), java.nio.charset.StandardCharsets.UTF_8);
            var colon = decoded.indexOf(':');
            return colon < 0 ? Result.err(Standing.MALFORMED) : Result.ok(new Credential.Basic(name, decoded.substring(0, colon), decoded.substring(colon + 1)));
        } catch (IllegalArgumentException e) {
            return Result.err(Standing.MALFORMED);
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

    /** The head of a request whose body is streamed: the body is expected, its content type must be described. */
    private Optional<JsonValue> streamedRequestBody(ApiOperation operation, HttpRequest request, Issues issues) {
        operation.operation().requestBody().ifPresentOrElse(declared -> mediaType(request.contentType(), declared.content(), "request", issues),
            () -> issues.add(IssueKeys.REQUEST_BODY_UNEXPECTED, "the operation does not describe a request body", "body"));
        return Optional.empty();
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
        return validateResponse(match, response, false);
    }

    /**
     * Validates everything of a response but its body, for responses whose body is streamed: the
     * status, the headers and the {@code Content-Type}. The items of the body are validated as they
     * are produced with the {@link #responseItems} validator.
     */
    public ValidationReport validateResponseHead(OperationMatch match, HttpResponse response) {
        return validateResponse(match, response, true);
    }

    private ValidationReport validateResponse(OperationMatch match, HttpResponse response, boolean streamed) {
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
        if (streamed) {
            if (!mediaTypes.isEmpty()) {
                mediaType(response.contentType(), mediaTypes, "response", issues);
            }
        } else if (mediaTypes.isEmpty()) {
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
        if (type.subtype().equals("form-data")) {
            Multipart.parse(content, type.parameter("boundary").orElseThrow()).toOptional().orElseThrow()
                .forEach(part -> part(part, part.name().map(mediaType.encoding()::get), "body." + part.name().orElse(""), side, issues));
            return;
        }
        var parts = ItemFramer.of(type).orElseThrow().frames(content).stream().filter(frame -> frame.content().length > 0)
            .map(frame -> Multipart.part(frame.content()).toOptional().orElseThrow()).toList();
        for (int i = 0; i < parts.size(); i++) {
            part(parts.get(i), positionalEncoding(mediaType, i), "body." + i, side, issues);
        }
    }

    private static Optional<Encoding> positionalEncoding(MediaType mediaType, int index) {
        return index < mediaType.prefixEncoding().size() ? Optional.of(mediaType.prefixEncoding().get(index)) : mediaType.itemEncoding();
    }

    private void part(Multipart.Part part, Optional<Encoding> encoding, String location, String side, Issues issues) {
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

    // ---------------------------------------------------------------------------------------------
    // Bodies

    /** The content type of a message and the media type describing it, reporting when there is none. */
    private Optional<Map.Entry<ContentType, MediaType>> mediaType(Optional<String> contentTypeHeader, Map<String, MediaType> mediaTypes, String side,
                                                                 Issues issues) {
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
        return Optional.of(Map.entry(contentType.get(), mediaTypes.get(range.get())));
    }

    private Optional<JsonValue> body(byte[] content, Optional<String> contentTypeHeader, Map<String, MediaType> mediaTypes, Direction direction,
                                     String side, Issues issues) {
        var described = mediaType(contentTypeHeader, mediaTypes, side, issues);
        if (described.isEmpty()) {
            return Optional.empty();
        }
        var contentType = described.get().getKey();
        var mediaType = described.get().getValue();
        var parsed = bodies.parse(content, contentType, mediaType);
        if (parsed.isErr()) {
            issues.add(side + ".body.invalid", parsed.failure().orElseThrow(), "body");
            return Optional.empty();
        }
        var value = parsed.toOptional().orElseThrow();
        if (contentType.type().equals("multipart")) {
            parts(content, contentType, mediaType, side, issues);
        }
        var key = side + ".body.schema";
        // A sequence's items conform to the itemSchema, and the sequence as a whole (an array) to the schema.
        if (mediaType.itemSchema().isPresent() && value instanceof JsonArray items) {
            var itemSchema = mediaType.itemSchema().get();
            for (int i = 0; i < items.size(); i++) {
                issues.schemaAt(key, "body", JsonPointer.of(Integer.toString(i)), schemas.validate(itemSchema, items.values().get(i), direction));
            }
        }
        mediaType.schema().ifPresent(schema -> issues.schema(key, "body", schemas.validate(schema, value, direction)));
        return Optional.of(value);
    }

    /**
     * An item of a streamed body.
     *
     * @param value the item, empty for frames without one (such as blank lines or comments) and
     *     for items that cannot be parsed
     * @param report the findings for this item
     */
    public record Item(Optional<JsonValue> value, ValidationReport report) {
    }

    /**
     * Validates the items of one streamed body as they arrive: each frame of the body's
     * {@link #framer()} is parsed and validated against the {@code itemSchema} and the item of the
     * {@code schema} at the item's position, and, for multipart parts, against the item encoding.
     * Constraints of the {@code schema} on the sequence as a whole are checked as items pass:
     * {@code maxItems}, {@code uniqueItems} (which remembers the items seen) and {@code maxContains}
     * on the item that violates them, {@code minItems} and {@code contains} / {@code minContains}
     * with the last frame. Findings are located at {@code body/<index>}, or at {@code body} for the
     * sequence. Instances are not thread-safe.
     */
    public final class ItemValidator {

        private final ContentType type;
        private final MediaType mediaType;
        private final Direction direction;
        private final String side;
        private final List<Schema> sequence;
        private final java.util.Set<JsonValue> seen = new java.util.HashSet<>();
        private final Map<Schema, Integer> contained = new LinkedHashMap<>();
        private int index;

        private ItemValidator(ContentType type, MediaType mediaType, Direction direction, String side) {
            this.type = type;
            this.mediaType = mediaType;
            this.direction = direction;
            this.side = side;
            this.sequence = mediaType.schema().map(OpenApiValidator.this::applying).orElse(List.of()).stream()
                .filter(schema -> SEQUENCE_KEYWORDS.stream().anyMatch(schema::has)).toList();
        }

        /** The content type of the body. */
        public ContentType contentType() {
            return type;
        }

        /** A new framer for the body. */
        public ItemFramer framer() {
            return ItemFramer.of(type).orElseThrow();
        }

        /** Parses and validates the item of the next frame. */
        public Item validate(ItemFramer.Frame frame) {
            var issues = new Issues();
            var parsed = bodies.item(frame, type, mediaType, index);
            if (parsed.isErr()) {
                issues.add(side + ".body.invalid", parsed.failure().orElseThrow(), "body");
                return new Item(Optional.empty(), issues.report());
            }
            var value = parsed.toOptional().orElseThrow();
            if (value.isPresent()) {
                var position = index++;
                if (type.type().equals("multipart")) {
                    Multipart.part(frame.content()).peek(part -> part(part, positionalEncoding(mediaType, position), "body." + position, side, issues));
                }
                var at = JsonPointer.of(Integer.toString(position));
                var itemSchema = mediaType.itemSchema().map(api.schemas()::deref);
                itemSchema.ifPresent(schema -> issues.schemaAt(side + ".body.schema", "body", at, schemas.validate(schema, value.get(), direction)));
                mediaType.schema().flatMap(schema -> shapes.item(schema, position)).map(api.schemas()::deref)
                    .filter(schema -> itemSchema.filter(same -> same.location().equals(schema.location())).isEmpty())
                    .ifPresent(schema -> issues.schemaAt(side + ".body.schema", "body", at, schemas.validate(schema, value.get(), direction)));
                sequenceItem(value.get(), position, issues);
            }
            if (frame.last()) {
                sequenceEnd(issues);
            }
            return new Item(value, issues.report());
        }

        /** The constraints on the sequence that an item can violate as it arrives. */
        private void sequenceItem(JsonValue value, int position, Issues issues) {
            var location = "body/" + position;
            for (var schema : sequence) {
                var key = side + ".body.schema.";
                schema.number("maxItems").filter(max -> position == max.intValue())
                    .ifPresent(max -> issues.add(key + "maxItems", "must have at most " + max.intValue() + " items", location));
                if (schema.object().bool("uniqueItems").orElse(false) && seen.contains(value)) {
                    issues.add(key + "uniqueItems", "must not contain duplicate items", location);
                }
                schema.subschema("contains").filter(contains -> schemas.validate(contains, value, direction).isEmpty()).ifPresent(contains -> {
                    var matched = contained.merge(schema, 1, Integer::sum);
                    schema.number("maxContains").filter(max -> matched == max.intValue() + 1).ifPresent(max ->
                        issues.add(key + "maxContains", "must contain at most " + max.intValue() + " matching items", location));
                });
            }
            seen.add(value);
        }

        /** The constraints on the sequence that are only known at its end. */
        private void sequenceEnd(Issues issues) {
            for (var schema : sequence) {
                var key = side + ".body.schema.";
                schema.number("minItems").filter(min -> index < min.intValue())
                    .ifPresent(min -> issues.add(key + "minItems", "must have at least " + min.intValue() + " items", "body"));
                if (schema.has("contains")) {
                    var min = schema.number("minContains").map(java.math.BigDecimal::intValue).orElse(1);
                    if (contained.getOrDefault(schema, 0) < min) {
                        issues.add(key + (min == 1 ? "contains" : "minContains"), min == 1 ? "must contain at least one matching item"
                            : "must contain at least " + min + " matching items", "body");
                    }
                }
            }
        }
    }

    /** The keywords constraining a sequence as a whole. */
    private static final List<String> SEQUENCE_KEYWORDS = List.of("maxItems", "minItems", "uniqueItems", "contains");

    /** A schema and the schemas that apply with it wherever it applies: its {@code $ref} and {@code allOf} chain. */
    private List<Schema> applying(Schema schema) {
        var result = new ArrayList<Schema>();
        applying(schema, result, new java.util.HashSet<>());
        return result;
    }

    private void applying(Schema schema, List<Schema> result, java.util.Set<java.net.URI> seen) {
        var resolved = api.schemas().deref(schema);
        if (seen.add(resolved.location())) {
            result.add(resolved);
            resolved.subschemaList("allOf").forEach(member -> applying(member, result, seen));
        }
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
