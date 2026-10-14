package dev.openapifn.parse;

import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Converts a Swagger / OpenAPI 2.0 description to OpenAPI 3.0, so that parsing, validation, the
 * runtime and code generation only deal with 3.x:
 * <ul>
 *   <li>{@code host}, {@code basePath} and {@code schemes} become servers</li>
 *   <li>{@code body} and {@code formData} parameters become request bodies (multipart when a file is
 *       uploaded or {@code consumes} says so, else form-urlencoded), and {@code consumes} and
 *       {@code produces} become the media types of request bodies and responses</li>
 *   <li>other parameters get a {@code schema}; {@code collectionFormat} becomes {@code style} and
 *       {@code explode}</li>
 *   <li>{@code definitions}, {@code parameters}, {@code responses} and {@code securityDefinitions}
 *       move to {@code components}, and local references follow them</li>
 *   <li>in schemas, a string {@code discriminator} becomes a Discriminator Object, {@code x-nullable}
 *       becomes {@code nullable} and {@code type: file} a binary string</li>
 * </ul>
 * Descriptions spanning several documents are made self-contained first (see {@link Swagger2Bundler}).
 */
final class Swagger2Converter {

    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch");
    private static final List<String> VALUE_KEYWORDS = List.of("type", "format", "items", "default", "maximum", "exclusiveMaximum", "minimum",
        "exclusiveMinimum", "maxLength", "minLength", "pattern", "maxItems", "minItems", "uniqueItems", "enum", "multipleOf");
    private static final Set<String> DATA_KEYWORDS = Set.of("example", "examples", "default", "enum", "const", "x-example");
    private static final List<String> JSON = List.of("application/json");

    /**
     * The converted description.
     *
     * @param root the OpenAPI 3.0 document
     * @param warnings constructs that have no exact 3.0 equivalent
     */
    record Conversion(JsonObject root, List<String> warnings) {
    }

    private final JsonObject source;
    private final List<String> consumes;
    private final List<String> produces;
    private final Map<String, JsonObject> globalParameters;
    private final List<String> warnings = new ArrayList<>();

    private Swagger2Converter(JsonObject source) {
        this.source = source;
        this.consumes = strings(source, "consumes").orElse(JSON);
        this.produces = strings(source, "produces").orElse(JSON);
        var parameters = new LinkedHashMap<String, JsonObject>();
        source.object("parameters").ifPresent(map -> map.members().forEach((name, value) -> value.asObject().ifPresent(p -> parameters.put(name, p))));
        this.globalParameters = parameters;
    }

    /** Whether {@code root} is a Swagger 2.0 description. */
    static boolean applies(JsonValue root) {
        return root instanceof JsonObject object && object.string("swagger").filter("2.0"::equals).isPresent();
    }

    static Conversion convert(JsonObject source) {
        var converter = new Swagger2Converter(source);
        return new Conversion(converter.root(), List.copyOf(converter.warnings));
    }

    private JsonObject root() {
        var out = new LinkedHashMap<String, JsonValue>();
        out.put("openapi", JsonString.of("3.0.3"));
        source.get("info").ifPresent(info -> out.put("info", info));
        out.put("servers", servers());
        out.put("paths", paths());
        out.put("components", components());
        for (var key : List.of("security", "tags", "externalDocs")) {
            source.get(key).ifPresent(value -> out.put(key, value));
        }
        extensions(source, out);
        return new JsonObject(out);
    }

    private JsonArray servers() {
        var basePath = source.string("basePath").orElse("");
        var urls = source.string("host")
            .map(host -> strings(source, "schemes").orElse(List.of("https")).stream().map(scheme -> scheme + "://" + host + basePath).toList())
            .orElse(List.of(basePath.isEmpty() ? "/" : basePath));
        return new JsonArray(urls.stream().<JsonValue>map(url -> JsonObject.builder().put("url", url).build()).toList());
    }

    // ---------------------------------------------------------------------------------------------
    // Paths and operations

    private JsonObject paths() {
        var out = new LinkedHashMap<String, JsonValue>();
        source.object("paths").orElse(JsonObject.EMPTY).members().forEach((path, value) -> {
            var item = value.asObject().orElse(JsonObject.EMPTY);
            out.put(path, path.startsWith("x-") ? value : pathItem(item));
        });
        return new JsonObject(out);
    }

    private JsonObject pathItem(JsonObject item) {
        var out = new LinkedHashMap<String, JsonValue>();
        item.string("$ref").ifPresent(ref -> out.put("$ref", JsonString.of(ref)));
        var shared = parameters(item);
        var sharedPlain = shared.stream().filter(p -> !isBodyOrForm(p)).map(this::parameter).toList();
        if (!sharedPlain.isEmpty()) {
            out.put("parameters", new JsonArray(sharedPlain));
        }
        for (var method : METHODS) {
            item.object(method).ifPresent(operation -> out.put(method, operation(operation, shared)));
        }
        extensions(item, out);
        return new JsonObject(out);
    }

    private JsonObject operation(JsonObject operation, List<JsonObject> shared) {
        var out = new LinkedHashMap<String, JsonValue>();
        for (var key : List.of("tags", "summary", "description", "externalDocs", "operationId", "deprecated", "security")) {
            operation.get(key).ifPresent(value -> out.put(key, value));
        }
        var operationConsumes = strings(operation, "consumes").orElse(consumes);
        var operationProduces = strings(operation, "produces").orElse(produces);
        var own = parameters(operation);
        var effective = new ArrayList<JsonObject>();
        shared.stream().filter(p -> own.stream().noneMatch(o -> sameParameter(o, p))).forEach(effective::add);
        effective.addAll(own);
        var plain = own.stream().filter(p -> !isBodyOrForm(p)).map(this::parameter).toList();
        if (!plain.isEmpty()) {
            out.put("parameters", new JsonArray(plain));
        }
        var body = effective.stream().filter(p -> resolved(p).string("in").filter("body"::equals).isPresent()).findFirst();
        var form = effective.stream().map(this::resolved).filter(p -> p.string("in").filter("formData"::equals).isPresent()).toList();
        // Referenced bodies are inlined: they take the operation's media types.
        body.ifPresent(parameter -> out.put("requestBody", requestBody(resolved(parameter), operationConsumes)));
        if (body.isEmpty() && !form.isEmpty()) {
            out.put("requestBody", formBody(form, operationConsumes));
        }
        var responses = new LinkedHashMap<String, JsonValue>();
        operation.object("responses").orElse(JsonObject.EMPTY).members().forEach((status, response) -> responses.put(status,
            status.startsWith("x-") ? response : response(response.asObject().orElse(JsonObject.EMPTY), operationProduces)));
        out.put("responses", new JsonObject(responses));
        extensions(operation, out);
        return new JsonObject(out);
    }

    private static List<JsonObject> parameters(JsonObject owner) {
        return owner.array("parameters").map(JsonArray::values).orElse(List.of()).stream().flatMap(value -> value.asObject().stream()).toList();
    }

    private boolean sameParameter(JsonObject a, JsonObject b) {
        var left = resolved(a);
        var right = resolved(b);
        return left.string("name").equals(right.string("name")) && left.string("in").equals(right.string("in"));
    }

    /** A parameter with a local reference replaced by its definition. */
    private JsonObject resolved(JsonObject parameter) {
        return parameter.string("$ref").flatMap(ref -> localName(ref, "parameters")).flatMap(name -> Optional.ofNullable(globalParameters.get(name)))
            .orElse(parameter);
    }

    private boolean isBodyOrForm(JsonObject parameter) {
        return resolved(parameter).string("in").filter(in -> in.equals("body") || in.equals("formData")).isPresent();
    }

    // ---------------------------------------------------------------------------------------------
    // Parameters, bodies and responses

    /** A non-body parameter (or a reference to one) as an OpenAPI 3.0 parameter. */
    private JsonValue parameter(JsonObject parameter) {
        var ref = parameter.string("$ref");
        if (ref.isPresent()) {
            return JsonObject.builder().put("$ref", rewrite(ref.get())).build();
        }
        var out = new LinkedHashMap<String, JsonValue>();
        for (var key : List.of("name", "in", "description", "required", "allowEmptyValue")) {
            parameter.get(key).ifPresent(value -> out.put(key, value));
        }
        out.put("schema", valueSchema(parameter));
        if (parameter.string("type").filter("array"::equals).isPresent()) {
            style(parameter.string("collectionFormat").orElse("csv"), parameter.string("in").orElse("query"), out);
        }
        parameter.get("x-example").ifPresent(example -> out.put("example", example));
        extensions(parameter, out);
        return new JsonObject(out);
    }

    /** {@code collectionFormat} as {@code style} and {@code explode} (only {@code csv} exists outside the query). */
    private void style(String collectionFormat, String in, Map<String, JsonValue> out) {
        if (!in.equals("query")) {
            if (!collectionFormat.equals("csv")) {
                warnings.add("collectionFormat '" + collectionFormat + "' of a " + in + " parameter has no OpenAPI 3 equivalent; using csv");
            }
            return;
        }
        var style = switch (collectionFormat) {
            case "ssv" -> "spaceDelimited";
            case "pipes" -> "pipeDelimited";
            case "multi" -> "form";
            default -> {
                if (!collectionFormat.equals("csv")) {
                    warnings.add("collectionFormat '" + collectionFormat + "' has no OpenAPI 3 equivalent; using csv");
                }
                yield "form";
            }
        };
        out.put("style", JsonString.of(style));
        out.put("explode", JsonBoolean.of(collectionFormat.equals("multi")));
    }

    /** The schema of a parameter, header or items object described with schema keywords of its own. */
    private JsonObject valueSchema(JsonObject value) {
        if (value.string("type").filter("file"::equals).isPresent()) {
            return JsonObject.builder().put("type", "string").put("format", "binary").build();
        }
        var out = new LinkedHashMap<String, JsonValue>();
        for (var keyword : VALUE_KEYWORDS) {
            value.get(keyword).ifPresent(v -> out.put(keyword, keyword.equals("items") ? valueSchema(v.asObject().orElse(JsonObject.EMPTY)) : v));
        }
        return new JsonObject(out);
    }

    private JsonValue requestBody(JsonObject parameter, List<String> mediaTypes) {
        var out = new LinkedHashMap<String, JsonValue>();
        parameter.get("description").ifPresent(description -> out.put("description", description));
        out.put("required", JsonBoolean.of(parameter.bool("required").orElse(false)));
        var schema = schema(parameter.get("schema").orElse(JsonObject.EMPTY));
        out.put("content", content(mediaTypes, schema, JsonObject.EMPTY));
        extensions(parameter, out);
        return new JsonObject(out);
    }

    /** {@code formData} parameters as an object schema sent multipart (with files or when consumed) or form-urlencoded. */
    private JsonObject formBody(List<JsonObject> parameters, List<String> mediaTypes) {
        var properties = new LinkedHashMap<String, JsonValue>();
        var required = new ArrayList<JsonValue>();
        var encoding = new LinkedHashMap<String, JsonValue>();
        for (var parameter : parameters) {
            var name = parameter.string("name").orElse("");
            var schema = new LinkedHashMap<>(valueSchema(parameter).members());
            parameter.get("description").ifPresent(description -> schema.put("description", description));
            properties.put(name, new JsonObject(schema));
            if (parameter.bool("required").orElse(false)) {
                required.add(JsonString.of(name));
            }
            if (parameter.string("collectionFormat").filter(format -> !format.equals("multi")).isPresent()) {
                var style = new LinkedHashMap<String, JsonValue>();
                style(parameter.string("collectionFormat").orElseThrow(), "query", style);
                encoding.put(name, new JsonObject(style));
            }
        }
        var multipart = mediaTypes.contains("multipart/form-data")
            || parameters.stream().anyMatch(p -> p.string("type").filter("file"::equals).isPresent());
        var schema = new LinkedHashMap<String, JsonValue>();
        schema.put("type", JsonString.of("object"));
        schema.put("properties", new JsonObject(properties));
        if (!required.isEmpty()) {
            schema.put("required", new JsonArray(required));
        }
        var mediaType = new LinkedHashMap<String, JsonValue>();
        mediaType.put("schema", new JsonObject(schema));
        if (!encoding.isEmpty() && !multipart) {
            mediaType.put("encoding", new JsonObject(encoding));
        }
        var type = multipart ? "multipart/form-data" : "application/x-www-form-urlencoded";
        return JsonObject.builder()
            .put("required", JsonBoolean.of(!required.isEmpty()))
            .put("content", JsonObject.builder().put(type, new JsonObject(mediaType)).build())
            .build();
    }

    private JsonValue response(JsonObject response, List<String> mediaTypes) {
        var ref = response.string("$ref");
        if (ref.isPresent()) {
            return JsonObject.builder().put("$ref", rewrite(ref.get())).build();
        }
        var out = new LinkedHashMap<String, JsonValue>();
        out.put("description", response.get("description").orElse(JsonString.of("")));
        response.object("headers").ifPresent(headers -> {
            var converted = new LinkedHashMap<String, JsonValue>();
            headers.members().forEach((name, header) -> {
                var definition = header.asObject().orElse(JsonObject.EMPTY);
                var h = new LinkedHashMap<String, JsonValue>();
                definition.get("description").ifPresent(description -> h.put("description", description));
                h.put("schema", valueSchema(definition));
                converted.put(name, new JsonObject(h));
            });
            out.put("headers", new JsonObject(converted));
        });
        response.get("schema").ifPresent(schema -> out.put("content", content(mediaTypes, schema(schema),
            response.object("examples").orElse(JsonObject.EMPTY))));
        extensions(response, out);
        return new JsonObject(out);
    }

    private static JsonObject content(List<String> mediaTypes, JsonValue schema, JsonObject examples) {
        var content = new LinkedHashMap<String, JsonValue>();
        for (var mediaType : mediaTypes) {
            var entry = new LinkedHashMap<String, JsonValue>();
            entry.put("schema", schema);
            examples.get(mediaType).ifPresent(example -> entry.put("example", example));
            content.put(mediaType, new JsonObject(entry));
        }
        return new JsonObject(content);
    }

    // ---------------------------------------------------------------------------------------------
    // Components

    private JsonObject components() {
        var out = new LinkedHashMap<String, JsonValue>();
        source.object("definitions").ifPresent(definitions -> out.put("schemas", map(definitions, this::schema)));
        var plain = new LinkedHashMap<String, JsonValue>();
        var bodies = new LinkedHashMap<String, JsonValue>();
        globalParameters.forEach((name, parameter) -> {
            var in = parameter.string("in").orElse("");
            if (in.equals("body")) {
                bodies.put(name, requestBody(parameter, consumes));
            } else if (!in.equals("formData")) {
                plain.put(name, parameter(parameter));
            }
        });
        if (!plain.isEmpty()) {
            out.put("parameters", new JsonObject(plain));
        }
        if (!bodies.isEmpty()) {
            out.put("requestBodies", new JsonObject(bodies));
        }
        source.object("responses").ifPresent(responses -> out.put("responses", map(responses, r -> response(r.asObject().orElse(JsonObject.EMPTY),
            produces))));
        source.object("securityDefinitions").ifPresent(schemes -> out.put("securitySchemes", map(schemes, s -> securityScheme(s.asObject()
            .orElse(JsonObject.EMPTY)))));
        return new JsonObject(out);
    }

    private static JsonObject map(JsonObject source, java.util.function.Function<JsonValue, JsonValue> convert) {
        var out = new LinkedHashMap<String, JsonValue>();
        source.members().forEach((name, value) -> out.put(name, convert.apply(value)));
        return new JsonObject(out);
    }

    private JsonValue securityScheme(JsonObject scheme) {
        var out = new LinkedHashMap<String, JsonValue>();
        var type = scheme.string("type").orElse("");
        switch (type) {
            case "basic" -> {
                out.put("type", JsonString.of("http"));
                out.put("scheme", JsonString.of("basic"));
            }
            case "oauth2" -> {
                out.put("type", JsonString.of("oauth2"));
                var flow = new LinkedHashMap<String, JsonValue>();
                for (var key : List.of("authorizationUrl", "tokenUrl", "scopes")) {
                    scheme.get(key).ifPresent(value -> flow.put(key, value));
                }
                flow.putIfAbsent("scopes", JsonObject.EMPTY);
                var name = switch (scheme.string("flow").orElse("")) {
                    case "implicit" -> "implicit";
                    case "password" -> "password";
                    case "application" -> "clientCredentials";
                    default -> "authorizationCode";
                };
                out.put("flows", JsonObject.builder().put(name, new JsonObject(flow)).build());
            }
            default -> {
                out.put("type", JsonString.of(type));
                scheme.get("name").ifPresent(value -> out.put("name", value));
                scheme.get("in").ifPresent(value -> out.put("in", value));
            }
        }
        scheme.get("description").ifPresent(description -> out.put("description", description));
        extensions(scheme, out);
        return new JsonObject(out);
    }

    // ---------------------------------------------------------------------------------------------
    // Schemas and references

    /** A schema in the 3.0 dialect: local references rewritten, discriminator objects, {@code nullable} and binary files. */
    private JsonValue schema(JsonValue value) {
        return switch (value) {
            case JsonObject object -> {
                var out = new LinkedHashMap<String, JsonValue>();
                object.members().forEach((key, member) -> {
                    switch (key) {
                        case "$ref" -> out.put(key, member.asString().<JsonValue>map(ref -> JsonString.of(rewrite(ref))).orElse(member));
                        case "discriminator" -> out.put(key, member.asString().<JsonValue>map(property -> JsonObject.builder().put("propertyName", property)
                            .build()).orElse(member));
                        case "x-nullable" -> out.put("nullable", member);
                        default -> out.put(key, DATA_KEYWORDS.contains(key) || key.startsWith("x-") ? member : schema(member));
                    }
                });
                if (object.string("type").filter("file"::equals).isPresent()) {
                    out.put("type", JsonString.of("string"));
                    out.put("format", JsonString.of("binary"));
                }
                yield new JsonObject(out);
            }
            case JsonArray array -> new JsonArray(array.values().stream().map(this::schema).toList());
            default -> value;
        };
    }

    /** Local references to moved objects follow them into {@code components}. */
    private static String rewrite(String ref) {
        return ref.replace("#/definitions/", "#/components/schemas/").replace("#/parameters/", "#/components/parameters/")
            .replace("#/responses/", "#/components/responses/");
    }

    private static Optional<String> localName(String ref, String section) {
        var prefix = "#/" + section + "/";
        return ref.startsWith(prefix) ? Optional.of(ref.substring(prefix.length())) : Optional.empty();
    }

    private static Optional<List<String>> strings(JsonObject owner, String key) {
        return owner.array(key).map(array -> array.values().stream().flatMap(v -> v.asString().stream()).toList());
    }

    private static void extensions(JsonObject from, Map<String, JsonValue> to) {
        from.members().forEach((key, value) -> {
            if (key.startsWith("x-") && !key.equals("x-example") && !key.equals("x-nullable")) {
                to.put(key, value);
            }
        });
    }
}
