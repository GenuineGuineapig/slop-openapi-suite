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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders a {@code @WebMvcTest} per API group. For every operation, one test stubs the delegate
 * with an example response, performs a valid example request and asserts that the exchange
 * conforms to the API description; another checks that requests lacking required input are
 * rejected before reaching the delegate.
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
    private static final ClassName STANDARD_CHARSETS = ClassName.of("java.nio.charset", "StandardCharsets");
    /** The boundary of positional multipart example bodies (fixed, so that generated tests are stable). */
    private static final String BOUNDARY = "openapi-fn-example";

    private final OpenApiDocument api;
    private final GeneratorNames names;
    private final ExampleGenerator examples;
    private final SchemaShapes shapes;

    public TestRenderer(OpenApiDocument api, GeneratorNames names) {
        this.api = api;
        this.names = names;
        this.examples = new ExampleGenerator(api.schemas());
        this.shapes = new SchemaShapes(api.schemas());
    }

    /** The test class for {@code group}. */
    public String test(String group, List<OperationModel> operations) {
        var name = names.controllerTest(group);
        var imports = new Imports(name, Set.of());
        var out = new Source();
        out.javadoc("Contract tests of the generated {@code " + names.controller(group).simpleName() + "}.");
        out.line("@" + imports.ref(dev.openapifn.codegen.model.ModelRenderer.GENERATED) + "(\"openapi-fn\")");
        out.line("@SuppressWarnings(\"deprecation\")");
        out.line("@" + imports.ref(WEB_MVC_TEST) + "(" + imports.ref(names.controller(group)) + ".class)");
        out.open("class " + name.simpleName());
        out.blank().line("@" + imports.ref(AUTOWIRED)).line("private " + imports.ref(MOCK_MVC) + " mvc;");
        out.blank().line("@" + imports.ref(AUTOWIRED)).line("private " + imports.ref(ApiRenderer.OPENAPI_RUNTIMES) + " runtimes;");
        out.blank().line("@" + imports.ref(AUTOWIRED)).line("private " + imports.ref(JSON_MAPPER) + " mapper;");
        out.blank().line("@" + imports.ref(MOCKITO_BEAN)).line("private " + imports.ref(names.api(group)) + " api;");
        for (var operation : operations) {
            exchangeTest(operation, out, imports);
            rejectionTest(operation, out, imports);
        }
        out.close();
        var statics = String.join("\n", "import static org.mockito.ArgumentMatchers.any;", "import static org.mockito.BDDMockito.given;",
            "import static org.mockito.BDDMockito.then;", "import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;",
            "import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;",
            "import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;");
        return "package " + name.packageName() + ";\n\n" + statics + "\n\n" + imports.declarations() + "\n\n" + out;
    }

    // ---------------------------------------------------------------------------------------------
    // The exchange test

    private void exchangeTest(OperationModel operation, Source out, Imports imports) {
        var variant = stubbedVariant(operation);
        var status = variant.fixedStatus() ? Integer.parseInt(variant.key()) : variant.key().equals("default") ? 200
            : Integer.parseInt(variant.key().charAt(0) + "00");
        var stub = responseExpression(operation, variant, status, imports);
        var request = requestExpression(operation, true, imports);
        out.blank().line("@" + imports.ref(TEST));
        if (stub.isEmpty() || request.isEmpty()) {
            out.line("@" + imports.ref(DISABLED) + "(\"No valid example could be derived; add examples to the API description\")");
        }
        out.open("void " + operation.method() + "() throws Exception");
        var call = operation.method() + "(" + (operation.request().isPresent() ? "any()" : "") + ")";
        out.line("given(api." + call + ").willReturn(" + stub.orElse("null") + ");");
        out.line("mvc.perform(" + request.orElse("request(" + imports.ref(HTTP_METHOD) + ".GET, \"/\")") + ")");
        out.line("    .andExpect(status().is(" + status + "))");
        out.line("    .andExpect(" + imports.ref(MATCHERS) + ".conformsTo(runtimes.get(" + imports.ref(names.spec()) + ".NAME)"
            + (operation.outbound() ? ", " + Names.literal(operation.id()) : "") + "));");
        out.line("then(api).should()." + call + ";");
        out.close();
    }

    /** The first fixed 2xx variant, else the first variant. */
    private static OperationModel.VariantModel stubbedVariant(OperationModel operation) {
        return operation.variants().stream().filter(v -> v.fixedStatus() && v.key().startsWith("2")).findFirst()
            .or(() -> operation.variants().stream().filter(v -> v.key().startsWith("2")).findFirst())
            .orElse(operation.variants().getFirst());
    }

    private Optional<String> responseExpression(OperationModel operation, OperationModel.VariantModel variant, int status, Imports imports) {
        var arguments = new ArrayList<String>();
        if (!variant.fixedStatus()) {
            arguments.add(Integer.toString(status));
        }
        if (variant.body().isPresent()) {
            var body = variant.body().get();
            var mediaType = OperationModel.primary(variant.response().content()).orElseThrow();
            var value = value(body, variant.response().content().get(mediaType).itemSchema(), Direction.RESPONSE, imports, concrete(mediaType));
            if (value.isEmpty()) {
                return Optional.empty();
            }
            arguments.add(value.get());
        }
        for (var header : variant.headers().values()) {
            if (header.required()) {
                var value = value(header, Optional.empty(), Direction.RESPONSE, imports, "text/plain");
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
        return example.map(json -> type.equals(TypeRef.STRING) ? Names.literal(json.asString().orElse(json.toJson()))
            : "mapper.readValue(" + Names.literal(json.toJson()) + ", " + ApiRenderer.token(type, imports) + ")");
    }

    // ---------------------------------------------------------------------------------------------
    // The request

    /** A request builder expression; {@code complete} includes optional-but-exemplified input and the body. */
    private Optional<String> requestExpression(OperationModel operation, boolean complete, Imports imports) {
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
                .or(() -> parameter.effectiveSchema().flatMap(schema -> examples.example(schema, Direction.REQUEST)));
            if (example.isEmpty()) {
                return Optional.empty();
            }
            values.put(parameter, example.get());
        }
        for (var entry : values.entrySet()) {
            var parameter = entry.getKey();
            var spec = new ParameterSerializer.Spec(parameter.name(), parameter.in(), parameter.style(), parameter.explode(), parameter.allowReserved());
            var value = parameter.content().isEmpty() ? entry.getValue() : dev.openapifn.json.JsonString.of(entry.getValue().toJson());
            switch (parameter.in()) {
                case PATH -> template = template.replace("{" + parameter.name() + "}", ParameterSerializer.path(spec, value));
                case QUERY -> query.addAll(ParameterSerializer.query(spec, value));
                case QUERYSTRING -> rawQuery = parameter.content().keySet().stream().findFirst().flatMap(ContentType::parse)
                    .filter(t -> t.essence().equals("application/x-www-form-urlencoded")).isPresent()
                    ? BodyWriter.form(entry.getValue()) : dev.openapifn.http.UrlCodec.encode(entry.getValue().toJson(), "");
                case HEADER -> headers.put(parameter.name(), ParameterSerializer.header(spec, value));
                default -> cookies.addAll(ParameterSerializer.cookie(spec, value)); // COOKIE
            }
        }
        security(op.security(), headers, query, cookies);
        path.append(template);
        var queryString = rawQuery != null ? rawQuery : ParameterSerializer.queryString(query);
        var uri = path + (queryString.isEmpty() ? "" : "?" + queryString);
        var body = complete ? operation.body() : Optional.<OperationModel.BodyModel>empty();
        var multipartBody = body.filter(OperationModel.BodyModel::formData);
        var expression = new StringBuilder(multipartBody.isPresent()
            ? "multipart(" + imports.ref(HTTP_METHOD) + ".valueOf(" + Names.literal(op.method()) + "), " + imports.ref(URI_TYPE) + ".create("
                + Names.literal(uri) + "))"
            : "request(" + imports.ref(HTTP_METHOD) + ".valueOf(" + Names.literal(op.method()) + "), " + imports.ref(URI_TYPE) + ".create("
                + Names.literal(uri) + "))");
        headers.forEach((header, value) -> expression.append("\n            .header(").append(Names.literal(header)).append(", ")
            .append(Names.literal(value)).append(")"));
        cookies.forEach(cookie -> expression.append("\n            .cookie(new ").append(imports.ref(COOKIE)).append("(")
            .append(Names.literal(cookie.getKey())).append(", ").append(Names.literal(cookie.getValue())).append("))"));
        if (body.isPresent()) {
            var content = content(body.get(), imports);
            if (content.isEmpty()) {
                return Optional.empty();
            }
            expression.append(content.get());
        }
        return Optional.of(expression.toString());
    }

    private Optional<String> content(OperationModel.BodyModel body, Imports imports) {
        var mediaType = concrete(body.contentType());
        var schema = body.mediaType().schema();
        if (body.property().type().equals(ApiRenderer.FILE_PART)) {
            return Optional.of("\n            .contentType(" + Names.literal(mediaType) + ")\n            .content(\"example\".getBytes("
                + imports.ref(STANDARD_CHARSETS) + ".UTF_8))");
        }
        var example = schema.flatMap(s -> examples.example(s, Direction.REQUEST)).or(() -> body.mediaType().example())
            .or(() -> body.mediaType().itemSchema().map(item -> examples.example(item, Direction.REQUEST)).flatMap(e -> e.map(JsonArray::of)));
        if (example.isEmpty()) {
            return Optional.empty();
        }
        if (body.formData()) {
            return Optional.of(parts(body, example.get(), imports));
        }
        var sent = ContentType.parse(mediaType).filter(type -> type.type().equals("multipart")).isPresent() ? mediaType + "; boundary=" + BOUNDARY
            : mediaType;
        var text = new String(new BodyWriter(shapes).write(example.get(), ContentType.parse(sent).orElseThrow(), schema), StandardCharsets.UTF_8);
        return Optional.of("\n            .contentType(" + Names.literal(sent) + ")\n            .content(" + Names.literal(text) + ")");
    }

    /** MockMvc parts for a multipart example: one per property, one per item for arrays (binary items become files). */
    private String parts(OperationModel.BodyModel body, JsonValue example, Imports imports) {
        var out = new StringBuilder();
        var object = example.asObject().orElse(JsonObject.EMPTY);
        for (var member : object.members().entrySet()) {
            var property = body.mediaType().schema().flatMap(s -> shapes.property(s, member.getKey()));
            var array = member.getValue() instanceof JsonArray;
            var itemSchema = array ? property.flatMap(shapes::items) : property;
            var binary = itemSchema.map(s -> shapes.closure(s).stream().anyMatch(c -> c.format().filter("binary"::equals).isPresent())).orElse(false);
            var values = member.getValue() instanceof JsonArray items ? items.values() : List.of(member.getValue());
            for (var value : values) {
                var structured = value instanceof JsonObject || value instanceof JsonArray;
                var bytes = binary ? "example" : structured ? value.toJson() : ParameterSerializer.text(value);
                out.append("\n            .part(new ").append(imports.ref(MOCK_PART)).append("(").append(Names.literal(member.getKey())).append(", ")
                    .append(binary ? Names.literal("example.bin") + ", " : structured ? "null, " : "").append(Names.literal(bytes)).append(".getBytes(")
                    .append(imports.ref(STANDARD_CHARSETS)).append(".UTF_8)").append(structured ? ", " + imports.ref(MEDIA_TYPE) + ".APPLICATION_JSON" : "")
                    .append("))");
            }
        }
        return out.toString();
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
        var request = requestExpression(operation, false, imports);
        out.blank().line("@" + imports.ref(TEST));
        if (request.isEmpty()) {
            out.line("@" + imports.ref(DISABLED) + "(\"No valid example could be derived; add examples to the API description\")");
        }
        out.open("void " + operation.method() + "RejectsIncompleteRequests() throws Exception");
        out.line("mvc.perform(" + request.orElse("request(" + imports.ref(HTTP_METHOD) + ".GET, \"/\")") + ")");
        out.line("    .andExpect(status().isBadRequest());");
        out.line("then(api).shouldHaveNoInteractions();");
        out.close();
    }
}
