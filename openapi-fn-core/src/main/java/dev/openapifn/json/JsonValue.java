package dev.openapifn.json;

import java.util.Optional;

/**
 * An immutable JSON value. The hierarchy is sealed so that consumers can pattern-match
 * exhaustively over the six JSON kinds.
 */
public sealed interface JsonValue permits JsonNull, JsonBoolean, JsonNumber, JsonString, JsonArray, JsonObject {

    /** The JSON Schema type name of this value ({@code "integer"} for integral numbers). */
    String typeName();

    default Optional<JsonObject> asObject() {
        return this instanceof JsonObject object ? Optional.of(object) : Optional.empty();
    }

    default Optional<JsonArray> asArray() {
        return this instanceof JsonArray array ? Optional.of(array) : Optional.empty();
    }

    default Optional<String> asString() {
        return this instanceof JsonString(var value) ? Optional.of(value) : Optional.empty();
    }

    default Optional<JsonNumber> asNumber() {
        return this instanceof JsonNumber number ? Optional.of(number) : Optional.empty();
    }

    default Optional<Boolean> asBoolean() {
        return this instanceof JsonBoolean(var value) ? Optional.of(value) : Optional.empty();
    }

    default boolean isNull() {
        return this == JsonNull.INSTANCE;
    }

    /** Compact JSON text. */
    default String toJson() {
        return JsonWriter.compact(this);
    }
}
