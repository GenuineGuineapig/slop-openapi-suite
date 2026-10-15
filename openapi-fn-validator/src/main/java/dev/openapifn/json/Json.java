package dev.openapifn.json;

import dev.openapifn.fn.Result;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conversions between plain Java values and {@link JsonValue}s. */
public final class Json {

    private Json() {
    }

    /** Parses JSON text; see {@link JsonParser}. */
    public static Result<JsonValue, JsonSyntaxError> parse(String text) {
        return JsonParser.parse(text);
    }

    /**
     * Converts {@code null}, booleans, numbers, strings/chars, maps (keys via {@code toString}),
     * collections, arrays of objects and existing {@link JsonValue}s into JSON.
     *
     * @throws IllegalArgumentException for any other type
     */
    public static JsonValue of(Object value) {
        return switch (value) {
            case null -> JsonNull.INSTANCE;
            case JsonValue json -> json;
            case Boolean bool -> JsonBoolean.of(bool);
            case BigDecimal decimal -> JsonNumber.of(decimal);
            case BigInteger integer -> JsonNumber.of(integer);
            case Double d -> JsonNumber.of(d);
            case Float f -> JsonNumber.of(new BigDecimal(f.toString()));
            case Number number -> JsonNumber.of(number.longValue());
            case CharSequence chars -> JsonString.of(chars.toString());
            case Character c -> JsonString.of(c.toString());
            case Map<?, ?> map -> {
                var members = new LinkedHashMap<String, JsonValue>();
                map.forEach((key, item) -> members.put(String.valueOf(key), of(item)));
                yield new JsonObject(members);
            }
            case Collection<?> collection -> new JsonArray(collection.stream().map(Json::of).toList());
            case Object[] array -> new JsonArray(Arrays.stream(array).map(Json::of).toList());
            default -> throw new IllegalArgumentException("Cannot convert " + value.getClass().getName() + " to JSON");
        };
    }

    /**
     * Converts JSON into plain Java values: {@code null}, {@link Boolean}, {@link BigDecimal} or
     * {@link BigInteger} (for integral numbers), {@link String}, {@link List} and ordered {@link Map}.
     * Collections are mutable copies.
     */
    public static Object toJava(JsonValue value) {
        return switch (value) {
            case JsonNull ignored -> null;
            case JsonBoolean bool -> bool.value();
            case JsonNumber number -> number.isIntegral() ? number.value().toBigInteger() : number.value();
            case JsonString string -> string.value();
            case JsonArray array -> {
                var list = new ArrayList<>(array.size());
                array.values().forEach(item -> list.add(toJava(item)));
                yield list;
            }
            case JsonObject object -> {
                var map = new LinkedHashMap<String, Object>();
                object.members().forEach((key, item) -> map.put(key, toJava(item)));
                yield map;
            }
        };
    }
}
