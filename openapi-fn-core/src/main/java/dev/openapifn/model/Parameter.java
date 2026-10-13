package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import dev.openapifn.schema.Schema;
import java.util.Map;
import java.util.Optional;

/**
 * The Parameter Object, with {@code style} and {@code explode} defaults already applied.
 *
 * @param name parameter name (case-insensitive for headers)
 * @param in location
 * @param description description
 * @param required whether the parameter is required (always true for path parameters)
 * @param deprecated whether the parameter is deprecated
 * @param allowEmptyValue whether an empty query value is allowed
 * @param style serialization style
 * @param explode whether arrays and objects are exploded
 * @param allowReserved whether reserved characters are allowed unencoded
 * @param schema schema of the value (mutually exclusive with {@code content})
 * @param example example value
 * @param examples named examples
 * @param content media type of the serialized value (mutually exclusive with {@code schema})
 * @param extensions specification extensions
 */
public record Parameter(String name, ParameterLocation in, Optional<String> description, boolean required, boolean deprecated,
                        boolean allowEmptyValue, ParameterStyle style, boolean explode, boolean allowReserved, Optional<Schema> schema,
                        Optional<JsonValue> example, Map<String, Example> examples, Map<String, MediaType> content,
                        Map<String, JsonValue> extensions) {

    public Parameter {
        examples = Immutable.map(examples);
        content = Immutable.map(content);
        extensions = Immutable.map(extensions);
    }

    /** Whether {@code other} has the same name (case-insensitive for headers) and location. */
    public boolean sameIdentity(Parameter other) {
        return in == other.in && (in == ParameterLocation.HEADER ? name.equalsIgnoreCase(other.name) : name.equals(other.name));
    }

    /** The schema of the value, taken from {@code content} when no direct schema is given. */
    public Optional<Schema> effectiveSchema() {
        return schema.or(() -> content.values().stream().findFirst().flatMap(MediaType::schema));
    }
}
