package dev.openapifn.json;

import java.util.Iterator;
import java.util.Map;

/** Serializes {@link JsonValue}s to JSON text. */
public final class JsonWriter {

    private final StringBuilder out = new StringBuilder();
    private final String indent;

    private JsonWriter(String indent) {
        this.indent = indent;
    }

    /** Compact JSON without insignificant whitespace. */
    public static String compact(JsonValue value) {
        var writer = new JsonWriter(null);
        writer.write(value, 0);
        return writer.out.toString();
    }

    /** JSON indented with two spaces per level. */
    public static String pretty(JsonValue value) {
        var writer = new JsonWriter("  ");
        writer.write(value, 0);
        return writer.out.toString();
    }

    /** {@code value} as a quoted, escaped JSON string literal. */
    public static String quote(String value) {
        var writer = new JsonWriter(null);
        writer.writeString(value);
        return writer.out.toString();
    }

    private void write(JsonValue value, int level) {
        switch (value) {
            case JsonNull ignored -> out.append("null");
            case JsonBoolean bool -> out.append(bool.value());
            case JsonNumber number -> out.append(number.value());
            case JsonString string -> writeString(string.value());
            case JsonArray array -> writeArray(array, level);
            case JsonObject object -> writeObject(object, level);
        }
    }

    private void writeArray(JsonArray array, int level) {
        if (array.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        Iterator<JsonValue> it = array.values().iterator();
        while (it.hasNext()) {
            newline(level + 1);
            write(it.next(), level + 1);
            if (it.hasNext()) {
                out.append(',');
            }
        }
        newline(level);
        out.append(']');
    }

    private void writeObject(JsonObject object, int level) {
        if (object.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        Iterator<Map.Entry<String, JsonValue>> it = object.members().entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            newline(level + 1);
            writeString(entry.getKey());
            out.append(indent == null ? ":" : ": ");
            write(entry.getValue(), level + 1);
            if (it.hasNext()) {
                out.append(',');
            }
        }
        newline(level);
        out.append('}');
    }

    private void newline(int level) {
        if (indent != null) {
            out.append('\n').append(indent.repeat(level));
        }
    }

    private void writeString(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
