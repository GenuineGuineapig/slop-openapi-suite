package dev.openapifn.http;

import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonValue;
import dev.openapifn.json.JsonWriter;
import dev.openapifn.schema.Schema;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
            var boundary = type.parameter("boundary").orElseThrow(() -> new IllegalArgumentException("multipart content type has no boundary"));
            return encode(boundary, type.subtype().equals("form-data") ? formParts(value) : positionalParts(value));
        }
        var essence = type.essence();
        var items = value instanceof JsonArray array ? array.values() : List.of(value);
        var text = switch (essence) {
            case "application/jsonl", "application/x-ndjson", "application/x-jsonlines" ->
                items.stream().map(JsonWriter::compact).map(line -> line + "\n").collect(Collectors.joining());
            case "application/json-seq" -> items.stream().map(item -> "\u001e" + JsonWriter.compact(item) + "\n").collect(Collectors.joining());
            case "text/event-stream" -> items.stream().map(BodyWriter::event).collect(Collectors.joining());
            case "application/x-www-form-urlencoded" -> form(value);
            default -> type.isJson() ? JsonWriter.compact(value)
                : type.isXml() ? xml.write(value, schema, "root")
                : ParameterSerializer.text(value);
        };
        return text.getBytes(StandardCharsets.UTF_8);
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
        var pairs = new ArrayList<String>();
        value.asObject().orElse(JsonObject.EMPTY).members().forEach((name, member) -> {
            var values = member instanceof JsonArray array ? array.values() : List.of(member);
            values.forEach(item -> pairs.add(UrlCodec.encode(name, "") + "=" + UrlCodec.encode(
                item instanceof JsonObject || item instanceof JsonArray ? JsonWriter.compact(item) : ParameterSerializer.text(item), "")));
        });
        return String.join("&", pairs);
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
        for (var part : parts) {
            var head = new StringBuilder("--").append(boundary).append("\r\n");
            part.headers().values().forEach((name, values) -> values.forEach(v -> head.append(header(name)).append(": ").append(v).append("\r\n")));
            out.writeBytes(head.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));
            out.writeBytes(part.body());
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
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
