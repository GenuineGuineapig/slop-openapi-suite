package dev.openapifn.http;

import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonNull;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.json.JsonWriter;
import dev.openapifn.model.Encoding;
import dev.openapifn.model.Parameter;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.ParameterStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Serializes parameter values according to {@code style} and {@code explode}; the inverse of
 * {@link ParameterParser}. The shape (primitive, array or object) is taken from the value itself,
 * so no schema is required. Used by generated clients.
 */
public final class ParameterSerializer {

    /**
     * How a parameter is serialized.
     *
     * @param name parameter name
     * @param in location
     * @param style serialization style
     * @param explode whether arrays and objects are exploded
     * @param allowReserved whether reserved characters stay unencoded in query values
     * @param delimiter the delimiter of array items that replaces the style's (a Swagger 2.0
     *     collection format, see {@link CollectionFormat})
     * @param contentType the media type of a parameter described by {@code content} rather than by
     *     a schema and a style: its value is serialized in that media type
     */
    public record Spec(String name, ParameterLocation in, ParameterStyle style, boolean explode, boolean allowReserved, Optional<String> delimiter,
                       Optional<String> contentType) {

        public Spec(String name, ParameterLocation in, ParameterStyle style, boolean explode, boolean allowReserved, Optional<String> delimiter) {
            this(name, in, style, explode, allowReserved, delimiter, Optional.empty());
        }

        public Spec(String name, ParameterLocation in, ParameterStyle style, boolean explode, boolean allowReserved) {
            this(name, in, style, explode, allowReserved, Optional.empty());
        }

        /** A spec with the default style and explode for {@code in}. */
        public static Spec of(String name, ParameterLocation in) {
            var style = in.defaultStyle();
            return new Spec(name, in, style, style.explodeByDefault(), false);
        }

        /** The spec of a described parameter. */
        public static Spec of(Parameter parameter) {
            return new Spec(parameter.name(), parameter.in(), parameter.style(), parameter.explode(), parameter.allowReserved(),
                CollectionFormat.delimiter(parameter.extensions()), parameter.content().keySet().stream().findFirst());
        }

        /** The spec of a property of an {@code application/x-www-form-urlencoded} body, serialized like a query parameter. */
        public static Spec of(String property, Encoding encoding) {
            return of(encoding.asParameter(property, Optional.empty()));
        }
    }

    private static final String QUERY_RESERVED = ":/?[]@!$'()*,;";

    private ParameterSerializer() {
    }

    /**
     * The value of a parameter described by {@code content}, in its media type: compact JSON for
     * JSON types, a form for {@code application/x-www-form-urlencoded} (a string being one already),
     * the text otherwise.
     */
    private static Optional<String> media(Spec spec, JsonValue value) {
        return spec.contentType().map(contentType -> {
            var type = ContentType.parse(contentType);
            if (type.filter(ContentType::isJson).isPresent()) {
                return JsonWriter.compact(value);
            }
            return type.filter(t -> t.essence().equals("application/x-www-form-urlencoded")).isPresent() && !(value instanceof JsonString)
                ? BodyWriter.form(value) : text(value);
        });
    }

    /**
     * The whole query string of a {@code querystring} parameter: a form (or a string, taken as the
     * query string itself) as it is, since forms are query-ready; other media types percent-encoded.
     */
    public static String querystring(Spec spec, JsonValue value) {
        var form = spec.contentType().flatMap(ContentType::parse).map(type -> type.essence().equals("application/x-www-form-urlencoded")).orElse(true);
        if (form) {
            return value instanceof JsonString string ? string.value() : BodyWriter.form(value);
        }
        return UrlCodec.encode(media(spec, value).orElseThrow(), "");
    }

    /** The percent-encoded value substituted for the path template expression. */
    public static String path(Spec spec, JsonValue value) {
        UnaryOperator<String> encode = text -> UrlCodec.encode(text, "");
        var media = media(spec, value);
        if (media.isPresent()) {
            return encode.apply(media.get());
        }
        return switch (spec.style()) {
            case LABEL -> "." + switch (value) {
                case JsonArray array -> join(items(array, encode), spec.explode() ? "." : ",");
                case JsonObject object -> spec.explode() ? join(assignments(object, encode), ".") : join(alternating(object, encode), ",");
                default -> encode.apply(text(value));
            };
            case MATRIX -> switch (value) {
                case JsonArray array -> spec.explode()
                    ? items(array, encode).stream().map(item -> matrix(spec.name(), item)).collect(Collectors.joining())
                    : matrix(spec.name(), join(items(array, encode), ","));
                case JsonObject object -> spec.explode()
                    ? assignments(object, encode).stream().map(item -> ";" + (item.endsWith("=") ? item.substring(0, item.length() - 1) : item))
                        .collect(Collectors.joining())
                    : matrix(spec.name(), join(alternating(object, encode), ","));
                default -> matrix(spec.name(), encode.apply(text(value)));
            };
            default -> simple(value, spec.explode(), encode, spec.delimiter().map(encode).orElse(","));
        };
    }

    /** An RFC 6570 path-style parameter: {@code ;name=value}, or {@code ;name} when the value is empty. */
    private static String matrix(String name, String encoded) {
        return ";" + name + (encoded.isEmpty() ? "" : "=" + encoded);
    }

    /** Encoded {@code name=value} query pairs. */
    public static List<Map.Entry<String, String>> query(Spec spec, JsonValue value) {
        UnaryOperator<String> encode = text -> UrlCodec.encode(text, spec.allowReserved() ? QUERY_RESERVED : "");
        var name = UrlCodec.encode(spec.name(), "");
        var media = media(spec, value);
        if (media.isPresent()) {
            return List.of(Map.entry(name, encode.apply(media.get())));
        }
        if (spec.style() == ParameterStyle.DEEP_OBJECT && value instanceof JsonObject object) {
            return object.members().entrySet().stream()
                .map(e -> Map.entry(UrlCodec.encode(spec.name() + "[" + e.getKey() + "]", ""), encode.apply(text(e.getValue())))).toList();
        }
        var separator = spec.delimiter().map(delimiter -> UrlCodec.encode(delimiter, "")).orElseGet(() -> switch (spec.style()) {
            case SPACE_DELIMITED -> "%20";
            case PIPE_DELIMITED -> "%7C";
            default -> ",";
        });
        return pairs(name, value, spec.explode(), separator, encode);
    }

    /** The header value ({@code simple} style, not percent-encoded). */
    public static String header(Spec spec, JsonValue value) {
        return media(spec, value).orElseGet(() -> simple(value, spec.explode(), UnaryOperator.identity(), spec.delimiter().orElse(",")));
    }

    /** Cookie {@code name=value} pairs; {@code form} style values are percent-encoded, {@code cookie} style ones are not. */
    public static List<Map.Entry<String, String>> cookie(Spec spec, JsonValue value) {
        UnaryOperator<String> encode = spec.style() == ParameterStyle.COOKIE ? UnaryOperator.identity() : text -> UrlCodec.encode(text, "");
        var media = media(spec, value);
        if (media.isPresent()) {
            return List.of(Map.entry(spec.name(), UrlCodec.encode(media.get(), "")));
        }
        return pairs(spec.name(), value, spec.explode(), ",", encode);
    }

    /** A {@code Cookie} header value for the given pairs. */
    public static String cookieHeader(List<Map.Entry<String, String>> cookies) {
        return cookies.stream().map(c -> c.getKey() + "=" + c.getValue()).collect(Collectors.joining("; "));
    }

    /** A query string for the given encoded pairs. */
    public static String queryString(List<Map.Entry<String, String>> pairs) {
        return pairs.stream().map(p -> p.getKey() + "=" + p.getValue()).collect(Collectors.joining("&"));
    }

    private static List<Map.Entry<String, String>> pairs(String name, JsonValue value, boolean explode, String separator, UnaryOperator<String> encode) {
        return switch (value) {
            case JsonArray array -> explode
                ? items(array, encode).stream().map(item -> Map.entry(name, item)).toList()
                : List.of(Map.entry(name, join(items(array, encode), separator)));
            case JsonObject object -> explode
                ? object.members().entrySet().stream().map(e -> Map.entry(encode.apply(e.getKey()), encode.apply(text(e.getValue())))).toList()
                : List.of(Map.entry(name, join(alternating(object, encode), separator)));
            default -> List.of(Map.entry(name, encode.apply(text(value))));
        };
    }

    private static String simple(JsonValue value, boolean explode, UnaryOperator<String> encode, String delimiter) {
        return switch (value) {
            case JsonArray array -> join(items(array, encode), delimiter);
            case JsonObject object -> join(explode ? assignments(object, encode) : alternating(object, encode), ",");
            default -> encode.apply(text(value));
        };
    }

    private static List<String> items(JsonArray array, UnaryOperator<String> encode) {
        return array.values().stream().map(item -> encode.apply(text(item))).toList();
    }

    private static List<String> alternating(JsonObject object, UnaryOperator<String> encode) {
        var result = new ArrayList<String>();
        object.members().forEach((key, item) -> {
            result.add(encode.apply(key));
            result.add(encode.apply(text(item)));
        });
        return result;
    }

    private static List<String> assignments(JsonObject object, UnaryOperator<String> encode) {
        return object.members().entrySet().stream().map(e -> encode.apply(e.getKey()) + "=" + encode.apply(text(e.getValue()))).toList();
    }

    private static String join(List<String> parts, String separator) {
        return String.join(separator, parts);
    }

    /** The text form of a primitive: strings as-is, numbers in plain notation, {@code null} as empty. */
    public static String text(JsonValue value) {
        return switch (value) {
            case JsonString string -> string.value();
            case JsonNumber number -> number.isIntegral() ? number.value().toBigInteger().toString() : number.value().stripTrailingZeros().toPlainString();
            case JsonBoolean bool -> Boolean.toString(bool.value());
            case JsonNull ignored -> "";
            default -> value.toJson();
        };
    }
}
