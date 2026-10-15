package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/** The Link Object. */
public record Link(Optional<String> operationRef, Optional<String> operationId, Map<String, JsonValue> parameters,
                   Optional<JsonValue> requestBody, Optional<String> description, Optional<Server> server, Map<String, JsonValue> extensions) {

    public Link {
        parameters = Immutable.map(parameters);
        extensions = Immutable.map(extensions);
    }
}
