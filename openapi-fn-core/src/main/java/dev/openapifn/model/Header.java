package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import dev.openapifn.schema.Schema;
import java.util.Map;
import java.util.Optional;

/** The Header Object (a parameter whose name and location come from its context; style is always {@code simple}). */
public record Header(Optional<String> description, boolean required, boolean deprecated, boolean explode, Optional<Schema> schema,
                     Optional<JsonValue> example, Map<String, Example> examples, Map<String, MediaType> content,
                     Map<String, JsonValue> extensions) {

    public Header {
        examples = Immutable.map(examples);
        content = Immutable.map(content);
        extensions = Immutable.map(extensions);
    }

    /** This header as a header parameter called {@code name}. */
    public Parameter asParameter(String name) {
        return new Parameter(name, ParameterLocation.HEADER, description, required, deprecated, false, ParameterStyle.SIMPLE, explode,
            false, schema, example, examples, content, extensions);
    }
}
