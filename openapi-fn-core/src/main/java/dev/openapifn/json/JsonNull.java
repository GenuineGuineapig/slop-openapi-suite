package dev.openapifn.json;

/** The JSON {@code null} literal. */
public enum JsonNull implements JsonValue {
    INSTANCE;

    @Override
    public String typeName() {
        return "null";
    }

    @Override
    public String toString() {
        return "null";
    }
}
