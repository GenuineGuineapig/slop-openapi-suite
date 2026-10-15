package dev.openapifn.fn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Order-preserving immutable copies. */
public final class Immutable {

    private Immutable() {
    }

    /** An unmodifiable copy of {@code map} that keeps its iteration order. */
    public static <K, V> Map<K, V> map(Map<K, V> map) {
        return map.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /** An unmodifiable copy of {@code list}; unlike {@link List#copyOf}, it permits {@code null} elements. */
    public static <T> List<T> list(List<T> list) {
        return Collections.unmodifiableList(new ArrayList<>(list));
    }
}
