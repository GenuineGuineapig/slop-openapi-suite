package dev.openapifn.codegen.api;

import dev.openapifn.codegen.java.Imports;
import dev.openapifn.codegen.java.Names;
import dev.openapifn.codegen.java.Source;
import dev.openapifn.codegen.java.TypeRef;
import dev.openapifn.codegen.java.TypeRef.ClassName;
import dev.openapifn.codegen.model.ModelDef.Property;
import dev.openapifn.example.ExampleGenerator;
import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.ContentType;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.http.SchemaShapes;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonValue;
import dev.openapifn.model.Parameter;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.SecurityScheme;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.schema.Direction;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OperationMatch;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders a {@code @WebMvcTest} (or, for WebFlux, a {@code @WebFluxTest}) per API group. For every
 * operation, one test stubs the delegate with an example response, performs a valid example request
 * and asserts that the exchange conforms to the API description; another checks that requests
 * lacking required input are rejected before reaching the delegate.
 */
public final class TestRenderer {

    private static final ClassName WEB_MVC_TEST = ClassName.of("org.springframework.boot.webmvc.test.autoconfigure", "WebMvcTest");
    private static final ClassName AUTOWIRED = ClassName.of("org.springframework.beans.factory.annotation", "Autowired");
    private static final ClassName MOCKITO_BEAN = ClassName.of("org.springframework.test.context.bean.override.mockito", "MockitoBean");
    private static final ClassName MOCK_MVC = ClassName.of("org.springframework.test.web.servlet", "MockMvc");
    private static final ClassName TEST = ClassName.of("org.junit.jupiter.api", "Test");
    private static final ClassName DISABLED = ClassName.of("org.junit.jupiter.api", "Disabled");
    private static final ClassName JSON_MAPPER = ClassName.of("tools.jackson.databind.json", "JsonMapper");
    private static final ClassName HTTP_METHOD = ClassName.of("org.springframework.http", "HttpMethod");
    private static final ClassName URI_TYPE = ClassName.of("java.net", "URI");
    private static final ClassName COOKIE = ClassName.of("jakarta.servlet.http", "Cookie");
    private static final ClassName MOCK_PART = ClassName.of("org.springframework.mock.web", "MockPart");
    private static final ClassName MEDIA_TYPE = ClassName.of("org.springframework.http", "MediaType");
    private static final ClassName MATCHERS = ClassName.of("dev.openapifn.spring.test", "OpenApiMatchers");
    private static final ClassName WEB_FLUX_TEST = ClassName.of("org.springframework.boot.webflux.test.autoconfigure", "WebFluxTest");
    private static final ClassName WEB_TEST_CLIENT = ClassName.of("org.springframework.test.web.reactive.server", "WebTestClient");
    private static final ClassName ASSERTIONS = ClassName.of("dev.openapifn.spring.test", "OpenApiExchangeAssertions");
    private static final ClassName MULTIPART_BODY_BUILDER = ClassName.of("org.springframework.http.client", "MultipartBodyBuilder");
    private static final ClassName BODY_INSERTERS = ClassName.of("org.springframework.web.reactive.function", "BodyInserters");
    private static final ClassName STANDARD_CHARSETS = ClassName.of("java.nio.charset", "StandardCharsets");
    /** The boundary of positional multipart example bodies (fixed, so that generated tests are stable). */
    private static final String BOUNDARY = "openapi-fn-example";

    private final OpenApiDocument api;
    private final GeneratorNames names;
    private final boolean reactive;
    private final ExampleGenerator examples;
    private final SchemaShapes shapes;
    private final OpenApiValidator validator;

    /** @param reactive whether to render {@code @WebFluxTest}s with {@code WebTestClient} rather than {@code @WebMvcTest}s */
    public TestRenderer(OpenApiDocument api, GeneratorNames names, boolean reactive) {
        this.api = api;
        this.names = names;
        this.reactive = reactive;
        this.examples = new ExampleGenerator(api.schemas());
        this.shapes = new SchemaShapes(api.schemas());
        this.validator = OpenApiValidator.of(api);
    }

    /** The test class for {@code group}. */
    public String test(String group, List<OperationModel> operations) {
        var name = names.controllerTest(group);
        var imports = new Imports(name, Set.of());
        var out = new Source();
        out.javadoc("Contract tests of the generated {@code " + names.controller(group).simpleName() + "}.");
        out.line("@" + imports.ref(dev.openapifn.codegen.model.ModelRenderer.GENERATED) + "(\"openapi-fn\")");
        out.line("@SuppressWarnings(\"deprecation\")");
        out.line("@" + imports.ref(reactive ? WEB_FLUX_TEST : WEB_MVC_TEST) + "(" + imports.ref(names.controller(group)) + ".class)");
        out.open("class " + name.simpleName());
        out.blank().line("@" + imports.ref(AUTOWIRED)).line(reactive ? "private " + imports.ref(WEB_TEST_CLIENT) + " client;"
            : "private " + imports.ref(MOCK_MVC) + " mvc;");
        out.blank().line("@" + imports.ref(AUTOWIRED)).line("private " + imports.ref(ApiRenderer.OPENAPI_RUNTIMES) + " runtimes;");
        out.blank().line("@" + imports.ref(AUTOWIRED)).line("private " + imports.ref(JSON_MAPPER) + " mapper;");
        out.blank().line("@" + imports.ref(MOCKITO_BEAN)).line("private " + imports.ref(names.api(group)) + " api;");
        var receivers = receivers(operations);
        for (var operation : operations) {
            exchangeTest(operation, receivers.getOrDefault(operation.id(), operation), out, imports);
            if (!receivedByAnother(operation, operations)) {
                rejectionTest(operation, out, imports);
            }
        }
        out.close();
        var mockito = List.of("import static org.mockito.ArgumentMatchers.any;", "import static org.mockito.BDDMockito.given;",
            "import static org.mockito.BDDMockito.then;");
        var statics = String.join("\n", reactive ? mockito : java.util.stream.Stream.concat(mockito.stream(), java.util.stream.Stream.of(
            "import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;",
            "import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;",
            "import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;")).toList());
        return "package " + name.packageName() + ";\n\n" + statics + "\n\n" + imports.declarations() + "\n\n" + out;
    }

    // ---------------------------------------------------------------------------------------------
    // The exchange test

    /**
     * The webhooks and callbacks whose example requests another one receives: one sent to the same
     * URL with the same method, declared earlier, for which the request is valid too (by operation id).
     */
    private Map<String, OperationModel> receivers(List<OperationModel> operations) {
        var shared = new LinkedHashMap<List<String>, List<OperationModel>>();
        operations.stream().filter(OperationModel::outbound)
            .forEach(op -> shared.computeIfAbsent(List.of(op.receiverPath(), op.operation().method()), key -> new ArrayList<>()).add(op));
        var receivers = new java.util.HashMap<String, OperationModel>();
        shared.values().forEach(group -> {
            for (int i = 1; i < group.size(); i++) {
                var earlier = group.subList(0, i);
                var operation = group.get(i);
                requestExpression(operation, true).flatMap(TestRenderer::message).flatMap(message -> earlier.stream()
                        .filter(op -> !validator.validateRequest(new OperationMatch(op.operation(), Map.of()), message).report().hasErrors()).findFirst())
                    .ifPresent(receiver -> receivers.put(operation.id(), receiver));
            }
        });
        return receivers;
    }

    /**
     * Whether a request of {@code operation} lacking its required input is a valid request of
     * another webhook or callback sent to the same URL with the same method, which then receives it.
     */
    private boolean receivedByAnother(OperationModel operation, List<OperationModel> operations) {
        // A group holds either webhooks and callbacks or path operations.
        var others = operations.stream().filter(other -> other != operation && other.outbound() && other.receiverPath().equals(operation.receiverPath())
            && other.operation().method().equals(operation.operation().method())).toList();
        return !others.isEmpty() && requestExpression(operation, false).flatMap(TestRenderer::message).filter(message -> others.stream()
            .anyMatch(other -> !validator.validateRequest(new OperationMatch(other.operation(), Map.of()), message).report().hasErrors())).isPresent();
    }

    /** {@code request} as a message to validate, unless its body is binary or multipart (whose examples are placeholders). */
    private static Optional<HttpRequest> message(ExampleRequest request) {
        var message = HttpRequest.of(request.method(), request.uri());
        for (var header : request.headers().entrySet()) {
            message = message.withHeader(header.getKey(), header.getValue());
        }
        if (!request.cookies().isEmpty()) {
            message = message.withHeader("Cookie", ParameterSerializer.cookieHeader(request.cookies()));
        }
        var body = request.body();
        if (body.isEmpty()) {
            return Optional.of(message);
        }
        return body.get() instanceof TextBody text ? Optional.of(message.withBody(text.contentType(), text.text())) : Optional.empty();
    }

    /** The exchange of {@code operation}'s example request, which {@code receiver} receives (another webhook or callback, when it cannot tell them apart). */
    private void exchangeTest(OperationModel operation, OperationModel receiver, Source out, Imports imports) {
        var variant = stubbedVariant(receiver);
        var status = variant.fixedStatus() ? Integer.parseInt(variant.key()) : variant.key().equals("default") ? 200
            : Integer.parseInt(variant.key().charAt(0) + "00");
        var stub = responseExpression(receiver, variant, status, imports);
        var request = requestExpression(operation, true);
        out.blank().line("@" + imports.ref(TEST));
        if (stub.isEmpty() || request.isEmpty()) {
            out.line("@" + imports.ref(DISABLED) + "(\"No valid example could be derived; add examples to the API description\")");
        }
        out.open("void " + operation.method() + "() throws Exception");
        if (receiver != operation) {
            out.line("// Sent to the URL of " + receiver.method() + " with its method, and valid for it too: " + receiver.method() + " receives it.");
        }
        var call = receiver.method() + "(" + (receiver.request().isPresent() ? "any()" : "") + ")";
        var stubbed = reactive ? stub.map(response -> imports.ref(TypeRef.MONO) + ".just(" + response + ")") : stub;
        out.line("given(api." + call + ").willReturn(" + stubbed.orElse("null") + ");");
        var conforms = "conformsTo(runtimes.get(" + imports.ref(names.spec()) + ".NAME)" + (receiver.outbound() ? ", " + Names.literal(receiver.id()) : "") + ")";
        var example = request.orElse(ExampleRequest.ROOT);
        if (reactive) {
            perform(example, out, imports);
            out.line("    .expectStatus().isEqualTo(" + status + ")");
            out.line("    .expectBody().consumeWith(" + imports.ref(ASSERTIONS) + "." + conforms + ");");
        } else {
            out.line("mvc.perform(" + mockMvc(example, imports) + ")");
            out.line("    .andExpect(status().is(" + status + "))");
            out.line("    .andExpect(" + imports.ref(MATCHERS) + "." + conforms + ");");
        }
        out.line("then(api).should()." + call + ";");
        out.close();
    }

    /** The first fixed 2xx variant, else the first variant. */
    private static OperationModel.VariantModel stubbedVariant(OperationModel operation) {
        var primaries = operation.variants().stream().filter(v -> !v.alternative()).toList();
        return primaries.stream().filter(v -> v.fixedStatus() && v.key().startsWith("2")).findFirst()
            .or(() -> primaries.stream().filter(v -> v.key().startsWith("2")).findFirst())
            .orElse(primaries.getFirst());
    }

    private Optional<String> responseExpression(OperationModel operation, OperationModel.VariantModel variant, int status, Imports imports) {
        var arguments = new ArrayList<String>();
        if (!variant.fixedStatus()) {
            arguments.add(Integer.toString(status));
        }
        if (variant.body().isPresent()) {
            var body = variant.body().get();
            var mediaType = OperationModel.primary(variant.response().content()).orElseThrow();
            var value = value(body, variant.response().content().get(mediaType).itemSchema(), operation.operation().responseDirection(), imports,
                concrete(mediaType));
            if (value.isEmpty()) {
                return Optional.empty();
            }
            arguments.add(value.get());
        }
        for (var header : variant.headers().values()) {
            if (header.required()) {
                var value = value(header, Optional.empty(), operation.operation().responseDirection(), imports, "text/plain");
                if (value.isEmpty()) {
                    return Optional.empty();
                }
                arguments.add(value.get());
            } else {
                arguments.add("null");
            }
        }
        return Optional.of("new " + imports.ref(operation.response()) + "." + variant.name() + "(" + String.join(", ", arguments) + ")");
    }

    /** A Java expression producing an example of {@code property}'s type (a list of item examples for sequential media types). */
    private Optional<String> value(Property property, Optional<dev.openapifn.schema.Schema> itemSchema, Direction direction, Imports imports,
                                   String mediaType) {
        var type = property.type();
        if (type.equals(ApiRenderer.FILE_PART)) {
            return Optional.of(imports.ref(ApiRenderer.FILE_PART) + ".of(\"example.bin\", " + Names.literal(mediaType)
                + ", \"example\".getBytes(" + imports.ref(STANDARD_CHARSETS) + ".UTF_8))");
        }
        var example = itemSchema.isPresent() ? examples.example(itemSchema.get(), direction).map(JsonArray::of).map(JsonValue.class::cast)
            : examples.example(property.schema(), direction);
        var streamed = OperationModel.streamed(type);
        return example.map(json -> type.equals(TypeRef.STRING) ? Names.literal(json.asString().orElse(json.toJson()))
            : streamed.isPresent() ? stream(type, "mapper.readValue(" + Names.literal(json.toJson()) + ", "
                + ApiRenderer.token(TypeRef.LIST.of(streamed.get()), imports) + ")", imports)
            : "mapper.readValue(" + Names.literal(json.toJson()) + ", " + ApiRenderer.token(type, imports) + ")");
    }

    /** A list expression as the stream type of a streamed body: a {@code Stream}, or a {@code Flux} for WebFlux. */
    private static String stream(TypeRef type, String list, Imports imports) {
        return ((TypeRef.Parameterized) type).raw().equals(TypeRef.FLUX) ? imports.ref(TypeRef.FLUX) + ".fromIterable(" + list + ")" : list + ".stream()";
    }

    // ---------------------------------------------------------------------------------------------
    // The request

    /** An example request. */
    private record ExampleRequest(String method, String uri, Map<String, String> headers, List<Map.Entry<String, String>> cookies,
                                  Optional<ExampleBody> body) {

        static final ExampleRequest ROOT = new ExampleRequest("GET", "/", Map.of(), List.of(), Optional.empty());
    }

    /** An example body: text, a binary placeholder or multipart parts. */
    private sealed interface ExampleBody {
    }

    private record TextBody(String contentType, String text) implements ExampleBody {
    }

    private record BinaryBody(String contentType) implements ExampleBody {
    }

    private record PartsBody(List<ExamplePart> parts) implements ExampleBody {
    }

    /** A part: binary parts have a file name; the content type is set when the encoding (or JSON content) asks for one. */
    private record ExamplePart(String name, Optional<String> filename, String text, Optional<String> contentType) {
    }

    /** A MockMvc request builder for {@code request}. */
    private String mockMvc(ExampleRequest request, Imports imports) {
        var parts = request.body().filter(PartsBody.class::isInstance).map(PartsBody.class::cast);
        var expression = new StringBuilder((parts.isPresent() ? "multipart(" : "request(") + imports.ref(HTTP_METHOD) + ".valueOf("
            + Names.literal(request.method()) + "), " + imports.ref(URI_TYPE) + ".create(" + Names.literal(request.uri()) + "))");
        request.headers().forEach((header, value) -> expression.append("\n            .header(").append(Names.literal(header)).append(", ")
            .append(Names.literal(value)).append(")"));
        request.cookies().forEach(cookie -> expression.append("\n            .cookie(new ").append(imports.ref(COOKIE)).append("(")
            .append(Names.literal(cookie.getKey())).append(", ").append(Names.literal(cookie.getValue())).append("))"));
        switch (request.body().orElse(null)) {
            case TextBody text -> expression.append("\n            .contentType(").append(Names.literal(text.contentType())).append(")\n            .content(")
                .append(Names.literal(text.text())).append(")");
            case BinaryBody binary -> expression.append("\n            .contentType(").append(Names.literal(binary.contentType()))
                .append(")\n            .content(\"example\".getBytes(").append(imports.ref(STANDARD_CHARSETS)).append(".UTF_8))");
            case PartsBody body -> body.parts().forEach(part -> expression.append("\n            .part(new ").append(imports.ref(MOCK_PART)).append("(")
                .append(Names.literal(part.name())).append(", ")
                .append(part.filename().or(() -> part.contentType().map(type -> "")).map(filename -> filename.isEmpty() ? "null, " : Names.literal(filename) + ", ")
                    .orElse(""))
                .append(Names.literal(part.text())).append(".getBytes(").append(imports.ref(STANDARD_CHARSETS)).append(".UTF_8)")
                .append(part.contentType().map(type -> ", " + imports.ref(MEDIA_TYPE) + ".parseMediaType(" + Names.literal(type) + ")").orElse(""))
                .append("))"));
            case null -> {
            }
        }
        return expression.toString();
    }

    /** Statements sending {@code request} with the {@code WebTestClient}, up to {@code .exchange()}. */
    private void perform(ExampleRequest request, Source out, Imports imports) {
        var parts = request.body().filter(PartsBody.class::isInstance).map(PartsBody.class::cast);
        parts.ifPresent(body -> {
            out.line("var parts = new " + imports.ref(MULTIPART_BODY_BUILDER) + "();");
            for (var part : body.parts()) {
                var binary = part.filename().isPresent() || part.contentType().filter(type -> !type.startsWith("text/")).isPresent();
                out.line("parts.part(" + Names.literal(part.name()) + ", " + Names.literal(part.text())
                    + (binary ? ".getBytes(" + imports.ref(STANDARD_CHARSETS) + ".UTF_8)" : "") + ")"
                    + part.filename().map(filename -> ".filename(" + Names.literal(filename) + ")").orElse("")
                    + part.contentType().map(type -> ".contentType(" + imports.ref(MEDIA_TYPE) + ".parseMediaType(" + Names.literal(type) + "))").orElse("")
                    + ";");
            }
        });
        out.line("client.method(" + imports.ref(HTTP_METHOD) + ".valueOf(" + Names.literal(request.method()) + ")).uri(" + imports.ref(URI_TYPE)
            + ".create(" + Names.literal(request.uri()) + "))");
        request.headers().forEach((header, value) -> out.line("    .header(" + Names.literal(header) + ", " + Names.literal(value) + ")"));
        request.cookies().forEach(cookie -> out.line("    .cookie(" + Names.literal(cookie.getKey()) + ", " + Names.literal(cookie.getValue()) + ")"));
        switch (request.body().orElse(null)) {
            case TextBody text -> out.line("    .contentType(" + imports.ref(MEDIA_TYPE) + ".parseMediaType(" + Names.literal(text.contentType())
                + ")).bodyValue(" + Names.literal(text.text()) + ".getBytes(" + imports.ref(STANDARD_CHARSETS) + ".UTF_8))");
            case BinaryBody binary -> out.line("    .contentType(" + imports.ref(MEDIA_TYPE) + ".parseMediaType(" + Names.literal(binary.contentType())
                + ")).bodyValue(\"example\".getBytes(" + imports.ref(STANDARD_CHARSETS) + ".UTF_8))");
            case PartsBody ignored -> out.line("    .body(" + imports.ref(BODY_INSERTERS) + ".fromMultipartData(parts.build()))");
            case null -> {
            }
        }
        out.line("    .exchange()");
    }

    /** An example request; {@code complete} includes optional-but-exemplified input and the body. */
    private Optional<ExampleRequest> requestExpression(OperationModel operation, boolean complete) {
        var op = operation.operation();
        var path = new StringBuilder(names.basePath().equals("/") || operation.outbound() ? "" : names.basePath());
        var template = operation.outbound() ? operation.receiverPath() : op.path();
        var query = new ArrayList<Map.Entry<String, String>>();
        var headers = new LinkedHashMap<String, String>();
        var cookies = new ArrayList<Map.Entry<String, String>>();
        var values = new LinkedHashMap<Parameter, JsonValue>();
        String rawQuery = null;
        for (var parameter : op.parameters()) {
            if (parameter.in() != ParameterLocation.PATH && !(complete && parameter.required())) {
                continue;
            }
            var example = parameter.example().or(() -> parameter.examples().values().stream().flatMap(e -> e.data().stream()).findFirst())
                .or(() -> parameter.effectiveSchema().flatMap(schema -> examples.example(schema, op.requestDirection())));
            if (example.isEmpty()) {
                return Optional.empty();
            }
            values.put(parameter, example.get());
        }
        for (var entry : values.entrySet()) {
            var parameter = entry.getKey();
            var spec = ParameterSerializer.Spec.of(parameter);
            var value = entry.getValue();
            switch (parameter.in()) {
                case PATH -> template = template.replace("{" + parameter.name() + "}", ParameterSerializer.path(spec, value));
                case QUERY -> query.addAll(ParameterSerializer.query(spec, value));
                case QUERYSTRING -> rawQuery = ParameterSerializer.querystring(spec, value);
                case HEADER -> headers.put(parameter.name(), ParameterSerializer.header(spec, value));
                default -> cookies.addAll(ParameterSerializer.cookie(spec, value)); // COOKIE
            }
        }
        security(op.security(), headers, query, cookies);
        path.append(template);
        var queryString = rawQuery != null ? rawQuery : ParameterSerializer.queryString(query);
        var uri = path + (queryString.isEmpty() ? "" : "?" + queryString);
        var body = complete ? operation.body() : Optional.<OperationModel.BodyModel>empty();
        if (body.isEmpty()) {
            return Optional.of(new ExampleRequest(op.method(), uri, headers, cookies, Optional.empty()));
        }
        return content(body.get(), op.requestDirection()).map(content -> new ExampleRequest(op.method(), uri, headers, cookies, Optional.of(content)));
    }

    private Optional<ExampleBody> content(OperationModel.BodyModel body, Direction direction) {
        var mediaType = concrete(body.contentType());
        var schema = body.mediaType().schema();
        if (body.property().type().equals(ApiRenderer.FILE_PART)) {
            return Optional.of(new BinaryBody(mediaType));
        }
        var example = schema.flatMap(s -> examples.example(s, direction)).or(() -> body.mediaType().example())
            .or(() -> body.mediaType().itemSchema().map(item -> examples.example(item, direction)).flatMap(e -> e.map(JsonArray::of)));
        if (example.isEmpty()) {
            return Optional.empty();
        }
        if (body.formData()) {
            return Optional.of(parts(body, example.get()));
        }
        var sent = ContentType.parse(mediaType).filter(type -> type.type().equals("multipart")).isPresent() ? mediaType + "; boundary=" + BOUNDARY
            : mediaType;
        var form = ContentType.parse(sent).filter(type -> type.essence().equals("application/x-www-form-urlencoded")).isPresent();
        var text = form ? BodyWriter.form(example.get(), BodyWriter.formSpecs(body.mediaType().encoding()))
            : new String(new BodyWriter(shapes).write(example.get(), ContentType.parse(sent).orElseThrow(), schema), StandardCharsets.UTF_8);
        return Optional.of(new TextBody(sent, text));
    }

    /** The parts of a multipart example: one per property, one per item for arrays (binary items become files). */
    private PartsBody parts(OperationModel.BodyModel body, JsonValue example) {
        var parts = new ArrayList<ExamplePart>();
        var object = example.asObject().orElse(JsonObject.EMPTY);
        for (var member : object.members().entrySet()) {
            var property = body.mediaType().schema().flatMap(s -> shapes.property(s, member.getKey()));
            var array = member.getValue() instanceof JsonArray;
            var itemSchema = array ? property.flatMap(shapes::items) : property;
            var binary = itemSchema.map(s -> shapes.closure(s).stream().anyMatch(c -> c.format().filter("binary"::equals).isPresent())).orElse(false);
            var encoded = Optional.ofNullable(body.mediaType().encoding().get(member.getKey())).flatMap(dev.openapifn.model.Encoding::contentType)
                .map(types -> concrete(types.split(",")[0].trim()));
            var values = member.getValue() instanceof JsonArray items ? items.values() : List.of(member.getValue());
            for (var value : values) {
                var structured = value instanceof JsonObject || value instanceof JsonArray;
                var text = binary ? "example" : structured ? value.toJson() : ParameterSerializer.text(value);
                var contentType = encoded.or(() -> structured ? Optional.of("application/json") : Optional.empty());
                parts.add(new ExamplePart(member.getKey(), binary ? Optional.of("example.bin") : Optional.empty(), text, contentType));
            }
        }
        return new PartsBody(parts);
    }

    /** Credentials for the first security requirement. */
    private void security(List<dev.openapifn.model.SecurityRequirement> requirements, Map<String, String> headers, List<Map.Entry<String, String>> query,
                          List<Map.Entry<String, String>> cookies) {
        var schemes = api.model().components().securitySchemes();
        requirements.stream().findFirst().ifPresent(requirement -> requirement.schemes().keySet().forEach(name -> {
            switch (schemes.get(name)) {
                case SecurityScheme.ApiKey key -> {
                    switch (key.in()) {
                        case HEADER -> headers.put(key.name(), "test-key");
                        case COOKIE -> cookies.add(Map.entry(key.name(), "test-key"));
                        default -> query.add(Map.entry(key.name(), "test-key"));
                    }
                }
                case SecurityScheme.Http http -> headers.put("Authorization", http.scheme().equalsIgnoreCase("basic") ? "Basic dGVzdDp0ZXN0"
                    : Character.toUpperCase(http.scheme().charAt(0)) + http.scheme().substring(1) + " test-token");
                case SecurityScheme.OAuth2 ignored -> headers.put("Authorization", "Bearer test-token");
                case SecurityScheme.OpenIdConnect ignored -> headers.put("Authorization", "Bearer test-token");
                case null, default -> {
                }
            }
        }));
    }

    /** A concrete media type for a (possibly wildcard) range. */
    static String concrete(String range) {
        if (range.equals("*/*")) {
            return "application/octet-stream";
        }
        return range.endsWith("/*") ? range.substring(0, range.length() - 1) + "example" : range;
    }

    // ---------------------------------------------------------------------------------------------
    // The rejection test

    private void rejectionTest(OperationModel operation, Source out, Imports imports) {
        var requiredInput = operation.operation().parameters().stream().anyMatch(p -> p.required() && p.in() != ParameterLocation.PATH)
            || operation.body().filter(b -> b.property().required()).isPresent();
        if (!requiredInput) {
            return;
        }
        var request = requestExpression(operation, false);
        out.blank().line("@" + imports.ref(TEST));
        if (request.isEmpty()) {
            out.line("@" + imports.ref(DISABLED) + "(\"No valid example could be derived; add examples to the API description\")");
        }
        out.open("void " + operation.method() + "RejectsIncompleteRequests() throws Exception");
        if (reactive) {
            perform(request.orElse(ExampleRequest.ROOT), out, imports);
            out.line("    .expectStatus().isBadRequest();");
        } else {
            out.line("mvc.perform(" + mockMvc(request.orElse(ExampleRequest.ROOT), imports) + ")");
            out.line("    .andExpect(status().isBadRequest());");
        }
        out.line("then(api).shouldHaveNoInteractions();");
        out.close();
    }
}
