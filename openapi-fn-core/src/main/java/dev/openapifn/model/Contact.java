package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/** The Contact Object. */
public record Contact(Optional<String> name, Optional<String> url, Optional<String> email, Map<String, JsonValue> extensions) {

    public Contact {
        extensions = Immutable.map(extensions);
    }
}
