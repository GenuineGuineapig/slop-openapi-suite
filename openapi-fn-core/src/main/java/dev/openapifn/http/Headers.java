package dev.openapifn.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable HTTP headers with case-insensitive names. Names are stored lower-cased.
 *
 * @param values header values keyed by lower-cased name, in arrival order
 */
public record Headers(Map<String, List<String>> values) {

    public static final Headers EMPTY = new Headers(Map.of());

    public Headers {
        var copy = new LinkedHashMap<String, List<String>>();
        values.forEach((name, list) -> copy.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>()).addAll(list));
        copy.replaceAll((name, list) -> List.copyOf(list));
        values = Collections.unmodifiableMap(copy);
    }

    /** Headers from alternating name/value pairs. */
    public static Headers of(String... namesAndValues) {
        var headers = EMPTY;
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            headers = headers.with(namesAndValues[i], namesAndValues[i + 1]);
        }
        return headers;
    }

    /** All values of {@code name}. */
    public List<String> all(String name) {
        return values.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    /** The first value of {@code name}. */
    public Optional<String> first(String name) {
        return all(name).stream().findFirst();
    }

    public boolean has(String name) {
        return values.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** A copy without any value of {@code name}. */
    public Headers without(String name) {
        var copy = new LinkedHashMap<>(values);
        copy.remove(name.toLowerCase(Locale.ROOT));
        return new Headers(copy);
    }

    /** A copy with {@code value} appended to {@code name}. */
    public Headers with(String name, String value) {
        var copy = new LinkedHashMap<>(values);
        var list = new ArrayList<>(copy.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()));
        list.add(value);
        copy.put(name.toLowerCase(Locale.ROOT), list);
        return new Headers(copy);
    }
}
