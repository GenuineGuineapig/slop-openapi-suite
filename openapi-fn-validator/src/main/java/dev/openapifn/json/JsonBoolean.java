package dev.openapifn.json;

/** A JSON boolean. */
public record JsonBoolean(boolean value) implements JsonValue {

    public static final JsonBoolean TRUE = new JsonBoolean(true);
    public static final JsonBoolean FALSE = new JsonBoolean(false);

    public static JsonBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override
    public String typeName() {
        return "boolean";
    }

    @Override
    public String toString() {
        return Boolean.toString(value);
    }
}
