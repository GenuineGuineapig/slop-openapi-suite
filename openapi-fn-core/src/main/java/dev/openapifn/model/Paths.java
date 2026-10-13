package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;

/** The Paths Object: path templates (such as {@code /pets/{id}}) to their path items. */
public record Paths(Map<String, PathItem> items, Map<String, JsonValue> extensions) {

    public static final Paths EMPTY = new Paths(Map.of(), Map.of());

    public Paths {
        items = Immutable.map(items);
        extensions = Immutable.map(extensions);
    }
}
