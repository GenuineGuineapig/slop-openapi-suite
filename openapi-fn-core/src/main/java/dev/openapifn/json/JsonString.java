package dev.openapifn.json;

import java.util.Objects;

/** A JSON string. */
public record JsonString(String value) implements JsonValue {

    public JsonString {
        Objects.requireNonNull(value, "value");
    }

    public static JsonString of(String value) {
        return new JsonString(value);
    }

    /** Length in Unicode code points, which is what JSON Schema length keywords count. */
    public int codePointLength() {
        return value.codePointCount(0, value.length());
    }

    @Override
    public String typeName() {
        return "string";
    }

    @Override
    public String toString() {
        return JsonWriter.compact(this);
    }
}
