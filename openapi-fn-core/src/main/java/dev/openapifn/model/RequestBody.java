package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/** The Request Body Object. */
public record RequestBody(Optional<String> description, Map<String, MediaType> content, boolean required, Map<String, JsonValue> extensions) {

    public RequestBody {
        content = Immutable.map(content);
        extensions = Immutable.map(extensions);
    }
}
