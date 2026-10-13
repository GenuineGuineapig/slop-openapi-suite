package dev.openapifn.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** An immutable JSON object that preserves member insertion order. */
public record JsonObject(Map<String, JsonValue> members) implements JsonValue {

    public static final JsonObject EMPTY = new JsonObject(Map.of());

    public JsonObject {
        var copy = new LinkedHashMap<String, JsonValue>(members.size() * 2);
        members.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value")));
        members = Collections.unmodifiableMap(copy);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<JsonValue> get(String name) {
        return Optional.ofNullable(members.get(name));
    }

    public boolean has(String name) {
        return members.containsKey(name);
    }

    public Set<String> keys() {
        return members.keySet();
    }

    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public Optional<String> string(String name) {
        return get(name).flatMap(JsonValue::asString);
    }

    public Optional<JsonObject> object(String name) {
        return get(name).flatMap(JsonValue::asObject);
    }

    public Optional<JsonArray> array(String name) {
        return get(name).flatMap(JsonValue::asArray);
    }

    public Optional<Boolean> bool(String name) {
        return get(name).flatMap(JsonValue::asBoolean);
    }

    public Optional<JsonNumber> number(String name) {
        return get(name).flatMap(JsonValue::asNumber);
    }

    /** A copy with {@code name} set to {@code value}. */
    public JsonObject with(String name, JsonValue value) {
        var copy = new LinkedHashMap<>(members);
        copy.put(name, value);
        return new JsonObject(copy);
    }

    /** A copy without {@code name}. */
    public JsonObject without(String name) {
        var copy = new LinkedHashMap<>(members);
        copy.remove(name);
        return new JsonObject(copy);
    }

    @Override
    public String typeName() {
        return "object";
    }

    @Override
    public String toString() {
        return JsonWriter.compact(this);
    }

    /** Mutable builder for objects; not thread-safe. */
    public static final class Builder {
        private final Map<String, JsonValue> members = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder put(String name, JsonValue value) {
            members.put(name, value);
            return this;
        }

        public Builder put(String name, String value) {
            return put(name, JsonString.of(value));
        }

        public Builder put(String name, long value) {
            return put(name, JsonNumber.of(value));
        }

        public Builder put(String name, boolean value) {
            return put(name, JsonBoolean.of(value));
        }

        public JsonObject build() {
            return new JsonObject(members);
        }
    }
}
