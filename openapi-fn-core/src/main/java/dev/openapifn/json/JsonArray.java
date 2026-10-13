package dev.openapifn.json;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** An immutable JSON array. */
public record JsonArray(List<JsonValue> values) implements JsonValue {

    public static final JsonArray EMPTY = new JsonArray(List.of());

    public JsonArray {
        values = List.copyOf(values);
    }

    public static JsonArray of(JsonValue... values) {
        return new JsonArray(List.of(values));
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Optional<JsonValue> get(int index) {
        return index >= 0 && index < values.size() ? Optional.of(values.get(index)) : Optional.empty();
    }

    public Stream<JsonValue> stream() {
        return values.stream();
    }

    @Override
    public String typeName() {
        return "array";
    }

    @Override
    public String toString() {
        return JsonWriter.compact(this);
    }
}
