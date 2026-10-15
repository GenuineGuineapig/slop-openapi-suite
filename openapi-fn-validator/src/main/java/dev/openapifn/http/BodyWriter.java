package dev.openapifn.http;

import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonValue;
import dev.openapifn.json.JsonWriter;
import dev.openapifn.schema.Schema;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Serializes JSON values to message bodies of a given media type; the inverse of
 * {@link BodyParser}. Binary multipart parts are supplied as {@link Multipart.Part}s.
 */
public final class BodyWriter {

    private final XmlWriter xml;

    public BodyWriter(SchemaShapes shapes) {
        this.xml = new XmlWriter(shapes);
    }

    /**
     * Serializes {@code value} as {@code type}. Arrays become sequences for the sequential media
     * types; objects become forms for {@code application/x-www-form-urlencoded} and named parts for
     * {@code multipart/form-data}; arrays become positional parts for other multipart types (which
     * need a {@code boundary} parameter); XML is written guided by {@code schema}; primitives
     * become text for other types.
     */
    public byte[] write(JsonValue value, ContentType type, Optional<Schema> schema) {
        if (type.type().equals("multipart")) {
            var boundary = boundary(type);
            return encode(boundary, type.subtype().equals("form-data") ? formParts(value) : positionalParts(value));
        }
        if (ItemFramer.isSequential(type)) {
            var out = new ByteArrayOutputStream();
            (value instanceof JsonArray array ? array.values() : List.of(value)).forEach(item -> out.writeBytes(item(item, type)));
            return out.toByteArray();
        }
        var text = type.essence().equals("application/x-www-form-urlencoded") ? form(value)
            : type.isJson() ? JsonWriter.compact(value)
            : type.isXml() ? xml.write(value, schema, "root")
            : ParameterSerializer.text(value);
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * One item of a body of a sequential media type ({@link ItemFramer#isSequential}), so that
     * items can be written as they are produced: a line, a JSON text sequence record, an event or
     * a positional part (which needs the {@code boundary} parameter). {@link #end} follows the last item.
     */
    public static byte[] item(JsonValue item, ContentType type) {
        if (type.type().equals("multipart")) {
            return part(boundary(type), part(Optional.empty(), item));
        }
        var text = switch (type.essence()) {
            case "application/json-seq" -> "\u001e" + JsonWriter.compact(item) + "\n";
            case "text/event-stream" -> event(item);
            default -> JsonWriter.compact(item) + "\n";
        };
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** What follows the last item of a body of a sequential media type: the closing delimiter of multipart bodies. */
    public static byte[] end(ContentType type) {
        return type.type().equals("multipart") ? ("--" + boundary(type) + "--\r\n").getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    private static String boundary(ContentType type) {
        return type.parameter("boundary").orElseThrow(() -> new IllegalArgumentException("multipart content type has no boundary"));
    }

    private static String event(JsonValue value) {
        var object = value.asObject().orElse(JsonObject.EMPTY);
        var out = new StringBuilder();
        object.members().forEach((field, fieldValue) -> {
            if (!field.equals("data")) {
                out.append(field).append(": ").append(ParameterSerializer.text(fieldValue)).append('\n');
            }
        });
        object.get("data").ifPresent(data -> ParameterSerializer.text(data).lines().forEach(line -> out.append("data: ").append(line).append('\n')));
        return out.append('\n').toString();
    }

    /** An object as {@code application/x-www-form-urlencoded}: arrays repeat the name, nested objects are JSON. */
    public static String form(JsonValue value) {
        return form(value, Map.of());
    }

    /**
     * An object as {@code application/x-www-form-urlencoded}, serializing the properties that have a
     * spec like query parameters (see {@link #formSpecs}); others as in {@link #form(JsonValue)}.
     */
    public static String form(JsonValue value, Map<String, ParameterSerializer.Spec> specs) {
        var pairs = new ArrayList<String>();
        value.asObject().orElse(JsonObject.EMPTY).members().forEach((name, member) -> {
            var spec = specs.get(name);
            if (spec != null) {
                ParameterSerializer.query(spec, member).forEach(pair -> pairs.add(pair.getKey() + "=" + pair.getValue()));
                return;
            }
            var values = member instanceof JsonArray array ? array.values() : List.of(member);
            values.forEach(item -> pairs.add(UrlCodec.encodeForm(name) + "=" + UrlCodec.encodeForm(
                item instanceof JsonObject || item instanceof JsonArray ? JsonWriter.compact(item) : ParameterSerializer.text(item))));
        });
        return String.join("&", pairs);
    }

    /** The specs of the form properties whose encodings say how they are serialized (see {@link dev.openapifn.model.Encoding#serializes()}). */
    public static Map<String, ParameterSerializer.Spec> formSpecs(Map<String, dev.openapifn.model.Encoding> encodings) {
        var specs = new LinkedHashMap<String, ParameterSerializer.Spec>();
        encodings.forEach((name, encoding) -> {
            if (encoding.serializes()) {
                specs.put(name, ParameterSerializer.Spec.of(name, encoding));
            }
        });
        return specs;
    }

    /**
     * A {@code multipart/form-data} body.
     *
     * @param body the encoded parts
     * @param contentType the content type including the boundary
     */
    public record MultipartBody(byte[] body, String contentType) {
    }

    /** Encodes parts as {@code multipart/form-data} with a random boundary. */
    public static MultipartBody multipart(List<Multipart.Part> parts) {
        return multipart("form-data", parts);
    }

    /** Encodes parts as {@code multipart/<subtype>} with a random boundary. */
    public static MultipartBody multipart(String subtype, List<Multipart.Part> parts) {
        var boundary = boundary();
        return new MultipartBody(encode(boundary, parts), "multipart/" + subtype + "; boundary=" + boundary);
    }

    /** A random multipart boundary. */
    public static String boundary() {
        return "openapi-fn-" + UUID.randomUUID();
    }

    private static byte[] encode(String boundary, List<Multipart.Part> parts) {
        var out = new ByteArrayOutputStream();
        parts.forEach(part -> out.writeBytes(part(boundary, part)));
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** A part with its delimiter, as one item of a multipart body (the closing delimiter follows the last one). */
    public static byte[] part(String boundary, Multipart.Part part) {
        var out = new ByteArrayOutputStream();
        var head = new StringBuilder("--").append(boundary).append("\r\n");
        part.headers().values().forEach((name, values) -> values.forEach(v -> head.append(header(name)).append(": ").append(v).append("\r\n")));
        out.writeBytes(head.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));
        out.writeBytes(part.body());
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** Named parts of an object, one per array item. */
    private static List<Multipart.Part> formParts(JsonValue value) {
        var parts = new ArrayList<Multipart.Part>();
        value.asObject().orElse(JsonObject.EMPTY).members().forEach((name, member) -> {
            var values = member instanceof JsonArray array ? array.values() : List.of(member);
            values.forEach(item -> parts.add(part(Optional.of(name), item)));
        });
        return parts;
    }

    /** Positional parts of an array (or of a single value). */
    private static List<Multipart.Part> positionalParts(JsonValue value) {
        var items = value instanceof JsonArray array ? array.values() : List.of(value);
        return items.stream().map(item -> part(Optional.empty(), item)).toList();
    }

    /** A value as a part, named for {@code multipart/form-data}: objects and arrays as JSON, other values as text. */
    public static Multipart.Part part(Optional<String> name, JsonValue value) {
        var structured = value instanceof JsonObject || value instanceof JsonArray;
        var contentType = structured ? "application/json" : "text/plain";
        var content = (structured ? JsonWriter.compact(value) : ParameterSerializer.text(value)).getBytes(StandardCharsets.UTF_8);
        return name.map(n -> part(n, Optional.empty(), Optional.of(contentType), content)).orElseGet(() -> part(contentType, content));
    }

    /** An unnamed part, e.g. of {@code multipart/mixed}. */
    public static Multipart.Part part(String contentType, byte[] content) {
        return new Multipart.Part(Headers.of("Content-Type", contentType), content);
    }

    /** A part with {@code Content-Disposition: form-data} and optional file name and content type. */
    public static Multipart.Part part(String name, Optional<String> filename, Optional<String> contentType, byte[] content) {
        var disposition = "form-data; name=\"" + name + "\"" + filename.map(f -> "; filename=\"" + f + "\"").orElse("");
        var headers = Headers.of("Content-Disposition", disposition);
        return new Multipart.Part(contentType.map(type -> headers.with("Content-Type", type)).orElse(headers), content);
    }

    private static String header(String lowerCase) {
        return switch (lowerCase) {
            case "content-disposition" -> "Content-Disposition";
            case "content-type" -> "Content-Type";
            default -> lowerCase;
        };
    }
}
