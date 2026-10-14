package dev.openapifn.json;

import dev.openapifn.fn.Result;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.json.JsonFactory;

/**
 * A strict RFC 8259 JSON parser on Jackson's streaming parser, producing {@link JsonValue}s.
 * Duplicate object keys keep the last value. Jackson's read constraints apply: nesting deeper than
 * {@link #MAX_DEPTH} and overly long numbers and strings are rejected, which protects validation
 * from hostile input.
 */
public final class JsonParser {

    /** Maximum nesting depth of arrays and objects. */
    public static final int MAX_DEPTH = 512;

    private static final JsonFactory FACTORY = JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH).build())
        .build();

    private JsonParser() {
    }

    /** Parses a complete JSON text. */
    public static Result<JsonValue, JsonSyntaxError> parse(String text) {
        var parser = FACTORY.createParser(ObjectReadContext.empty(), text);
        try {
            var first = parser.nextToken();
            if (first == null) {
                return Result.err(new JsonSyntaxError("Unexpected end of input", 1, 1, 0));
            }
            var value = read(parser, first);
            if (parser.nextToken() != null) {
                return Result.err(error("Unexpected trailing content", parser.currentTokenLocation()));
            }
            return Result.ok(value);
        } catch (JacksonException e) {
            // Violated read constraints (nesting depth, number or string length) carry no location of their own.
            return Result.err(error(e.getOriginalMessage(), e.getLocation() != null ? e.getLocation() : parser.currentLocation()));
        } finally {
            parser.close();
        }
    }

    private static JsonValue read(tools.jackson.core.JsonParser parser, JsonToken token) {
        return switch (token) {
            case START_OBJECT -> {
                var members = new LinkedHashMap<String, JsonValue>();
                for (var name = parser.nextName(); name != null; name = parser.nextName()) {
                    members.put(name, read(parser, parser.nextToken()));
                }
                yield new JsonObject(members);
            }
            case START_ARRAY -> {
                var items = new ArrayList<JsonValue>();
                for (var next = parser.nextToken(); next != JsonToken.END_ARRAY; next = parser.nextToken()) {
                    items.add(read(parser, next));
                }
                yield new JsonArray(items);
            }
            case VALUE_STRING -> JsonString.of(parser.getString());
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> JsonNumber.of(parser.getDecimalValue());
            case VALUE_TRUE -> JsonBoolean.TRUE;
            case VALUE_FALSE -> JsonBoolean.FALSE;
            default -> JsonNull.INSTANCE; // VALUE_NULL: the parser yields no other tokens in value position
        };
    }

    private static JsonSyntaxError error(String message, TokenStreamLocation location) {
        return new JsonSyntaxError(message, location.getLineNr(), location.getColumnNr(), (int) location.getCharOffset());
    }
}
