package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/** The License Object. */
public record License(String name, Optional<String> identifier, Optional<String> url, Map<String, JsonValue> extensions) {

    public License {
        extensions = Immutable.map(extensions);
    }
}
