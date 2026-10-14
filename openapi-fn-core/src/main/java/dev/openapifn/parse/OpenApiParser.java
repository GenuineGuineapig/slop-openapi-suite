package dev.openapifn.parse;

import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.Diagnostic;
import dev.openapifn.load.Document;
import dev.openapifn.load.DocumentLoader;
import dev.openapifn.load.Uris;
import dev.openapifn.model.Callback;
import dev.openapifn.model.Components;
import dev.openapifn.model.Contact;
import dev.openapifn.model.Encoding;
import dev.openapifn.model.Example;
import dev.openapifn.model.ExternalDocs;
import dev.openapifn.model.Header;
import dev.openapifn.model.Info;
import dev.openapifn.model.License;
import dev.openapifn.model.Link;
import dev.openapifn.model.MediaType;
import dev.openapifn.model.OAuthFlow;
import dev.openapifn.model.OAuthFlows;
import dev.openapifn.model.OpenApi;
import dev.openapifn.model.Operation;
import dev.openapifn.model.Parameter;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.ParameterStyle;
import dev.openapifn.model.PathItem;
import dev.openapifn.model.Paths;
import dev.openapifn.model.RequestBody;
import dev.openapifn.model.Response;
import dev.openapifn.model.Responses;
import dev.openapifn.model.SecurityRequirement;
import dev.openapifn.model.SecurityScheme;
import dev.openapifn.model.Server;
import dev.openapifn.model.ServerVariable;
import dev.openapifn.model.SpecVersion;
import dev.openapifn.model.Tag;
import dev.openapifn.schema.Dialect;
import dev.openapifn.schema.Schema;
import dev.openapifn.schema.SchemaRegistry;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Interprets loaded documents as an OpenAPI description. Single use; not thread-safe. */
final class OpenApiParser {

    private static final Pattern STATUS_CODE = Pattern.compile("[1-5](\\d\\d|XX)");
    private static final Pattern TEMPLATE_EXPRESSION = Pattern.compile("\\{([^}]+)}");

    private static final Set<String> ROOT_FIELDS = Set.of("openapi", "$self", "info", "jsonSchemaDialect", "servers", "paths", "webhooks",
        "components", "security", "tags", "externalDocs");
    private static final Set<String> INFO_FIELDS = Set.of("title", "summary", "description", "termsOfService", "contact", "license", "version");
    private static final Set<String> CONTACT_FIELDS = Set.of("name", "url", "email");
    private static final Set<String> LICENSE_FIELDS = Set.of("name", "identifier", "url");
    private static final Set<String> SERVER_FIELDS = Set.of("url", "description", "name", "variables");
    private static final Set<String> SERVER_VARIABLE_FIELDS = Set.of("enum", "default", "description");
    private static final Set<String> COMPONENTS_FIELDS = Set.of("schemas", "responses", "parameters", "examples", "requestBodies", "headers",
        "securitySchemes", "links", "callbacks", "pathItems", "mediaTypes");
    private static final Set<String> PATH_ITEM_FIELDS = Set.of("$ref", "summary", "description", "get", "put", "post", "delete", "options",
        "head", "patch", "trace", "query", "additionalOperations", "servers", "parameters");
    private static final Set<String> OPERATION_FIELDS = Set.of("tags", "summary", "description", "externalDocs", "operationId", "parameters",
        "requestBody", "responses", "callbacks", "deprecated", "security", "servers");
    private static final Set<String> EXTERNAL_DOCS_FIELDS = Set.of("description", "url");
    private static final Set<String> PARAMETER_FIELDS = Set.of("name", "in", "description", "required", "deprecated", "allowEmptyValue",
        "style", "explode", "allowReserved", "schema", "example", "examples", "content");
    private static final Set<String> HEADER_FIELDS = Set.of("description", "required", "deprecated", "style", "explode", "schema", "example",
        "examples", "content");
    private static final Set<String> REQUEST_BODY_FIELDS = Set.of("description", "content", "required");
    private static final Set<String> MEDIA_TYPE_FIELDS = Set.of("description", "schema", "itemSchema", "example", "examples", "encoding",
        "prefixEncoding", "itemEncoding");
    private static final Set<String> ENCODING_FIELDS = Set.of("contentType", "headers", "style", "explode", "allowReserved", "encoding",
        "prefixEncoding", "itemEncoding");
    private static final Set<String> RESPONSE_FIELDS = Set.of("summary", "description", "headers", "content", "links");
    private static final Set<String> EXAMPLE_FIELDS = Set.of("summary", "description", "value", "dataValue", "serializedValue", "externalValue");
    private static final Set<String> LINK_FIELDS = Set.of("operationRef", "operationId", "parameters", "requestBody", "description", "server");
    private static final Set<String> TAG_FIELDS = Set.of("name", "summary", "description", "externalDocs", "parent", "kind");
    private static final Set<String> SECURITY_SCHEME_FIELDS = Set.of("type", "description", "name", "in", "scheme", "bearerFormat", "flows",
        "openIdConnectUrl", "oauth2MetadataUrl", "deprecated");
    private static final Set<String> OAUTH_FLOWS_FIELDS = Set.of("implicit", "password", "clientCredentials", "authorizationCode",
        "deviceAuthorization");
    private static final Set<String> OAUTH_FLOW_FIELDS = Set.of("authorizationUrl", "deviceAuthorizationUrl", "tokenUrl", "refreshUrl", "scopes");

    /** Fields added after OpenAPI 3.0, by object type: they are errors in descriptions of earlier versions. */
    private static final Map<String, Map<String, SpecVersion>> INTRODUCED = Map.ofEntries(
        Map.entry("OpenAPI Object", Map.of("webhooks", SpecVersion.V3_1, "jsonSchemaDialect", SpecVersion.V3_1, "$self", SpecVersion.V3_2)),
        Map.entry("Info Object", Map.of("summary", SpecVersion.V3_1)),
        Map.entry("License Object", Map.of("identifier", SpecVersion.V3_1)),
        Map.entry("Server Object", Map.of("name", SpecVersion.V3_2)),
        Map.entry("Components Object", Map.of("pathItems", SpecVersion.V3_1, "mediaTypes", SpecVersion.V3_2)),
        Map.entry("Path Item Object", Map.of("query", SpecVersion.V3_2, "additionalOperations", SpecVersion.V3_2)),
        Map.entry("Media Type Object", Map.of("description", SpecVersion.V3_2, "itemSchema", SpecVersion.V3_2, "prefixEncoding", SpecVersion.V3_2,
            "itemEncoding", SpecVersion.V3_2)),
        Map.entry("Encoding Object", Map.of("encoding", SpecVersion.V3_2, "prefixEncoding", SpecVersion.V3_2, "itemEncoding", SpecVersion.V3_2)),
        Map.entry("Response Object", Map.of("summary", SpecVersion.V3_2)),
        Map.entry("Example Object", Map.of("dataValue", SpecVersion.V3_2, "serializedValue", SpecVersion.V3_2)),
        Map.entry("Tag Object", Map.of("summary", SpecVersion.V3_2, "parent", SpecVersion.V3_2, "kind", SpecVersion.V3_2)),
        Map.entry("Security Scheme Object", Map.of("deprecated", SpecVersion.V3_2, "oauth2MetadataUrl", SpecVersion.V3_2)),
        Map.entry("OAuth Flows Object", Map.of("deviceAuthorization", SpecVersion.V3_2)),
        Map.entry("OAuth Flow Object", Map.of("deviceAuthorizationUrl", SpecVersion.V3_2)));

    /** Header names are HTTP field names (RFC 9110 tokens). */
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    private final DocumentLoader loader;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Deque<URI> resolving = new ArrayDeque<>();
    private final List<Schema> schemas = new ArrayList<>();
    private final Set<String> operationIds = new LinkedHashSet<>();
    private final List<Map.Entry<Cursor, String>> linkedOperations = new ArrayList<>();
    private SpecVersion version = SpecVersion.V3_1;
    private Dialect defaultDialect = Dialect.DRAFT_2020_12;

    OpenApiParser(DocumentLoader loader) {
        this.loader = loader;
    }

    List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** Every schema position encountered, used as roots for the schema registry. */
    List<Schema> schemas() {
        return schemas;
    }

    Optional<OpenApi> parse(Document document) {
        var root = new Obj(Cursor.root(document), "OpenAPI Object", ROOT_FIELDS);
        var openapi = root.required("openapi");
        var detected = SpecVersion.of(openapi);
        if (detected.isEmpty()) {
            error(root.cursor, openapi.isEmpty() ? "Not an OpenAPI document" : "Unsupported OpenAPI version '" + openapi + "'");
            return Optional.empty();
        }
        version = detected.get();
        root.introduced("OpenAPI Object");
        var dialect = root.string("jsonSchemaDialect");
        defaultDialect = version == SpecVersion.V3_0
            ? Dialect.OAS_3_0
            : dialect.flatMap(Dialect::fromUri).orElse(Dialect.DRAFT_2020_12);
        dialect.filter(uri -> Dialect.fromUri(uri).isEmpty())
            .ifPresent(uri -> warning(root.cursor, "Unknown jsonSchemaDialect '" + uri + "'; using JSON Schema 2020-12"));

        var info = root.get("info").map(this::info).orElseGet(() -> {
            root.missing("info");
            return new Info("", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), "", Map.of());
        });
        var servers = root.list("servers", this::server);
        var components = root.get("components").map(this::components).orElse(Components.EMPTY);
        var paths = root.get("paths").map(this::paths).orElse(Paths.EMPTY);
        if (version == SpecVersion.V3_0 && !root.json.has("paths")) {
            root.missing("paths");
        }
        if (version != SpecVersion.V3_0 && !root.json.has("paths") && !root.json.has("components") && !root.json.has("webhooks")) {
            error(root.cursor, "At least one of 'paths', 'components' or 'webhooks' is required");
        }
        var model = new OpenApi(openapi, version, root.string("$self"), info, dialect,
            servers.isEmpty() ? List.of(new Server("/", Optional.empty(), Optional.empty(), Map.of(), Map.of())) : servers,
            paths, root.map("webhooks", (name, cursor) -> pathItem(cursor)), components,
            root.list("security", this::securityRequirement), root.list("tags", this::tag), root.get("externalDocs").flatMap(this::externalDocs),
            root.extensions());
        checkSecurityReferences(root, model);
        linkedOperations.stream().filter(link -> !operationIds.contains(link.getValue()))
            .forEach(link -> warning(link.getKey(), "Link target operation '" + link.getValue() + "' does not exist"));
        return Optional.of(model);
    }

    // ---------------------------------------------------------------------------------------------
    // Objects

    private Info info(Cursor cursor) {
        var obj = new Obj(cursor, "Info Object", INFO_FIELDS);
        return new Info(obj.required("title"), obj.string("summary"), obj.string("description"), obj.string("termsOfService"),
            obj.get("contact").map(this::contact), obj.get("license").map(this::license), obj.required("version"), obj.extensions());
    }

    private Contact contact(Cursor cursor) {
        var obj = new Obj(cursor, "Contact Object", CONTACT_FIELDS);
        return new Contact(obj.string("name"), obj.string("url"), obj.string("email"), obj.extensions());
    }

    private License license(Cursor cursor) {
        var obj = new Obj(cursor, "License Object", LICENSE_FIELDS);
        if (obj.json.has("identifier") && obj.json.has("url")) {
            error(cursor, "License 'identifier' and 'url' are mutually exclusive");
        }
        return new License(obj.required("name"), obj.string("identifier"), obj.string("url"), obj.extensions());
    }

    private Optional<Server> server(Cursor cursor) {
        var obj = new Obj(cursor, "Server Object", SERVER_FIELDS);
        var url = obj.required("url");
        var variables = obj.map("variables", (name, c) -> serverVariable(c));
        var matcher = TEMPLATE_EXPRESSION.matcher(url);
        while (matcher.find()) {
            if (!variables.containsKey(matcher.group(1))) {
                error(cursor, "Server variable '" + matcher.group(1) + "' is not defined");
            }
        }
        return Optional.of(new Server(url, obj.string("description"), obj.string("name"), variables, obj.extensions()));
    }

    private Optional<ServerVariable> serverVariable(Cursor cursor) {
        var obj = new Obj(cursor, "Server Variable Object", SERVER_VARIABLE_FIELDS);
        var enumValues = obj.strings("enum");
        var defaultValue = obj.required("default");
        if (obj.json.has("enum") && enumValues.isEmpty()) {
            error(cursor, "Server variable 'enum' must not be empty");
        }
        if (!enumValues.isEmpty() && !enumValues.contains(defaultValue)) {
            error(cursor, "Server variable default '" + defaultValue + "' is not one of its enum values");
        }
        return Optional.of(new ServerVariable(enumValues, defaultValue, obj.string("description"), obj.extensions()));
    }

    private Components components(Cursor cursor) {
        var obj = new Obj(cursor, "Components Object", COMPONENTS_FIELDS);
        return new Components(
            obj.map("schemas", (name, c) -> schema(c)),
            obj.map("responses", (name, c) -> component(c, RESPONSE_FIELDS, this::response)),
            obj.map("parameters", (name, c) -> component(c, PARAMETER_FIELDS, this::parameter)),
            obj.map("examples", (name, c) -> component(c, EXAMPLE_FIELDS, this::example)),
            obj.map("requestBodies", (name, c) -> component(c, REQUEST_BODY_FIELDS, this::requestBody)),
            obj.map("headers", (name, c) -> component(c, HEADER_FIELDS, this::header)),
            obj.map("securitySchemes", (name, c) -> component(c, SECURITY_SCHEME_FIELDS, this::securityScheme)),
            obj.map("links", (name, c) -> component(c, LINK_FIELDS, this::link)),
            obj.map("callbacks", (name, c) -> component(c, Set.of(), this::callback)),
            obj.map("pathItems", (name, c) -> pathItem(c)),
            obj.map("mediaTypes", (name, c) -> component(c, MEDIA_TYPE_FIELDS, this::mediaType)),
            obj.extensions());
    }

    private Paths paths(Cursor cursor) {
        var obj = new Obj(cursor, "Paths Object", Set.of());
        var items = new LinkedHashMap<String, PathItem>();
        var normalized = new LinkedHashMap<String, String>();
        obj.json.members().forEach((path, value) -> {
            if (path.startsWith("x-")) {
                return;
            }
            var child = cursor.child(path, value);
            if (!path.startsWith("/")) {
                error(child, "Path '" + path + "' must begin with '/'");
                return;
            }
            var shape = TEMPLATE_EXPRESSION.matcher(path).replaceAll("{}");
            var previous = normalized.putIfAbsent(shape, path);
            if (previous != null) {
                error(child, "Path '" + path + "' is equivalent to '" + previous + "'");
            }
            pathItem(child).ifPresent(item -> {
                items.put(path, item);
                checkPathParameters(child, path, item);
            });
        });
        return new Paths(items, obj.extensions());
    }

    private void checkPathParameters(Cursor cursor, String path, PathItem item) {
        var templated = new LinkedHashSet<String>();
        var matcher = TEMPLATE_EXPRESSION.matcher(path);
        while (matcher.find()) {
            templated.add(matcher.group(1));
        }
        item.operations().forEach((method, operation) -> {
            var declared = operation.effectiveParameters(item).stream()
                .filter(p -> p.in() == ParameterLocation.PATH).map(Parameter::name).toList();
            for (var name : templated) {
                if (!declared.contains(name)) {
                    warning(cursor, method + " " + path + ": path parameter '" + name + "' is not declared");
                }
            }
            for (var name : declared) {
                if (!templated.contains(name)) {
                    warning(cursor, method + " " + path + ": path parameter '" + name + "' does not occur in the path template");
                }
            }
        });
    }

    private Optional<PathItem> pathItem(Cursor cursor) {
        var obj = new Obj(cursor, "Path Item Object", PATH_ITEM_FIELDS);
        Optional<PathItem> referenced = obj.string("$ref")
            .flatMap(ref -> resolveRef(cursor, ref).flatMap(target -> guarded(target, () -> pathItem(target))));
        if (obj.json.has("$ref") && referenced.isEmpty()) {
            return Optional.empty();
        }
        var operations = new LinkedHashMap<String, Operation>();
        for (var method : PathItem.FIXED_METHODS) {
            obj.get(method).flatMap(this::operation).ifPresent(op -> operations.put(method.toUpperCase(Locale.ROOT), op));
        }
        obj.map("additionalOperations", (method, c) -> {
            if (!method.equals(method.toUpperCase(Locale.ROOT)) || PathItem.FIXED_METHODS.contains(method.toLowerCase(Locale.ROOT))) {
                error(c, "Additional operation method '" + method + "' must be upper case and not one of the fixed methods");
                return Optional.empty();
            }
            return operation(c);
        }).forEach(operations::put);
        var local = new PathItem(obj.string("summary"), obj.string("description"), operations, obj.list("servers", this::server),
            obj.list("parameters", c -> component(c, PARAMETER_FIELDS, this::parameter)), obj.extensions());
        checkDuplicateParameters(cursor, local.parameters());
        var item = referenced.map(base -> merge(base, local)).orElse(local);
        checkQuerystring(cursor, item.parameters());
        item.operations().values().forEach(operation -> checkQuerystring(cursor, operation.effectiveParameters(item)));
        return Optional.of(item);
    }

    private static PathItem merge(PathItem base, PathItem local) {
        var operations = new LinkedHashMap<>(base.operations());
        operations.putAll(local.operations());
        var parameters = new ArrayList<Parameter>();
        base.parameters().stream().filter(p -> local.parameters().stream().noneMatch(p::sameIdentity)).forEach(parameters::add);
        parameters.addAll(local.parameters());
        var extensions = new LinkedHashMap<>(base.extensions());
        extensions.putAll(local.extensions());
        return new PathItem(local.summary().or(base::summary), local.description().or(base::description), operations,
            local.servers().isEmpty() ? base.servers() : local.servers(), parameters, extensions);
    }

    private Optional<Operation> operation(Cursor cursor) {
        var obj = new Obj(cursor, "Operation Object", OPERATION_FIELDS);
        var operationId = obj.string("operationId");
        operationId.filter(id -> !operationIds.add(id)).ifPresent(id -> error(cursor, "Duplicate operationId '" + id + "'"));
        var parameters = obj.list("parameters", c -> component(c, PARAMETER_FIELDS, this::parameter));
        checkDuplicateParameters(cursor, parameters);
        if (!obj.json.has("responses") && version == SpecVersion.V3_0) {
            obj.missing("responses");
        }
        var responses = obj.get("responses").map(this::responses).orElse(Responses.EMPTY);
        Optional<List<SecurityRequirement>> security = obj.get("security").map(c -> obj.list("security", this::securityRequirement));
        return Optional.of(new Operation(obj.strings("tags"), obj.string("summary"), obj.string("description"),
            obj.get("externalDocs").flatMap(this::externalDocs), operationId, parameters,
            obj.get("requestBody").flatMap(c -> component(c, REQUEST_BODY_FIELDS, this::requestBody)), responses,
            obj.map("callbacks", (name, c) -> component(c, Set.of(), this::callback)), obj.bool("deprecated", false), security,
            obj.list("servers", this::server), obj.extensions()));
    }

    private void checkDuplicateParameters(Cursor cursor, List<Parameter> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            for (int j = i + 1; j < parameters.size(); j++) {
                if (parameters.get(i).sameIdentity(parameters.get(j))) {
                    error(cursor, "Duplicate parameter '" + parameters.get(i).name() + "' in " + parameters.get(i).in().value());
                }
            }
        }
    }

    private Optional<ExternalDocs> externalDocs(Cursor cursor) {
        var obj = new Obj(cursor, "External Documentation Object", EXTERNAL_DOCS_FIELDS);
        return Optional.of(new ExternalDocs(obj.string("description"), obj.required("url"), obj.extensions()));
    }

    private Optional<Parameter> parameter(Cursor cursor) {
        var obj = new Obj(cursor, "Parameter Object", PARAMETER_FIELDS);
        var name = obj.required("name");
        var inValue = obj.required("in");
        var in = ParameterLocation.of(inValue);
        if (in.isEmpty()) {
            if (!inValue.isEmpty()) {
                error(cursor, "Invalid parameter location '" + inValue + "'");
            }
            return Optional.empty();
        }
        var location = in.get();
        if (location == ParameterLocation.QUERYSTRING && version.compareTo(SpecVersion.V3_2) < 0) {
            error(cursor, "Querystring parameters require OpenAPI 3.2");
        }
        if (location == ParameterLocation.PATH && (name.contains("{") || name.contains("}"))) {
            error(cursor, "Path parameter name '" + name + "' must not contain '{' or '}'");
        }
        if (location == ParameterLocation.HEADER && !TOKEN.matcher(name).matches()) {
            error(cursor, "Header parameter name '" + name + "' is not a valid HTTP field name");
        }
        var style = obj.string("style").flatMap(value -> {
            var parsed = ParameterStyle.of(value);
            if (parsed.isEmpty()) {
                error(cursor, "Invalid parameter style '" + value + "'");
            }
            return parsed;
        }).orElse(location.defaultStyle());
        if (!allowedStyles(location).contains(style)) {
            error(cursor, "Style '" + style.value() + "' is not allowed for " + location.value() + " parameters");
        }
        if (style == ParameterStyle.COOKIE && version.compareTo(SpecVersion.V3_2) < 0) {
            error(cursor, "Style 'cookie' requires OpenAPI 3.2");
        }
        if (obj.json.has("allowReserved") && !allowsReserved(location, style)) {
            error(cursor, "'allowReserved' is not allowed for " + location.value() + " parameters" + (location == ParameterLocation.COOKIE
                ? " with style '" + style.value() + "'" : ""));
        }
        checkExampleOrExamples(cursor, obj);
        var required = obj.bool("required", false);
        if (location == ParameterLocation.PATH && !required) {
            warning(cursor, "Path parameter '" + name + "' must be required");
            required = true;
        }
        var schema = obj.get("schema").flatMap(this::schema);
        var content = obj.map("content", (type, c) -> component(c, MEDIA_TYPE_FIELDS, this::mediaType));
        checkSchemaOrContent(cursor, obj, location == ParameterLocation.QUERYSTRING);
        if (location == ParameterLocation.HEADER && Set.of("accept", "content-type", "authorization").contains(name.toLowerCase(Locale.ROOT))) {
            warning(cursor, "Header parameter '" + name + "' is ignored; it is controlled by other OpenAPI constructs");
        }
        return Optional.of(new Parameter(name, location, obj.string("description"), required, obj.bool("deprecated", false),
            obj.bool("allowEmptyValue", false), style, obj.bool("explode", style.explodeByDefault()), obj.bool("allowReserved", false),
            schema, obj.value("example"), obj.map("examples", (n, c) -> component(c, EXAMPLE_FIELDS, this::example)), content, obj.extensions()));
    }

    /** {@code allowReserved} is for query parameters, and since 3.2 also for path and form-style cookie parameters. */
    private boolean allowsReserved(ParameterLocation location, ParameterStyle style) {
        return location == ParameterLocation.QUERY || version == SpecVersion.V3_2
            && (location == ParameterLocation.PATH || location == ParameterLocation.COOKIE && style == ParameterStyle.FORM);
    }

    /** The {@code headers} of a response or encoding, whose names must be HTTP field names. */
    private Map<String, Header> headers(Obj obj) {
        return obj.map("headers", (name, c) -> {
            if (!TOKEN.matcher(name).matches()) {
                error(c, "Header name '" + name + "' is not a valid HTTP field name");
            }
            return component(c, HEADER_FIELDS, this::header);
        });
    }

    private void checkExampleOrExamples(Cursor cursor, Obj obj) {
        if (obj.json.has("example") && obj.json.has("examples")) {
            error(cursor, "'example' and 'examples' are mutually exclusive");
        }
    }

    /** {@code encoding} applies to named parts, {@code prefixEncoding} and {@code itemEncoding} to positional ones. */
    private void checkEncodings(Cursor cursor, Obj obj) {
        if (obj.json.has("encoding") && (obj.json.has("prefixEncoding") || obj.json.has("itemEncoding"))) {
            error(cursor, "'encoding' cannot be combined with 'prefixEncoding' or 'itemEncoding'");
        }
    }

    /** At most one {@code querystring} parameter, and none together with {@code query} parameters. */
    private void checkQuerystring(Cursor cursor, List<Parameter> parameters) {
        var querystrings = parameters.stream().filter(p -> p.in() == ParameterLocation.QUERYSTRING).count();
        if (querystrings > 1) {
            error(cursor, "At most one querystring parameter is allowed");
        }
        if (querystrings > 0 && parameters.stream().anyMatch(p -> p.in() == ParameterLocation.QUERY)) {
            error(cursor, "A querystring parameter cannot be combined with query parameters");
        }
    }

    private static Set<ParameterStyle> allowedStyles(ParameterLocation location) {
        return switch (location) {
            case PATH -> Set.of(ParameterStyle.MATRIX, ParameterStyle.LABEL, ParameterStyle.SIMPLE);
            case QUERY -> Set.of(ParameterStyle.FORM, ParameterStyle.SPACE_DELIMITED, ParameterStyle.PIPE_DELIMITED, ParameterStyle.DEEP_OBJECT);
            case QUERYSTRING -> Set.of(ParameterStyle.FORM);
            case HEADER -> Set.of(ParameterStyle.SIMPLE);
            case COOKIE -> Set.of(ParameterStyle.FORM, ParameterStyle.COOKIE);
        };
    }

    private void checkSchemaOrContent(Cursor cursor, Obj obj, boolean contentRequired) {
        var hasSchema = obj.json.has("schema");
        var hasContent = obj.json.has("content");
        if (hasSchema && hasContent) {
            error(cursor, "'schema' and 'content' are mutually exclusive");
        } else if (hasContent && obj.json.object("content").map(JsonObject::size).orElse(0) != 1) {
            error(cursor, "'content' must contain exactly one media type");
        } else if (contentRequired && !hasContent) {
            error(cursor, "A querystring parameter must use 'content'");
        } else if (!hasSchema && !hasContent) {
            warning(cursor, "Should define either 'schema' or 'content'");
        }
    }

    private Optional<Header> header(Cursor cursor) {
        var obj = new Obj(cursor, "Header Object", HEADER_FIELDS);
        obj.string("style").filter(style -> !style.equals("simple")).ifPresent(style -> error(cursor, "Header style must be 'simple'"));
        checkSchemaOrContent(cursor, obj, false);
        checkExampleOrExamples(cursor, obj);
        return Optional.of(new Header(obj.string("description"), obj.bool("required", false), obj.bool("deprecated", false),
            obj.bool("explode", false), obj.get("schema").flatMap(this::schema), obj.value("example"),
            obj.map("examples", (n, c) -> component(c, EXAMPLE_FIELDS, this::example)), obj.map("content", (type, c) -> component(c, MEDIA_TYPE_FIELDS, this::mediaType)),
            obj.extensions()));
    }

    private Optional<RequestBody> requestBody(Cursor cursor) {
        var obj = new Obj(cursor, "Request Body Object", REQUEST_BODY_FIELDS);
        if (!obj.json.has("content")) {
            obj.missing("content");
        }
        return Optional.of(new RequestBody(obj.string("description"), obj.map("content", (type, c) -> component(c, MEDIA_TYPE_FIELDS, this::mediaType)),
            obj.bool("required", false), obj.extensions()));
    }

    private Optional<MediaType> mediaType(Cursor cursor) {
        var obj = new Obj(cursor, "Media Type Object", MEDIA_TYPE_FIELDS);
        checkExampleOrExamples(cursor, obj);
        checkEncodings(cursor, obj);
        return Optional.of(new MediaType(obj.get("schema").flatMap(this::schema), obj.get("itemSchema").flatMap(this::schema),
            obj.value("example"), obj.map("examples", (n, c) -> component(c, EXAMPLE_FIELDS, this::example)), obj.map("encoding", (n, c) -> encoding(c)),
            obj.list("prefixEncoding", this::encoding), obj.get("itemEncoding").flatMap(this::encoding), obj.extensions()));
    }

    private Optional<Encoding> encoding(Cursor cursor) {
        var obj = new Obj(cursor, "Encoding Object", ENCODING_FIELDS);
        checkEncodings(cursor, obj);
        var style = obj.string("style").flatMap(value -> {
            var parsed = ParameterStyle.of(value);
            if (parsed.isEmpty()) {
                error(cursor, "Invalid encoding style '" + value + "'");
            }
            return parsed;
        });
        return Optional.of(new Encoding(obj.string("contentType"), headers(obj), style,
            obj.optionalBool("explode"), obj.bool("allowReserved", false), obj.map("encoding", (n, c) -> encoding(c)),
            obj.list("prefixEncoding", this::encoding), obj.get("itemEncoding").flatMap(this::encoding), obj.extensions()));
    }

    private Responses responses(Cursor cursor) {
        var obj = new Obj(cursor, "Responses Object", Set.of());
        var byStatus = new LinkedHashMap<String, Response>();
        Optional<Response> defaultResponse = Optional.empty();
        for (var entry : obj.json.members().entrySet()) {
            var key = entry.getKey();
            var child = cursor.child(key, entry.getValue());
            if (key.startsWith("x-")) {
                continue;
            }
            if (key.equals("default")) {
                defaultResponse = component(child, RESPONSE_FIELDS, this::response);
            } else if (STATUS_CODE.matcher(key).matches()) {
                component(child, RESPONSE_FIELDS, this::response).ifPresent(response -> byStatus.put(key, response));
            } else {
                error(child, "Invalid response status code '" + key + "'");
            }
        }
        if (byStatus.isEmpty() && defaultResponse.isEmpty()) {
            warning(cursor, "Responses Object should contain at least one response");
        }
        return new Responses(byStatus, defaultResponse, obj.extensions());
    }

    private Optional<Response> response(Cursor cursor) {
        var obj = new Obj(cursor, "Response Object", RESPONSE_FIELDS);
        if (!obj.json.has("description") && version != SpecVersion.V3_2) {
            obj.missing("description");
        }
        return Optional.of(new Response(obj.string("summary"), obj.string("description"),
            headers(obj), obj.map("content", (type, c) -> component(c, MEDIA_TYPE_FIELDS, this::mediaType)),
            obj.map("links", (name, c) -> component(c, LINK_FIELDS, this::link)), obj.extensions()));
    }

    private Optional<Callback> callback(Cursor cursor) {
        var obj = new Obj(cursor, "Callback Object", Set.of());
        var expressions = new LinkedHashMap<String, PathItem>();
        obj.json.members().forEach((expression, value) -> {
            if (!expression.startsWith("x-")) {
                pathItem(cursor.child(expression, value)).ifPresent(item -> expressions.put(expression, item));
            }
        });
        return Optional.of(new Callback(expressions, obj.extensions()));
    }

    private Optional<Example> example(Cursor cursor) {
        var obj = new Obj(cursor, "Example Object", EXAMPLE_FIELDS);
        var exclusive = List.of("value", "dataValue", "serializedValue", "externalValue").stream().filter(obj.json::has).toList();
        if (exclusive.contains("value") && exclusive.size() > 1) {
            error(cursor, "Example 'value' is mutually exclusive with " + exclusive.subList(1, exclusive.size()));
        }
        if (exclusive.contains("serializedValue") && exclusive.contains("externalValue")) {
            error(cursor, "Example 'serializedValue' and 'externalValue' are mutually exclusive");
        }
        return Optional.of(new Example(obj.string("summary"), obj.string("description"), obj.value("value"), obj.value("dataValue"),
            obj.string("serializedValue"), obj.string("externalValue"), obj.extensions()));
    }

    private Optional<Link> link(Cursor cursor) {
        var obj = new Obj(cursor, "Link Object", LINK_FIELDS);
        if (obj.json.has("operationRef") == obj.json.has("operationId")) {
            error(cursor, "Exactly one of 'operationRef' and 'operationId' is required");
        }
        obj.string("operationId").ifPresent(id -> linkedOperations.add(Map.entry(cursor, id)));
        obj.string("operationRef").filter(ref -> ref.startsWith("#")).ifPresent(ref -> {
            var target = Uris.parse(ref).toOptional().map(Uris::fragment).flatMap(fragment -> JsonPointer.parse(fragment).toOptional())
                .flatMap(cursor.document()::resolve);
            if (target.isEmpty()) {
                warning(cursor, "Link operationRef '" + ref + "' does not resolve");
            }
        });
        var parameters = obj.json.object("parameters").map(JsonObject::members).orElse(Map.of());
        return Optional.of(new Link(obj.string("operationRef"), obj.string("operationId"), parameters, obj.value("requestBody"),
            obj.string("description"), obj.get("server").flatMap(this::server), obj.extensions()));
    }

    private Optional<Tag> tag(Cursor cursor) {
        var obj = new Obj(cursor, "Tag Object", TAG_FIELDS);
        return Optional.of(new Tag(obj.required("name"), obj.string("summary"), obj.string("description"),
            obj.get("externalDocs").flatMap(this::externalDocs), obj.string("parent"), obj.string("kind"), obj.extensions()));
    }

    private Optional<SecurityRequirement> securityRequirement(Cursor cursor) {
        var obj = new Obj(cursor, "Security Requirement Object", Set.of());
        var schemes = new LinkedHashMap<String, List<String>>();
        obj.json.members().forEach((name, value) -> {
            if (value instanceof JsonArray array && array.values().stream().allMatch(item -> item.asString().isPresent())) {
                schemes.put(name, array.values().stream().map(item -> item.asString().orElseThrow()).toList());
            } else {
                error(cursor.child(name, value), "Security requirement scopes must be an array of strings");
            }
        });
        return Optional.of(new SecurityRequirement(schemes));
    }

    private void checkSecurityReferences(Obj root, OpenApi model) {
        var defined = model.components().securitySchemes().keySet();
        var requirements = new ArrayList<>(model.security());
        model.paths().items().values().forEach(item -> item.operations().values()
            .forEach(op -> op.security().ifPresent(requirements::addAll)));
        requirements.stream().flatMap(r -> r.schemes().keySet().stream()).distinct()
            .filter(name -> !defined.contains(name) && !name.contains(":") && !name.contains("/"))
            .forEach(name -> warning(root.cursor, "Security scheme '" + name + "' is not defined in components"));
    }

    private Optional<SecurityScheme> securityScheme(Cursor cursor) {
        var obj = new Obj(cursor, "Security Scheme Object", SECURITY_SCHEME_FIELDS);
        var type = obj.required("type");
        var description = obj.string("description");
        var deprecated = obj.bool("deprecated", false);
        var extensions = obj.extensions();
        return switch (type) {
            case "apiKey" -> {
                var name = obj.required("name");
                var in = obj.required("in");
                var location = ParameterLocation.of(in).filter(l -> l == ParameterLocation.QUERY || l == ParameterLocation.HEADER
                    || l == ParameterLocation.COOKIE);
                if (location.isEmpty()) {
                    error(cursor, "API key location must be query, header or cookie");
                }
                yield location.map(l -> new SecurityScheme.ApiKey(name, l, description, deprecated, extensions));
            }
            case "http" -> Optional.of(new SecurityScheme.Http(obj.required("scheme"), obj.string("bearerFormat"), description, deprecated,
                extensions));
            case "mutualTLS" -> Optional.of(new SecurityScheme.MutualTls(description, deprecated, extensions));
            case "oauth2" -> obj.get("flows").or(() -> {
                obj.missing("flows");
                return Optional.empty();
            }).map(c -> new SecurityScheme.OAuth2(oauthFlows(c), obj.string("oauth2MetadataUrl"), description, deprecated, extensions));
            case "openIdConnect" -> Optional.of(new SecurityScheme.OpenIdConnect(obj.required("openIdConnectUrl"), description, deprecated,
                extensions));
            default -> {
                if (!type.isEmpty()) {
                    error(cursor, "Invalid security scheme type '" + type + "'");
                }
                yield Optional.empty();
            }
        };
    }

    private OAuthFlows oauthFlows(Cursor cursor) {
        var obj = new Obj(cursor, "OAuth Flows Object", OAUTH_FLOWS_FIELDS);
        return new OAuthFlows(
            obj.get("implicit").map(c -> oauthFlow(c, "authorizationUrl")),
            obj.get("password").map(c -> oauthFlow(c, "tokenUrl")),
            obj.get("clientCredentials").map(c -> oauthFlow(c, "tokenUrl")),
            obj.get("authorizationCode").map(c -> oauthFlow(c, "authorizationUrl", "tokenUrl")),
            obj.get("deviceAuthorization").map(c -> oauthFlow(c, "deviceAuthorizationUrl", "tokenUrl")),
            obj.extensions());
    }

    private OAuthFlow oauthFlow(Cursor cursor, String... requiredUrls) {
        var obj = new Obj(cursor, "OAuth Flow Object", OAUTH_FLOW_FIELDS);
        for (var url : requiredUrls) {
            obj.required(url);
        }
        if (!obj.json.has("scopes")) {
            obj.missing("scopes");
        }
        var scopes = new LinkedHashMap<String, String>();
        obj.json.object("scopes").ifPresent(s -> s.members().forEach((scope, text) -> scopes.put(scope, text.asString().orElse(""))));
        return new OAuthFlow(obj.string("authorizationUrl"), obj.string("deviceAuthorizationUrl"), obj.string("tokenUrl"),
            obj.string("refreshUrl"), scopes, obj.extensions());
    }

    private Optional<Schema> schema(Cursor cursor) {
        var value = cursor.value();
        if (!(value instanceof JsonObject) && !(value instanceof JsonBoolean && version != SpecVersion.V3_0)) {
            error(cursor, "A schema must be an object" + (version == SpecVersion.V3_0 ? "" : " or a boolean"));
            return Optional.empty();
        }
        var document = cursor.document();
        var schema = Schema.at(value, document.uri(), cursor.pointer(), SchemaRegistry.baseUri(document, cursor.pointer(), dialectOf(document)),
            dialectOf(document));
        schemas.add(schema);
        return Optional.of(schema);
    }

    /** The default dialect of schemas in {@code document}. */
    private Dialect dialectOf(Document document) {
        return document.root().asObject().flatMap(root -> root.string("openapi")).flatMap(SpecVersion::of)
            .map(v -> v == SpecVersion.V3_0 ? Dialect.OAS_3_0 : document.root().asObject().flatMap(r -> r.string("jsonSchemaDialect"))
                .flatMap(Dialect::fromUri).orElse(Dialect.DRAFT_2020_12))
            .orElse(defaultDialect);
    }

    // ---------------------------------------------------------------------------------------------
    // References

    /**
     * Parses a component that may be a Reference Object. In 3.1+ the {@code summary} and
     * {@code description} next to a {@code $ref} override those of the target.
     */
    private <T> Optional<T> component(Cursor cursor, Set<String> fields, Function<Cursor, Optional<T>> parse) {
        if (!(cursor.value() instanceof JsonObject object) || !object.has("$ref")) {
            return parse.apply(cursor);
        }
        var ref = object.get("$ref").orElseThrow();
        if (!(ref instanceof JsonString(var refText))) {
            error(cursor, "'$ref' must be a string");
            return Optional.empty();
        }
        object.keys().stream()
            .filter(key -> !key.equals("$ref") && !key.startsWith("x-") && (version == SpecVersion.V3_0 || !Set.of("summary", "description").contains(key)))
            .forEach(key -> warning(cursor, "Field '" + key + "' next to '$ref' is ignored"));
        return resolveRef(cursor, refText).flatMap(target -> guarded(target, () -> {
            var overridden = target;
            if (version != SpecVersion.V3_0 && target.value() instanceof JsonObject targetObject) {
                var merged = targetObject;
                for (var key : List.of("summary", "description")) {
                    var override = object.get(key);
                    if (override.isPresent() && fields.contains(key)) {
                        merged = merged.with(key, override.get());
                    }
                }
                overridden = new Cursor(target.document(), target.pointer(), merged);
            }
            return component(overridden, fields, parse);
        }));
    }

    private <T> Optional<T> guarded(Cursor target, java.util.function.Supplier<Optional<T>> parse) {
        var location = target.location();
        if (resolving.contains(location)) {
            error(target, "Circular reference to " + location);
            return Optional.empty();
        }
        resolving.push(location);
        try {
            return parse.get();
        } finally {
            resolving.pop();
        }
    }

    private Optional<Cursor> resolveRef(Cursor from, String ref) {
        var uri = Uris.resolve(from.document().baseUri(), ref);
        if (uri.isErr()) {
            error(from, uri.failure().orElseThrow());
            return Optional.empty();
        }
        var target = uri.toOptional().orElseThrow();
        var document = loader.load(target);
        if (document.isErr()) {
            error(from, "Cannot resolve reference '" + ref + "': " + document.failure().orElseThrow().message());
            return Optional.empty();
        }
        var fragment = Uris.fragment(target);
        var pointer = JsonPointer.parse(fragment);
        if (pointer.isErr()) {
            error(from, "Reference '" + ref + "' must use a JSON pointer fragment");
            return Optional.empty();
        }
        var doc = document.toOptional().orElseThrow();
        var resolvedPointer = pointer.toOptional().orElseThrow();
        var value = resolvedPointer.resolve(doc.root());
        if (value.isEmpty()) {
            error(from, "Reference target '" + ref + "' does not exist");
            return Optional.empty();
        }
        return Optional.of(new Cursor(doc, resolvedPointer, value.get()));
    }

    // ---------------------------------------------------------------------------------------------
    // Diagnostics

    private void error(Cursor cursor, String message) {
        diagnostics.add(Diagnostic.error(message, cursor.document().uri(), cursor.pointer())
            .withPosition(cursor.document().position(cursor.pointer())));
    }

    private void warning(Cursor cursor, String message) {
        diagnostics.add(Diagnostic.warning(message, cursor.document().uri(), cursor.pointer())
            .withPosition(cursor.document().position(cursor.pointer())));
    }

    /** Typed, diagnosing access to the fields of a JSON object. */
    private final class Obj {
        private final Cursor cursor;
        private final JsonObject json;
        private final boolean valid;

        /** Reports fields that the description's version does not have yet. */
        void introduced(String type) {
            INTRODUCED.getOrDefault(type, Map.of()).forEach((field, since) -> {
                if (json.has(field) && version.compareTo(since) < 0) {
                    error(cursor.child(field, json.get(field).orElseThrow()), "Field '" + field + "' of the " + type + " requires OpenAPI " + since.prefix());
                }
            });
        }

        Obj(Cursor cursor, String type, Set<String> known) {
            this.cursor = cursor;
            this.valid = cursor.value() instanceof JsonObject;
            if (cursor.value() instanceof JsonObject object) {
                this.json = object;
                if (!known.isEmpty()) {
                    object.keys().stream().filter(key -> !key.startsWith("x-") && !known.contains(key))
                        .forEach(key -> error(cursor.child(key, object.get(key).orElseThrow()), "Unknown field '" + key + "' in " + type));
                }
                if (!type.equals("OpenAPI Object")) {
                    introduced(type);
                }
            } else {
                error(cursor, type + " must be an object");
                this.json = JsonObject.EMPTY;
            }
        }

        Optional<Cursor> get(String name) {
            return json.get(name).map(value -> cursor.child(name, value));
        }

        Optional<JsonValue> value(String name) {
            return json.get(name);
        }

        Optional<String> string(String name) {
            return get(name).flatMap(child -> {
                var text = child.value().asString();
                if (text.isEmpty()) {
                    error(child, "Field '" + name + "' must be a string");
                }
                return text;
            });
        }

        String required(String name) {
            if (!json.has(name)) {
                missing(name);
            }
            return string(name).orElse("");
        }

        void missing(String name) {
            if (valid) {
                error(cursor, "Missing required field '" + name + "'");
            }
        }

        boolean bool(String name, boolean defaultValue) {
            return optionalBool(name).orElse(defaultValue);
        }

        Optional<Boolean> optionalBool(String name) {
            return get(name).flatMap(child -> {
                var flag = child.value().asBoolean();
                if (flag.isEmpty()) {
                    error(child, "Field '" + name + "' must be a boolean");
                }
                return flag;
            });
        }

        List<String> strings(String name) {
            return list(name, child -> {
                var text = child.value().asString();
                if (text.isEmpty()) {
                    error(child, "Field '" + name + "' must contain only strings");
                }
                return text;
            });
        }

        <T> List<T> list(String name, Function<Cursor, Optional<T>> parse) {
            var result = new ArrayList<T>();
            get(name).ifPresent(child -> {
                if (child.value() instanceof JsonArray array) {
                    for (int i = 0; i < array.size(); i++) {
                        parse.apply(child.child(Integer.toString(i), array.values().get(i))).ifPresent(result::add);
                    }
                } else {
                    error(child, "Field '" + name + "' must be an array");
                }
            });
            return result;
        }

        <T> Map<String, T> map(String name, BiFunction<String, Cursor, Optional<T>> parse) {
            var result = new LinkedHashMap<String, T>();
            get(name).ifPresent(child -> {
                if (child.value() instanceof JsonObject object) {
                    object.members().forEach((key, value) ->
                        parse.apply(key, child.child(key, value)).ifPresent(item -> result.put(key, item)));
                } else {
                    error(child, "Field '" + name + "' must be an object");
                }
            });
            return result;
        }

        Map<String, JsonValue> extensions() {
            var result = new LinkedHashMap<String, JsonValue>();
            json.members().forEach((key, value) -> {
                if (key.startsWith("x-")) {
                    result.put(key, value);
                }
            });
            return result;
        }
    }
}
