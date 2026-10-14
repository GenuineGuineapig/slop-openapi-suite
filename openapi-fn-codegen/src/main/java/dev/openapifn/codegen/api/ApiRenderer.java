package dev.openapifn.codegen.api;

import dev.openapifn.codegen.java.Imports;
import dev.openapifn.codegen.java.Names;
import dev.openapifn.codegen.java.Source;
import dev.openapifn.codegen.java.TypeRef;
import dev.openapifn.codegen.java.TypeRef.ClassName;
import dev.openapifn.codegen.model.ModelDef;
import dev.openapifn.codegen.model.ModelDef.Property;
import dev.openapifn.codegen.model.ModelDef.RecordDef;
import dev.openapifn.codegen.model.ModelRenderer;
import dev.openapifn.codegen.model.TypeMapper;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.schema.Dialect;
import dev.openapifn.schema.Schema;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Renders the API side: request and response types, delegate interfaces, controllers and clients. */
public final class ApiRenderer {

    static final String SPRING = "dev.openapifn.spring";
    static final ClassName API_RESPONSE = ClassName.of(SPRING, "ApiResponse");
    static final ClassName FILE_PART = ClassName.of(SPRING, "FilePart");
    static final ClassName OPENAPI_RUNTIME = ClassName.of(SPRING, "OpenApiRuntime");
    static final ClassName OPENAPI_RUNTIMES = ClassName.of(SPRING, "OpenApiRuntimes");
    static final ClassName HTTP_CLIENT = ClassName.of(SPRING + ".client", "OpenApiHttpClient");
    static final ClassName CLIENT_CALL = ClassName.of(SPRING + ".client", "ClientCall");
    static final ClassName CLIENT_RESPONSE = ClassName.of(SPRING + ".client", "ClientResponse");
    static final ClassName UNEXPECTED = ClassName.of(SPRING + ".client", "UnexpectedResponseException");
    static final ClassName CLIENTS = ClassName.of(SPRING + ".client", "OpenApiClients");
    static final ClassName PARAMETER_SPEC = ClassName.of("dev.openapifn.http", "ParameterSerializer").nested("Spec");
    static final ClassName PARAMETER_LOCATION = ClassName.of("dev.openapifn.model", "ParameterLocation");
    static final ClassName PARAMETER_STYLE = ClassName.of("dev.openapifn.model", "ParameterStyle");
    static final ClassName TYPE_REFERENCE = ClassName.of("tools.jackson.core.type", "TypeReference");
    static final ClassName OBJECTS = ClassName.of("java.util", "Objects");
    static final ClassName LINKED_HASH_MAP = ClassName.of("java.util", "LinkedHashMap");
    static final ClassName ARRAYS = ClassName.of("java.util", "Arrays");
    static final ClassName REST_CONTROLLER = ClassName.of("org.springframework.web.bind.annotation", "RestController");
    static final ClassName REQUEST_MAPPING = ClassName.of("org.springframework.web.bind.annotation", "RequestMapping");
    static final ClassName REQUEST_METHOD = ClassName.of("org.springframework.web.bind.annotation", "RequestMethod");
    static final ClassName RESPONSE_ENTITY = ClassName.of("org.springframework.http", "ResponseEntity");
    static final ClassName HTTP_STATUS = ClassName.of("org.springframework.http", "HttpStatus");
    static final ClassName RESPONSE_STATUS_EXCEPTION = ClassName.of("org.springframework.web.server", "ResponseStatusException");
    static final ClassName OBJECT_PROVIDER = ClassName.of("org.springframework.beans.factory", "ObjectProvider");
    static final ClassName SERVLET_REQUEST = ClassName.of("jakarta.servlet.http", "HttpServletRequest");
    static final ClassName CONFIGURATION = ClassName.of("org.springframework.context.annotation", "Configuration");
    static final ClassName BEAN = ClassName.of("org.springframework.context.annotation", "Bean");
    static final ClassName CONDITIONAL_ON_MISSING_BEAN = ClassName.of("org.springframework.boot.autoconfigure.condition", "ConditionalOnMissingBean");
    static final Set<String> MAPPABLE_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

    private final TypeMapper mapper;
    private final ModelRenderer models;
    private final GeneratorNames names;

    public ApiRenderer(TypeMapper mapper, ModelRenderer models, GeneratorNames names) {
        this.mapper = mapper;
        this.models = models;
        this.names = names;
    }

    private static Imports imports(ClassName name, Set<String> declared) {
        return new Imports(name, declared);
    }

    private static String unit(ClassName name, Imports imports, Source body) {
        return "package " + name.packageName() + ";\n\n" + imports.declarations() + "\n\n" + body;
    }

    private static String generated(Imports imports) {
        return "@" + imports.ref(ModelRenderer.GENERATED) + "(\"openapi-fn\")";
    }

    // ---------------------------------------------------------------------------------------------
    // Request records and response types

    /** The request record of {@code operation}. */
    public String request(OperationModel operation) {
        var name = operation.request().orElseThrow();
        var summary = JsonObject.builder().put("description", "The input of {@code " + operation.method() + "}.").build();
        var uri = URI.create("urn:openapi-fn:request");
        var schema = Schema.at(summary, uri, JsonPointer.ROOT, uri, Dialect.DRAFT_2020_12);
        return models.file(new RecordDef(name, schema, operation.requestProperties(), Optional.empty()));
    }

    /** The sealed response type of {@code operation}. */
    public String response(OperationModel operation) {
        var name = operation.response();
        var declared = new java.util.HashSet<>(models.nestedNames(name));
        operation.variants().forEach(variant -> declared.add(variant.name()));
        var imports = imports(name, declared);
        var out = new Source();
        out.javadoc("The responses of {@code " + operation.method() + "}.");
        out.line(generated(imports));
        out.open("public sealed interface " + name.simpleName() + " extends " + imports.ref(API_RESPONSE) + " permits "
            + operation.variants().stream().map(v -> name.simpleName() + "." + v.name()).collect(Collectors.joining(", ")));
        for (var variant : operation.variants()) {
            out.blank();
            variant(operation, variant, out, imports);
        }
        for (var variant : operation.variants()) {
            var components = components(variant);
            out.blank().line("/** A {@code " + variant.key() + "} response. */");
            out.open("static " + name.simpleName() + " " + variant.factory() + "(" + components.stream().map(p -> declaration(p, imports))
                .collect(Collectors.joining(", ")) + ")");
            out.line("return new " + variant.name() + "(" + components.stream().map(Property::javaName).collect(Collectors.joining(", ")) + ");").close();
        }
        models.renderNested(name, out, imports);
        out.close();
        return unit(name, imports, out);
    }

    /** The record components of a variant: {@code status} (for ranges and default), {@code body}, then headers. */
    static List<Property> components(OperationModel.VariantModel variant) {
        var components = new ArrayList<Property>();
        if (!variant.fixedStatus()) {
            components.add(new Property("status", "status", new TypeRef.Primitive("int"), true, false, anySchema()));
        }
        variant.body().ifPresent(components::add);
        components.addAll(variant.headers().values());
        return components;
    }

    private static Schema anySchema() {
        var uri = URI.create("urn:openapi-fn:any");
        return Schema.at(JsonObject.EMPTY, uri, JsonPointer.ROOT, uri, Dialect.DRAFT_2020_12);
    }

    private void variant(OperationModel operation, OperationModel.VariantModel variant, Source out, Imports imports) {
        var components = components(variant);
        out.javadoc(variant.response().description().or(() -> variant.response().summary()).orElse("A " + variant.key() + " response."));
        out.open("record " + variant.name() + "(" + components.stream().map(p -> declaration(p, imports)).collect(Collectors.joining(", "))
            + ") implements " + operation.response().simpleName());
        var checks = new ArrayList<String>();
        if (!variant.fixedStatus() && !variant.key().equals("default")) {
            var digit = variant.key().charAt(0);
            checks.add("if (status / 100 != " + digit + ") {\n    throw new IllegalArgumentException(\"status must be " + variant.key() + " but was \" + status);\n}");
        }
        components.stream().filter(p -> !p.nullable() && !(p.type() instanceof TypeRef.Primitive))
            .forEach(p -> checks.add(imports.ref(OBJECTS) + ".requireNonNull(" + p.javaName() + ", " + Names.literal(p.javaName()) + ");"));
        if (!checks.isEmpty()) {
            out.open("public " + variant.name());
            checks.forEach(check -> check.lines().forEach(out::line));
            out.close();
        }
        if (variant.fixedStatus()) {
            out.blank().line("@Override").open("public int status()").line("return " + variant.key() + ";").close();
        }
        if (!variant.headers().isEmpty()) {
            var map = imports.ref(TypeRef.MAP) + "<String, " + "@" + imports.ref(TypeMapper.NULLABLE) + " Object>";
            out.blank().line("@Override").open("public " + map + " headers()");
            out.line("var headers = new " + imports.ref(LINKED_HASH_MAP) + "<String, @" + imports.ref(TypeMapper.NULLABLE) + " Object>();");
            variant.headers().forEach((header, property) -> out.line("headers.put(" + Names.literal(header) + ", " + property.javaName() + ");"));
            out.line("return headers;").close();
        }
        out.close();
    }

    static String declaration(Property property, Imports imports) {
        return (property.nullable() ? imports.ref(property.type(), TypeMapper.NULLABLE) : imports.ref(property.type())) + " " + property.javaName();
    }

    // ---------------------------------------------------------------------------------------------
    // Delegate interfaces

    /** The interface applications implement to serve {@code group}. */
    public String api(String group, List<OperationModel> operations) {
        var name = names.api(group);
        var imports = imports(name, Set.of());
        var out = new Source();
        out.javadoc("Implement this interface (as a bean) to serve the {@code " + group + "} operations; the generated {@code "
            + names.controller(group).simpleName() + "} binds requests and maps the returned responses.");
        out.line(generated(imports));
        out.open("public interface " + name.simpleName());
        for (var operation : operations) {
            out.blank();
            out.javadoc(javadoc(operation));
            if (operation.operation().operation().deprecated()) {
                out.line("@Deprecated");
            }
            out.line(imports.ref(operation.response()) + " " + operation.method() + "(" + operation.request().map(r -> imports.ref(r) + " request").orElse("") + ");");
        }
        out.close();
        return unit(name, imports, out);
    }

    private static String javadoc(OperationModel operation) {
        var op = operation.operation().operation();
        var text = java.util.stream.Stream.of(op.summary(), op.description()).flatMap(Optional::stream).collect(Collectors.joining("\n\n"));
        var kind = switch (operation.operation().kind()) {
            case PATH -> "";
            case WEBHOOK -> "Webhook ";
            case CALLBACK -> "Callback to ";
        };
        return (text.isEmpty() ? "" : text + "\n\n") + "<p>" + kind + "{@code " + operation.operation().method() + " " + operation.operation().path() + "}";
    }

    // ---------------------------------------------------------------------------------------------
    // Controllers

    /** The {@code @RestController} of {@code group}. */
    public String controller(String group, List<OperationModel> operations) {
        var name = names.controller(group);
        var apiName = names.api(group);
        var imports = imports(name, Set.of());
        var out = new Source();
        out.javadoc("Serves the {@code " + group + "} operations by delegating to {@link " + imports.ref(apiName)
            + "}; requests are validated and bound by the openapi-fn runtime. Answers 501 while no implementation is registered.");
        out.line(generated(imports)).line("@SuppressWarnings(\"deprecation\")").line("@" + imports.ref(REST_CONTROLLER));
        if (operations.stream().noneMatch(OperationModel::outbound)) {
            out.line("@" + imports.ref(REQUEST_MAPPING) + "(\"${openapi-fn.specs." + names.apiName() + ".base-path:" + names.basePath() + "}\")");
        }
        out.open("public class " + name.simpleName());
        out.line("private final " + imports.ref(OPENAPI_RUNTIME) + " runtime;");
        out.line("private final " + imports.ref(OBJECT_PROVIDER) + "<" + imports.ref(apiName) + "> api;");
        out.blank().open("public " + name.simpleName() + "(" + imports.ref(OPENAPI_RUNTIMES) + " runtimes, " + imports.ref(OBJECT_PROVIDER) + "<"
            + imports.ref(apiName) + "> api)");
        out.line("this.runtime = runtimes.get(" + imports.ref(names.spec()) + ".NAME);").line("this.api = api;").close();
        var custom = new LinkedHashMap<String, List<OperationModel>>();
        for (var operation : operations) {
            var method = operation.operation().method();
            if (MAPPABLE_METHODS.contains(method)) {
                out.blank().line("@" + imports.ref(REQUEST_MAPPING) + "(method = " + imports.ref(REQUEST_METHOD) + "." + method + ", path = "
                    + Names.literal(mappingPath(operation, names)) + ")");
                handler(operation, "public", out, imports);
            } else {
                custom.computeIfAbsent(operation.operation().path(), path -> new ArrayList<>()).add(operation);
            }
        }
        custom.forEach((path, pathOperations) -> {
            out.blank().javadoc("Dispatches the methods Spring cannot map by annotation.");
            out.line("@" + imports.ref(REQUEST_MAPPING) + "(path = " + Names.literal(mappingPath(pathOperations.getFirst(), names)) + ")");
            out.open("public " + imports.ref(RESPONSE_ENTITY) + "<Object> " + pathOperations.getFirst().method() + "Dispatch(" + imports.ref(SERVLET_REQUEST)
                + " request)");
            out.open("return switch (request.getMethod())");
            pathOperations.forEach(op -> out.line("case " + Names.literal(op.operation().method()) + " -> " + op.method() + "(request);"));
            out.line("default -> throw new " + imports.ref(RESPONSE_STATUS_EXCEPTION) + "(" + imports.ref(HTTP_STATUS) + ".METHOD_NOT_ALLOWED);");
            out.close(";").close();
            pathOperations.forEach(op -> {
                out.blank();
                handler(op, "private", out, imports);
            });
        });
        out.blank().open("private " + imports.ref(apiName) + " api()");
        out.line("var implementation = api.getIfAvailable();");
        out.open("if (implementation == null)");
        out.line("throw new " + imports.ref(RESPONSE_STATUS_EXCEPTION) + "(" + imports.ref(HTTP_STATUS) + ".NOT_IMPLEMENTED, \"No "
            + apiName.simpleName() + " implementation is registered\");").close();
        out.line("return implementation;").close();
        out.close();
        return unit(name, imports, out);
    }

    /**
     * The Spring mapping pattern of an operation. Spring matches segments without their
     * {@code ;} parameters, so matrix-style expressions become {@code *} (the runtime extracts
     * their raw values itself). Webhooks and callbacks are received at a configurable path.
     */
    static String mappingPath(OperationModel operation, GeneratorNames names) {
        if (operation.outbound()) {
            return "${openapi-fn.specs." + names.apiName() + ".receivers." + operation.method() + ".path:" + operation.receiverPath() + "}";
        }
        var path = operation.operation().path();
        for (var parameter : operation.operation().parameters()) {
            if (parameter.in() == ParameterLocation.PATH && parameter.style() == dev.openapifn.model.ParameterStyle.MATRIX) {
                path = path.replace("{" + parameter.name() + "}", "*");
            }
        }
        return path;
    }

    private void handler(OperationModel operation, String visibility, Source out, Imports imports) {
        out.open(visibility + " " + imports.ref(RESPONSE_ENTITY) + "<Object> " + operation.method() + "(" + imports.ref(SERVLET_REQUEST) + " request)");
        if (operation.request().isPresent()) {
            out.line("var binding = runtime.bind(request, " + Names.literal(operation.id()) + ");");
            var arguments = new ArrayList<String>();
            operation.parameters().forEach(p -> arguments.add("binding.parameter(" + Names.literal(p.parameter().in().value()) + ", "
                + Names.literal(p.parameter().name()) + ", " + token(p.property().type(), imports) + ")"));
            operation.body().ifPresent(b -> arguments.add("binding.body(" + token(b.property().type(), imports) + ")"));
            out.line("var input = new " + imports.ref(operation.request().get()) + "(\n        " + String.join(",\n        ", arguments) + ");");
            out.line("return runtime.respond(request, " + Names.literal(operation.id()) + ", api()." + operation.method() + "(input));");
        } else {
            out.line("runtime.bind(request, " + Names.literal(operation.id()) + ");");
            out.line("return runtime.respond(request, " + Names.literal(operation.id()) + ", api()." + operation.method() + "());");
        }
        out.close();
    }

    /** A Jackson type token: {@code X.class} or {@code new TypeReference<...>() { }}. */
    static String token(TypeRef type, Imports imports) {
        return switch (type) {
            case TypeRef.Parameterized parameterized -> "new " + imports.ref(TYPE_REFERENCE) + "<" + imports.ref(parameterized) + ">() { }";
            default -> imports.ref(type) + ".class";
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Clients

    /** The HTTP client of {@code group}. */
    public String client(String group, List<OperationModel> operations) {
        var name = names.client(group);
        var imports = imports(name, Set.of());
        var out = new Source();
        out.javadoc((operations.stream().anyMatch(OperationModel::outbound) ? "Sends the {@code " + group + "} to the URLs their receivers registered."
            : "A client for the {@code " + group + "} operations.") + " Every described response, errors included, is returned as a value of the "
            + "operation's sealed response type; undescribed statuses raise {@code UnexpectedResponseException}.");
        out.line(generated(imports)).line("@SuppressWarnings(\"deprecation\")");
        out.open("public class " + name.simpleName());
        var constants = new LinkedHashMap<String, String>();
        for (var operation : operations) {
            for (var parameter : operation.parameters()) {
                var p = parameter.parameter();
                var constant = Names.constant(operation.method()) + "_" + Names.constant(parameter.property().javaName());
                constants.put(operation.method() + "/" + parameter.property().javaName(), constant);
                out.line("private static final " + imports.ref(PARAMETER_SPEC) + " " + constant + " = new " + imports.ref(PARAMETER_SPEC) + "("
                    + Names.literal(p.name()) + ", " + imports.ref(PARAMETER_LOCATION) + "." + p.in().name() + ", " + imports.ref(PARAMETER_STYLE) + "."
                    + p.style().name() + ", " + p.explode() + ", " + p.allowReserved() + ");");
            }
        }
        out.blank().line("private final " + imports.ref(HTTP_CLIENT) + " http;");
        out.blank().open("public " + name.simpleName() + "(" + imports.ref(HTTP_CLIENT) + " http)").line("this.http = http;").close();
        for (var operation : operations) {
            out.blank().javadoc(javadoc(operation));
            if (operation.operation().operation().deprecated()) {
                out.line("@Deprecated");
            }
            clientMethod(operation, constants, out, imports);
        }
        out.close();
        return unit(name, imports, out);
    }

    private void clientMethod(OperationModel operation, Map<String, String> constants, Source out, Imports imports) {
        var parameters = new ArrayList<String>();
        if (operation.outbound()) {
            parameters.add(imports.ref(TypeRef.URI) + " target");
        }
        operation.request().ifPresent(r -> parameters.add(imports.ref(r) + " request"));
        out.open("public " + imports.ref(operation.response()) + " " + operation.method() + "(" + String.join(", ", parameters) + ")");
        var chain = new ArrayList<String>();
        chain.add("var call = " + imports.ref(CLIENT_CALL) + ".of(" + Names.literal(operation.operation().method()) + ", "
            + Names.literal(operation.outbound() ? "" : operation.operation().path()) + ")");
        if (operation.outbound()) {
            chain.add("    .operation(" + Names.literal(operation.id()) + ")");
        }
        for (var parameter : operation.parameters()) {
            chain.add("    .param(" + constants.get(operation.method() + "/" + parameter.property().javaName()) + ", request."
                + parameter.property().javaName() + "())");
        }
        var produces = operation.produces();
        if (!produces.isEmpty()) {
            chain.add("    .accept(" + produces.stream().map(Names::literal).collect(Collectors.joining(", ")) + ")");
        }
        chain.set(chain.size() - 1, chain.getLast() + ";");
        chain.forEach(out::line);
        operation.body().ifPresent(body -> formRecord(body).ifPresentOrElse(record -> {
            out.line("var body = request.body();");
            out.open("if (body != null)");
            out.line("call = call.multipart(" + imports.ref(ARRAYS) + ".asList(" + parts(body, record, imports) + "));");
            out.close();
        }, () -> out.line("call = call.body(" + Names.literal(body.contentType()) + ", request.body());")));
        out.open("return " + (operation.outbound() ? "http.to(target.toString())" : "http") + ".exchange(call, response -> switch (response.status())");
        var ranges = new ArrayList<OperationModel.VariantModel>();
        Optional<OperationModel.VariantModel> fallback = Optional.empty();
        for (var variant : operation.variants()) {
            if (variant.fixedStatus()) {
                out.line("case " + variant.key() + " -> " + construct(operation, variant, "", imports) + ";");
            } else if (variant.key().equals("default")) {
                fallback = Optional.of(variant);
            } else {
                ranges.add(variant);
            }
        }
        out.open("default ->");
        for (var range : ranges) {
            out.open("if (response.status() / 100 == " + range.key().charAt(0) + ")");
            out.line("yield " + construct(operation, range, "response.status()", imports) + ";").close();
        }
        if (fallback.isPresent()) {
            out.line("yield " + construct(operation, fallback.get(), "response.status()", imports) + ";");
        } else {
            out.line("throw new " + imports.ref(UNEXPECTED) + "(response);");
        }
        out.close();
        out.close(");");
        out.close();
    }

    private String construct(OperationModel operation, OperationModel.VariantModel variant, String status, Imports imports) {
        var arguments = new ArrayList<String>();
        if (!status.isEmpty()) {
            arguments.add(status);
        }
        variant.body().ifPresent(body -> arguments.add("response.body(" + token(body.type(), imports) + ")"));
        variant.headers().forEach((header, property) -> arguments.add("response.header(" + Names.literal(header) + ", " + token(property.type(), imports) + ")"));
        return "new " + imports.ref(operation.response()) + "." + variant.name() + "(" + String.join(", ", arguments) + ")";
    }

    /** The record whose components are sent as {@code multipart/form-data} parts, if the body is one; other bodies are encoded from their JSON. */
    private Optional<RecordDef> formRecord(OperationModel.BodyModel body) {
        return Optional.of(body).filter(OperationModel.BodyModel::formData).map(b -> b.property().type()).filter(ClassName.class::isInstance)
            .flatMap(type -> mapper.definition((ClassName) type)).filter(RecordDef.class::isInstance).map(RecordDef.class::cast);
    }

    /** The parts of a record body: one per component, JSON for structured values unless the encoding says otherwise. */
    private String parts(OperationModel.BodyModel body, RecordDef record, Imports imports) {
        var part = imports.ref(CLIENT_CALL) + ".Part.of";
        return record.properties().stream().map(property -> {
            var encoding = Optional.ofNullable(body.mediaType().encoding().get(property.jsonName())).flatMap(e -> e.contentType());
            var json = encoding.or(() -> structured(property.type()) ? Optional.of("application/json") : Optional.empty());
            return part + "(" + Names.literal(property.jsonName()) + ", body." + property.javaName() + "()" + json.map(t -> ", " + Names.literal(t))
                .orElse("") + ")";
        }).collect(Collectors.joining(",\n        "));
    }

    /**
     * Whether a part value of {@code type} is sent as JSON. Arrays are sent as one part per item,
     * so they are JSON parts when their items are.
     */
    private boolean structured(TypeRef type) {
        return type instanceof TypeRef.Parameterized collection && !collection.raw().equals(TypeRef.MAP)
            ? json(collection.arguments().getFirst()) : json(type);
    }

    /** Whether values of {@code type} are JSON (rather than text or file) parts: models, maps and nested arrays are. */
    private boolean json(TypeRef type) {
        return switch (type) {
            case ClassName className -> mapper.definition(className).filter(definition -> !(definition instanceof ModelDef.EnumDef)).isPresent();
            case TypeRef.Parameterized ignored -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Spec constants and client configuration

    /** Constants describing the API description: name, location, server URL and base path. */
    public String spec(String location, String serverUrl) {
        var name = names.spec();
        var imports = imports(name, Set.of());
        var out = new Source();
        out.javadoc("The API description {@code " + names.apiName() + "} these classes were generated from.");
        out.line(generated(imports));
        out.open("public final class " + name.simpleName());
        out.blank().line("/** The name runtimes and properties ({@code openapi-fn.specs.<name>}) use. */");
        out.line("public static final String NAME = " + Names.literal(names.apiName()) + ";");
        out.blank().line("/** Where the bundled description is loaded from. */");
        out.line("public static final String LOCATION = " + Names.literal(location) + ";");
        out.blank().line("/** The first server URL, with variables replaced by their defaults. */");
        out.line("public static final String SERVER_URL = " + Names.literal(serverUrl) + ";");
        out.blank().line("/** The path the controllers are mapped to unless {@code openapi-fn.specs.<name>.base-path} is set. */");
        out.line("public static final String BASE_PATH = " + Names.literal(names.basePath()) + ";");
        out.blank().open("private " + name.simpleName() + "()").close();
        out.close();
        return unit(name, imports, out);
    }

    /** A configuration registering a bean for every client. */
    public String clientsConfiguration(List<String> groups) {
        var name = names.clientsConfiguration();
        var imports = imports(name, Set.of());
        var out = new Source();
        out.javadoc("Registers the generated clients as beans; base URLs come from {@code openapi-fn.clients." + names.apiName()
            + ".base-url} (default: the first server URL). Import it or let component scanning find it.");
        out.line(generated(imports)).line("@" + imports.ref(CONFIGURATION) + "(proxyBeanMethods = false)");
        out.open("public class " + name.simpleName());
        for (var group : groups) {
            var client = names.client(group);
            out.blank().line("@" + imports.ref(BEAN)).line("@" + imports.ref(CONDITIONAL_ON_MISSING_BEAN));
            out.open("public " + imports.ref(client) + " " + Character.toLowerCase(client.simpleName().charAt(0)) + client.simpleName().substring(1) + "("
                + imports.ref(CLIENTS) + " clients)");
            out.line("return new " + imports.ref(client) + "(clients.http(" + imports.ref(names.spec()) + ".NAME, " + imports.ref(names.spec())
                + ".SERVER_URL));").close();
        }
        out.close();
        return unit(name, imports, out);
    }
}
