package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/** The External Documentation Object. */
public record ExternalDocs(Optional<String> description, String url, Map<String, JsonValue> extensions) {

    public ExternalDocs {
        extensions = Immutable.map(extensions);
    }
}
