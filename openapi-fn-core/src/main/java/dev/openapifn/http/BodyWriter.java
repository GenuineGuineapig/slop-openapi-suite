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
     * types; objects become forms for {@code application/x-www-form-urlencoded}; XML is written
     * guided by {@code schema}; primitives become text for other types.
     */
    public byte[] write(JsonValue value, ContentType type, Optional<Schema> schema) {
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
        var boundary = "openapi-fn-" + UUID.randomUUID();
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            var head = new StringBuilder("--").append(boundary).append("\r\n");
            part.headers().values().forEach((name, values) -> values.forEach(v -> head.append(header(name)).append(": ").append(v).append("\r\n")));
            out.writeBytes(head.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));
            out.writeBytes(part.body());
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new MultipartBody(out.toByteArray(), "multipart/form-data; boundary=" + boundary);
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
