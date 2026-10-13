package dev.openapifn.http;

import dev.openapifn.fn.Result;
import dev.openapifn.http.SchemaShapes.Shape;
import dev.openapifn.json.Json;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.model.Encoding;
import dev.openapifn.model.MediaType;
import dev.openapifn.schema.Schema;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses message bodies into JSON according to their media type: JSON and {@code +json},
 * {@code application/x-www-form-urlencoded}, {@code multipart/*}, XML, text, binary, and the
 * sequential types {@code application/jsonl}, {@code application/x-ndjson},
 * {@code application/json-seq} and {@code text/event-stream}.
 */
public final class BodyParser {

    private static final Set<String> LINE_DELIMITED = Set.of("application/jsonl", "application/x-ndjson", "application/x-jsonlines");

    private final SchemaShapes shapes;
    private final ParameterParser parameters;
    private final XmlBodies xml;

    public BodyParser(SchemaShapes shapes) {
        this.shapes = shapes;
        this.parameters = new ParameterParser(shapes);
        this.xml = new XmlBodies(shapes);
    }

    /**
     * Parses {@code body}. For sequential media types the result is an array of the items; the
     * schema used for coercion is the media type's {@code schema} (or its {@code itemSchema}).
     */
    public Result<JsonValue, String> parse(byte[] body, ContentType type, MediaType mediaType) {
        var schema = mediaType.schema();
        var itemSchema = mediaType.itemSchema();
        var charset = charset(type);
        if (charset.isErr()) {
            return Result.err(charset.failure().orElseThrow());
        }
        var text = new String(body, charset.toOptional().orElseThrow());
        var essence = type.essence();
        if (LINE_DELIMITED.contains(essence)) {
            return sequence(List.of(text.split("\r?\n")));
        }
        if (essence.equals("application/json-seq")) {
            return sequence(List.of(text.split("\u001e")));
        }
        if (essence.equals("text/event-stream")) {
            return Result.ok(events(text));
        }
        if (type.isJson()) {
            return Json.parse(text).mapError(error -> "must be valid JSON: " + error);
        }
        if (essence.equals("application/x-www-form-urlencoded")) {
            return Result.ok(form(text, schema, mediaType.encoding()));
        }
        if (type.type().equals("multipart")) {
            return multipart(body, type, schema.or(() -> itemSchema), mediaType);
        }
        if (type.isXml()) {
            return xml.parse(body, schema);
        }
        if (type.type().equals("text")) {
            return Result.ok(shapes.coerce(text, schema));
        }
        return Result.ok(JsonString.of(new String(body, StandardCharsets.ISO_8859_1)));
    }

    private static Result<Charset, String> charset(ContentType type) {
        var name = type.parameter("charset");
        if (name.isEmpty()) {
            return Result.ok(StandardCharsets.UTF_8);
        }
        return Result.attempt(() -> Charset.forName(name.get()), e -> "unsupported charset '" + name.get() + "'");
    }

    private static Result<JsonValue, String> sequence(List<String> chunks) {
        var items = new ArrayList<JsonValue>();
        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i).trim();
            if (chunk.isEmpty()) {
                continue;
            }
            var parsed = Json.parse(chunk);
            if (parsed.isErr()) {
                return Result.err("item " + i + " must be valid JSON: " + parsed.failure().orElseThrow());
            }
            items.add(parsed.toOptional().orElseThrow());
        }
        return Result.ok(new JsonArray(items));
    }

    /** Server-sent events as objects with {@code event}, {@code data}, {@code id} and {@code retry}. */
    private static JsonValue events(String text) {
        var events = new ArrayList<JsonValue>();
        for (var block : text.split("\r?\n\r?\n")) {
            var event = new LinkedHashMap<String, JsonValue>();
            var data = new ArrayList<String>();
            for (var line : block.split("\r?\n")) {
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                var colon = line.indexOf(':');
                var field = colon < 0 ? line : line.substring(0, colon);
                var value = colon < 0 ? "" : line.substring(colon + 1).replaceFirst("^ ", "");
                switch (field) {
                    case "data" -> data.add(value);
                    case "retry" -> event.put("retry", value.matches("[0-9]+") ? JsonNumber.of(new BigDecimal(value)) : JsonString.of(value));
                    default -> event.put(field, JsonString.of(value));
                }
            }
            if (!data.isEmpty()) {
                event.put("data", JsonString.of(String.join("\n", data)));
            }
            if (!event.isEmpty()) {
                events.add(new JsonObject(event));
            }
        }
        return new JsonArray(events);
    }

    /** A form body; properties whose encoding has a JSON {@code contentType} are parsed as JSON. */
    private JsonValue form(String text, Optional<Schema> schema, Map<String, Encoding> encodings) {
        var members = new LinkedHashMap<>(parameters.formObject(text, schema).members());
        for (var pair : UrlCodec.parseQuery(text)) {
            var json = Optional.ofNullable(encodings.get(pair.getKey())).flatMap(Encoding::contentType).flatMap(ContentType::parse)
                .filter(ContentType::isJson).isPresent();
            if (json) {
                members.put(pair.getKey(), Json.parse(pair.getValue()).toOptional().orElse(JsonString.of(pair.getValue())));
            }
        }
        return new JsonObject(members);
    }

    private Result<JsonValue, String> multipart(byte[] body, ContentType type, Optional<Schema> schema, MediaType mediaType) {
        var boundary = type.parameter("boundary");
        if (boundary.isEmpty()) {
            return Result.err("multipart content type has no boundary");
        }
        var parsed = Multipart.parse(body, boundary.get());
        if (parsed.isErr()) {
            return Result.err(parsed.failure().orElseThrow());
        }
        var parts = parsed.toOptional().orElseThrow();
        if (type.subtype().equals("form-data")) {
            return Result.ok(formData(parts, schema, mediaType.encoding()));
        }
        var items = new ArrayList<JsonValue>();
        for (var part : parts) {
            var index = items.size();
            var encoding = index < mediaType.prefixEncoding().size() ? Optional.of(mediaType.prefixEncoding().get(index)) : mediaType.itemEncoding();
            var item = mediaType.itemSchema().or(() -> mediaType.schema().flatMap(s -> shapes.item(s, index)));
            items.add(partValue(part, item, encoding));
        }
        return Result.ok(new JsonArray(items));
    }

    private JsonValue formData(List<Multipart.Part> parts, Optional<Schema> schema, Map<String, Encoding> encodings) {
        var grouped = new LinkedHashMap<String, List<Multipart.Part>>();
        parts.forEach(part -> grouped.computeIfAbsent(part.name().orElse(""), key -> new ArrayList<>()).add(part));
        var members = new LinkedHashMap<String, JsonValue>();
        grouped.forEach((name, group) -> {
            var property = schema.flatMap(s -> shapes.property(s, name));
            var encoding = Optional.ofNullable(encodings.get(name));
            if (property.map(shapes::shape).filter(shape -> shape == Shape.ARRAY).isPresent()) {
                var items = property.flatMap(shapes::items);
                members.put(name, new JsonArray(group.stream().map(part -> partValue(part, items, encoding)).toList()));
            } else {
                members.put(name, partValue(group.getFirst(), property, encoding));
            }
        });
        return new JsonObject(members);
    }

    /** A part as JSON (JSON parts are parsed, binary parts become ISO-8859-1 strings, text is coerced). */
    private JsonValue partValue(Multipart.Part part, Optional<Schema> schema, Optional<Encoding> encoding) {
        var declared = part.contentType().or(() -> encoding.flatMap(Encoding::contentType)).flatMap(ContentType::parse);
        var text = new String(part.body(), declared.flatMap(t -> charset(t).toOptional()).orElse(StandardCharsets.UTF_8));
        if (declared.filter(ContentType::isJson).isPresent()) {
            return Json.parse(text).toOptional().orElse(JsonString.of(text));
        }
        var binary = part.filename().isPresent() || declared.filter(t -> !t.type().equals("text")).isPresent()
            || schema.flatMap(Schema::format).filter("binary"::equals).isPresent();
        return binary ? JsonString.of(new String(part.body(), StandardCharsets.ISO_8859_1)) : shapes.coerce(text, schema);
    }
}
