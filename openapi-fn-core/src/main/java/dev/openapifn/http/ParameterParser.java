package dev.openapifn.http;

import dev.openapifn.fn.Result;
import dev.openapifn.http.SchemaShapes.Shape;
import dev.openapifn.json.Json;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.model.Parameter;
import dev.openapifn.model.ParameterStyle;
import dev.openapifn.schema.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Deserializes parameter values according to their {@code style}, {@code explode} and schema
 * (or {@code content}), producing JSON values ready for schema validation or data binding.
 * Serialized values are split on their delimiters before percent-decoding, so encoded
 * delimiters inside values are preserved.
 */
public final class ParameterParser {

    /**
     * The raw parameter sources of a request.
     *
     * @param pathValues raw (percent-encoded) values of path template expressions
     * @param query query pairs with decoded names and raw values
     * @param rawQuery the raw query string
     * @param headers request headers
     * @param cookies raw cookie pairs
     */
    public record Sources(Map<String, String> pathValues, List<Map.Entry<String, String>> query, String rawQuery, Headers headers,
                          List<Map.Entry<String, String>> cookies) {

        public Sources {
            pathValues = Map.copyOf(pathValues);
            query = List.copyOf(query);
            cookies = List.copyOf(cookies);
        }

        /** Sources taken from a request whose path template matched with {@code pathValues}. */
        public static Sources of(HttpRequest request, Map<String, String> pathValues) {
            return new Sources(pathValues, pairs(request.query()), request.query(), request.headers(), UrlCodec.parseCookies(request.headers().all("Cookie")));
        }

        /** Sources holding only a query string, such as an {@code application/x-www-form-urlencoded} body. */
        public static Sources ofQuery(String query) {
            return new Sources(Map.of(), pairs(query), query, Headers.EMPTY, List.of());
        }

        private static List<Map.Entry<String, String>> pairs(String query) {
            var pairs = new ArrayList<Map.Entry<String, String>>();
            for (var part : query.split("&")) {
                if (!part.isEmpty()) {
                    var eq = part.indexOf('=');
                    pairs.add(Map.entry(UrlCodec.decode(eq < 0 ? part : part.substring(0, eq), true), eq < 0 ? "" : part.substring(eq + 1)));
                }
            }
            return pairs;
        }
    }

    private static final Pattern DEEP_OBJECT_KEY = Pattern.compile("(.+?)\\[([^\\]]*)]");

    private final SchemaShapes shapes;

    public ParameterParser(SchemaShapes shapes) {
        this.shapes = shapes;
    }

    /**
     * Deserializes {@code parameter} from {@code sources}.
     *
     * @param otherQueryNames names of the other query parameters of the operation, which an
     *     exploded form object with additional properties must not claim
     * @return the value, empty when the parameter is absent, or an error for malformed input
     */
    public Result<Optional<JsonValue>, String> parse(Parameter parameter, Sources sources, Set<String> otherQueryNames) {
        var schema = parameter.effectiveSchema();
        var mediaType = parameter.content().keySet().stream().findFirst();
        if (mediaType.isPresent()) {
            var raw = contentValue(parameter, sources, mediaType.get());
            return raw.isEmpty() ? Result.ok(Optional.empty()) : fromContent(raw.get(), mediaType.get(), schema).map(Optional::of);
        }
        var shape = schema.map(shapes::shape).orElse(Shape.PRIMITIVE);
        return switch (parameter.in()) {
            case PATH -> {
                var raw = sources.pathValues().get(parameter.name());
                yield raw == null ? Result.ok(Optional.empty()) : path(parameter, raw, shape, schema).map(Optional::of);
            }
            case QUERY -> query(parameter, sources.query(), shape, schema, otherQueryNames);
            case HEADER -> Result.ok(joinedHeader(sources.headers(), parameter.name())
                .map(raw -> simple(raw, shape, schema, parameter.explode(), String::trim, CollectionFormat.delimiter(parameter.extensions()).orElse(","))));
            case COOKIE -> Result.ok(cookie(parameter, sources.cookies(), shape, schema));
            case QUERYSTRING -> Result.ok(Optional.of(sources.rawQuery()).filter(q -> !q.isEmpty()).map(q -> formObject(q, schema)));
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Content-based parameters: the value is a serialized document such as JSON

    private Optional<String> contentValue(Parameter parameter, Sources sources, String mediaType) {
        return switch (parameter.in()) {
            case PATH -> Optional.ofNullable(sources.pathValues().get(parameter.name())).map(raw -> UrlCodec.decode(raw, false));
            case QUERY -> values(sources.query(), parameter.name()).stream().findFirst().map(raw -> UrlCodec.decode(raw, true));
            case HEADER -> joinedHeader(sources.headers(), parameter.name());
            case COOKIE -> values(sources.cookies(), parameter.name()).stream().findFirst().map(raw -> UrlCodec.decode(raw, false));
            case QUERYSTRING -> Optional.of(sources.rawQuery()).filter(query -> !query.isEmpty())
                .map(query -> isForm(mediaType) ? query : UrlCodec.decode(query, true));
        };
    }

    private static boolean isForm(String mediaType) {
        return ContentType.parse(mediaType).filter(t -> t.essence().equals("application/x-www-form-urlencoded")).isPresent();
    }

    private Result<JsonValue, String> fromContent(String text, String mediaType, Optional<Schema> schema) {
        var type = ContentType.parse(mediaType);
        if (type.filter(ContentType::isJson).isPresent()) {
            return Json.parse(text).mapError(error -> "must be valid JSON: " + error.message());
        }
        if (isForm(mediaType)) {
            return Result.ok(formObject(text, schema));
        }
        return Result.ok(JsonString.of(text));
    }

    /** An {@code application/x-www-form-urlencoded} text as an object of coerced values (repeated names become arrays). */
    public JsonObject formObject(String text, Optional<Schema> schema) {
        var grouped = new LinkedHashMap<String, List<String>>();
        UrlCodec.parseQuery(text).forEach(pair -> grouped.computeIfAbsent(pair.getKey(), key -> new ArrayList<>()).add(pair.getValue()));
        var members = new LinkedHashMap<String, JsonValue>();
        grouped.forEach((name, values) -> {
            var property = schema.flatMap(s -> shapes.property(s, name));
            var isArray = property.map(shapes::shape).filter(shape -> shape == Shape.ARRAY).isPresent();
            members.put(name, isArray
                ? new JsonArray(values.stream().map(value -> shapes.coerce(value, property.flatMap(shapes::items))).toList())
                : shapes.coerce(values.getFirst(), property));
        });
        return new JsonObject(members);
    }

    // ---------------------------------------------------------------------------------------------
    // Path: simple, label and matrix

    private Result<JsonValue, String> path(Parameter parameter, String raw, Shape shape, Optional<Schema> schema) {
        UnaryOperator<String> decode = value -> UrlCodec.decode(value, false);
        return switch (parameter.style()) {
            case LABEL -> {
                if (!raw.startsWith(".")) {
                    yield Result.err("label style value must start with '.'");
                }
                var body = raw.substring(1);
                var separator = parameter.explode() ? "." : ",";
                yield Result.ok(switch (shape) {
                    case PRIMITIVE -> primitive(decode.apply(body), schema);
                    case ARRAY -> array(split(body, separator), decode, schema);
                    case OBJECT -> parameter.explode() ? assignments(split(body, "."), decode, schema) : alternating(split(body, ","), decode, schema);
                });
            }
            case MATRIX -> matrix(parameter, raw, shape, schema, decode);
            default -> Result.ok(simple(raw, shape, schema, parameter.explode(), decode, CollectionFormat.delimiter(parameter.extensions()).orElse(",")));
        };
    }

    private Result<JsonValue, String> matrix(Parameter parameter, String raw, Shape shape, Optional<Schema> schema, UnaryOperator<String> decode) {
        if (!raw.startsWith(";")) {
            return Result.err("matrix style value must start with ';'");
        }
        var parts = split(raw.substring(1), ";");
        if (parts.isEmpty()) {
            return Result.err("matrix style value must name parameter '" + parameter.name() + "'");
        }
        var name = parameter.name();
        if (shape == Shape.OBJECT && parameter.explode()) {
            return Result.ok(assignments(parts, decode, schema));
        }
        var values = new ArrayList<String>();
        for (var part : parts) {
            var eq = part.indexOf('=');
            var key = eq < 0 ? part : part.substring(0, eq);
            if (!key.equals(name)) {
                return Result.err("matrix style value must name parameter '" + name + "'");
            }
            values.add(eq < 0 ? "" : part.substring(eq + 1));
        }
        return Result.ok(switch (shape) {
            case PRIMITIVE -> primitive(decode.apply(values.getFirst()), schema);
            case ARRAY -> parameter.explode() ? array(values, decode, schema) : array(split(values.getFirst(), ","), decode, schema);
            case OBJECT -> alternating(split(values.getFirst(), ","), decode, schema);
        });
    }

    /** The {@code simple} style shared by path and header parameters; array items are separated by {@code delimiter}. */
    private JsonValue simple(String raw, Shape shape, Optional<Schema> schema, boolean explode, UnaryOperator<String> decode, String delimiter) {
        return switch (shape) {
            case PRIMITIVE -> primitive(decode.apply(raw), schema);
            case ARRAY -> array(splitDecoded(raw, delimiter), decode, schema);
            case OBJECT -> explode ? assignments(split(raw, ","), decode, schema) : alternating(split(raw, ","), decode, schema);
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Query: form, spaceDelimited, pipeDelimited and deepObject

    private Result<Optional<JsonValue>, String> query(Parameter parameter, List<Map.Entry<String, String>> query, Shape shape,
                                                      Optional<Schema> schema, Set<String> otherNames) {
        UnaryOperator<String> decode = value -> UrlCodec.decode(value, true);
        var name = parameter.name();
        if (parameter.style() == ParameterStyle.DEEP_OBJECT) {
            var members = new LinkedHashMap<String, JsonValue>();
            for (var pair : query) {
                var matcher = DEEP_OBJECT_KEY.matcher(pair.getKey());
                if (matcher.matches() && matcher.group(1).equals(name)) {
                    var property = matcher.group(2);
                    members.put(property, shapes.coerce(decode.apply(pair.getValue()), schema.flatMap(s -> shapes.property(s, property))));
                }
            }
            return Result.ok(Optional.of(new JsonObject(members)).filter(object -> !object.isEmpty()).map(JsonValue.class::cast));
        }
        if (shape == Shape.OBJECT && parameter.explode()) {
            return Result.ok(explodedFormObject(query, schema.orElseThrow(), otherNames, decode));
        }
        var values = values(query, name);
        if (values.isEmpty()) {
            return Result.ok(Optional.empty());
        }
        var separator = CollectionFormat.delimiter(parameter.extensions()).orElseGet(() -> switch (parameter.style()) {
            case SPACE_DELIMITED -> " ";
            case PIPE_DELIMITED -> "|";
            default -> ",";
        });
        return Result.ok(Optional.of(switch (shape) {
            case PRIMITIVE -> primitive(decode.apply(values.getFirst()), schema);
            case ARRAY -> parameter.explode() ? array(values, decode, schema) : array(splitDecoded(values.getFirst(), separator), decode, schema);
            case OBJECT -> alternating(splitDecoded(values.getFirst(), separator), decode, schema);
        }));
    }

    /** Splits on {@code separator}, also accepting the separator percent-encoded (and spaces as {@code +}); commas are only literal. */
    private static List<String> splitDecoded(String raw, String separator) {
        if (separator.equals(",")) {
            return split(raw, ",");
        }
        var encoded = UrlCodec.encode(separator, "");
        var normalized = raw.replace(encoded, separator).replace(encoded.toLowerCase(java.util.Locale.ROOT), separator);
        return split(separator.equals(" ") ? normalized.replace("+", " ") : normalized, separator);
    }

    private Optional<JsonValue> explodedFormObject(List<Map.Entry<String, String>> query, Schema schema, Set<String> otherNames,
                                                   UnaryOperator<String> decode) {
        var declared = shapes.propertyNames(schema);
        var additional = shapes.closure(schema).stream().anyMatch(s -> s.subschema("additionalProperties").filter(a -> !a.rejectsEverything()).isPresent());
        var members = new LinkedHashMap<String, JsonValue>();
        for (var pair : query) {
            var key = pair.getKey();
            if (declared.contains(key) || (additional && !otherNames.contains(key))) {
                members.putIfAbsent(key, shapes.coerce(decode.apply(pair.getValue()), shapes.property(schema, key)));
            }
        }
        return members.isEmpty() ? Optional.empty() : Optional.of(new JsonObject(members));
    }

    // ---------------------------------------------------------------------------------------------
    // Headers and cookies

    private static Optional<String> joinedHeader(Headers headers, String name) {
        var values = headers.all(name);
        return values.isEmpty() ? Optional.empty() : Optional.of(String.join(",", values));
    }

    private Optional<JsonValue> cookie(Parameter parameter, List<Map.Entry<String, String>> cookies, Shape shape, Optional<Schema> schema) {
        UnaryOperator<String> decode = parameter.style() == ParameterStyle.COOKIE ? UnaryOperator.identity() : value -> UrlCodec.decode(value, false);
        if (shape == Shape.OBJECT && parameter.explode()) {
            var members = new LinkedHashMap<String, JsonValue>();
            var declared = shapes.propertyNames(schema.orElseThrow());
            cookies.stream().filter(c -> declared.contains(c.getKey()))
                .forEach(c -> members.putIfAbsent(c.getKey(), shapes.coerce(decode.apply(c.getValue()), shapes.property(schema.get(), c.getKey()))));
            return members.isEmpty() ? Optional.empty() : Optional.of(new JsonObject(members));
        }
        var values = values(cookies, parameter.name());
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(switch (shape) {
            case PRIMITIVE -> primitive(decode.apply(values.getFirst()), schema);
            case ARRAY -> parameter.explode() ? array(values, decode, schema) : array(split(values.getFirst(), ","), decode, schema);
            case OBJECT -> alternating(split(values.getFirst(), ","), decode, schema);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Building values

    private JsonValue primitive(String decoded, Optional<Schema> schema) {
        return shapes.coerce(decoded, schema);
    }

    private JsonValue array(List<String> raw, UnaryOperator<String> decode, Optional<Schema> schema) {
        var items = schema.flatMap(shapes::items);
        return new JsonArray(raw.stream().map(value -> shapes.coerce(decode.apply(value), items)).toList());
    }

    /** {@code k1,v1,k2,v2}: alternating names and values (an odd trailing name gets an empty value). */
    private JsonValue alternating(List<String> raw, UnaryOperator<String> decode, Optional<Schema> schema) {
        var members = new LinkedHashMap<String, JsonValue>();
        for (int i = 0; i < raw.size(); i += 2) {
            var key = decode.apply(raw.get(i));
            var value = i + 1 < raw.size() ? decode.apply(raw.get(i + 1)) : "";
            members.put(key, shapes.coerce(value, schema.flatMap(s -> shapes.property(s, key))));
        }
        return new JsonObject(members);
    }

    /** {@code k1=v1,k2=v2}: assignments (a missing {@code =} gives an empty value). */
    private JsonValue assignments(List<String> raw, UnaryOperator<String> decode, Optional<Schema> schema) {
        var members = new LinkedHashMap<String, JsonValue>();
        for (var part : raw) {
            var eq = part.indexOf('=');
            var key = decode.apply(eq < 0 ? part : part.substring(0, eq));
            var value = eq < 0 ? "" : decode.apply(part.substring(eq + 1));
            members.put(key, shapes.coerce(value, schema.flatMap(s -> shapes.property(s, key))));
        }
        return new JsonObject(members);
    }

    private static List<String> values(List<Map.Entry<String, String>> pairs, String name) {
        return pairs.stream().filter(pair -> pair.getKey().equals(name)).map(Map.Entry::getValue).toList();
    }

    private static List<String> split(String raw, String separator) {
        return raw.isEmpty() ? List.of() : List.of(raw.split(Pattern.quote(separator), -1));
    }
}
