package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The Server Variable Object. */
public record ServerVariable(List<String> enumValues, String defaultValue, Optional<String> description, Map<String, JsonValue> extensions) {

    public ServerVariable {
        enumValues = List.copyOf(enumValues);
        extensions = Immutable.map(extensions);
    }
}
