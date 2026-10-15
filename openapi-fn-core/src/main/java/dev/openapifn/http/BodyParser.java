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

/**
 * Parses message bodies into JSON according to their media type: JSON and {@code +json},
 * {@code application/x-www-form-urlencoded}, {@code multipart/*}, XML, text, binary, and the
 * sequential types {@code application/jsonl}, {@code application/x-ndjson},
 * {@code application/json-seq} and {@code text/event-stream}.
 */
public final class BodyParser {

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
        var charset = charset(type);
        if (charset.isErr()) {
            return Result.err(charset.failure().orElseThrow());
        }
        var framer = ItemFramer.of(type);
        if (framer.isPresent()) {
            return sequence(framer.get().frames(body), type, mediaType);
        }
        var text = new String(body, charset.toOptional().orElseThrow());
        var essence = type.essence();
        if (type.isJson()) {
            return Json.parse(text).mapError(error -> "must be valid JSON: " + error);
        }
        if (essence.equals("application/x-www-form-urlencoded")) {
            return Result.ok(form(text, schema, mediaType.encoding()));
        }
        if (type.type().equals("multipart")) {
            return multipart(body, type, schema, mediaType);
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

    private Result<JsonValue, String> sequence(List<ItemFramer.Frame> frames, ContentType type, MediaType mediaType) {
        var items = new ArrayList<JsonValue>();
        for (var frame : frames) {
            var item = item(frame, type, mediaType, items.size());
            if (item.isErr()) {
                return Result.err(item.failure().orElseThrow());
            }
            item.toOptional().orElseThrow().ifPresent(items::add);
        }
        return Result.ok(new JsonArray(items));
    }

    /**
     * Parses one item of a sequential body (see {@link ItemFramer}): a JSON text, an event as an
     * object with {@code event}, {@code data}, {@code id} and {@code retry}, or a positional
     * multipart part. Empty for frames that carry no item, such as blank lines and comments.
     *
     * @param index the position of the item among the items of the body, which selects its
     *     schema and encoding
     */
    public Result<Optional<JsonValue>, String> item(ItemFramer.Frame frame, ContentType type, MediaType mediaType, int index) {
        if (frame.error().isPresent()) {
            return Result.err(frame.error().get());
        }
        if (type.type().equals("multipart")) {
            if (frame.content().length == 0) {
                return Result.ok(Optional.empty());
            }
            var encoding = index < mediaType.prefixEncoding().size() ? Optional.of(mediaType.prefixEncoding().get(index)) : mediaType.itemEncoding();
            var schema = mediaType.itemSchema().or(() -> mediaType.schema().flatMap(s -> shapes.item(s, index)));
            return Multipart.part(frame.content()).map(part -> Optional.of(partValue(part, schema, encoding)));
        }
        if (frame.blank()) {
            return Result.ok(Optional.empty());
        }
        var charset = charset(type);
        if (charset.isErr()) {
            return Result.err(charset.failure().orElseThrow());
        }
        var text = new String(frame.content(), charset.toOptional().orElseThrow());
        if (type.essence().equals("text/event-stream")) {
            return Result.ok(event(text));
        }
        return Json.parse(text.trim()).<Optional<JsonValue>>map(Optional::of)
            .mapError(error -> "item " + index + " must be valid JSON: " + error);
    }

    /** An event as an object with {@code event}, {@code data}, {@code id} and {@code retry}; empty when it has no fields. */
    private static Optional<JsonValue> event(String block) {
        var event = new LinkedHashMap<String, JsonValue>();
        var data = new ArrayList<String>();
        for (var line : block.split("\r?\n")) {
            if (line.startsWith(":")) {
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
        return event.isEmpty() ? Optional.empty() : Optional.of(new JsonObject(event));
    }

    /**
     * A form body. Properties whose encoding has a JSON {@code contentType} are parsed as JSON;
     * properties whose encoding gives a {@code style}, {@code explode} or collection format are
     * parsed like query parameters of that style; others are repeated names (arrays) or single values.
     */
    private JsonValue form(String text, Optional<Schema> schema, Map<String, Encoding> encodings) {
        var members = new LinkedHashMap<>(parameters.formObject(text, schema).members());
        var values = new LinkedHashMap<String, List<String>>();
        UrlCodec.parseQuery(text).forEach(pair -> values.computeIfAbsent(pair.getKey(), key -> new ArrayList<>()).add(pair.getValue()));
        values.forEach((name, texts) -> {
            var property = schema.flatMap(s -> shapes.property(s, name));
            var array = property.map(shapes::shape).filter(shape -> shape == Shape.ARRAY).isPresent();
            // The content type of a property: its encoding's, else application/json for objects (and arrays of them).
            var declared = Optional.ofNullable(encodings.get(name)).flatMap(Encoding::contentType).flatMap(ContentType::parse);
            var item = array ? property.flatMap(shapes::items) : property;
            var json = declared.map(ContentType::isJson).orElseGet(() -> item.map(shapes::shape).filter(shape -> shape == Shape.OBJECT).isPresent());
            if (json) {
                var parsed = texts.stream().map(value -> Json.parse(value).toOptional().orElse(JsonString.of(value))).toList();
                // An array is its repeated items, or one value: the JSON its encoding asks for, or a JSON array.
                var whole = parsed.size() == 1 && (declared.isPresent() || parsed.getFirst() instanceof JsonArray);
                members.put(name, array && !whole ? new JsonArray(parsed) : parsed.getFirst());
            }
        });
        var sources = ParameterParser.Sources.ofQuery(text);
        var names = schema.map(shapes::propertyNames).orElse(java.util.Set.of());
        encodings.forEach((name, encoding) -> {
            if (!encoding.serializes()) {
                return;
            }
            var parameter = encoding.asParameter(name, schema.flatMap(s -> shapes.property(s, name)));
            if (parameter.style() == dev.openapifn.model.ParameterStyle.DEEP_OBJECT) {
                members.keySet().removeIf(key -> key.startsWith(name + "["));
            }
            var others = new java.util.HashSet<>(names);
            others.remove(name);
            parameters.parse(parameter, sources, others).toOptional().flatMap(java.util.function.Function.identity())
                .ifPresent(value -> members.put(name, value));
        });
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
        return Result.ok(formData(parsed.toOptional().orElseThrow(), schema, mediaType.encoding()));
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
