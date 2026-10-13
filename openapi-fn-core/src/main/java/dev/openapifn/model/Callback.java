package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;

/** The Callback Object: runtime expressions to the path items describing the callback requests. */
public record Callback(Map<String, PathItem> expressions, Map<String, JsonValue> extensions) {

    public Callback {
        expressions = Immutable.map(expressions);
        extensions = Immutable.map(extensions);
    }
}
