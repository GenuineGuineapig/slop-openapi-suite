package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/**
 * The Example Object.
 *
 * @param summary summary
 * @param description description
 * @param value literal example (deprecated in 3.2 in favour of {@code dataValue})
 * @param dataValue the example in data form (3.2)
 * @param serializedValue the example in serialized form (3.2)
 * @param externalValue URI of an external example
 * @param extensions specification extensions
 */
public record Example(Optional<String> summary, Optional<String> description, Optional<JsonValue> value, Optional<JsonValue> dataValue,
                      Optional<String> serializedValue, Optional<String> externalValue, Map<String, JsonValue> extensions) {

    public Example {
        extensions = Immutable.map(extensions);
    }

    /** The data form of the example: {@code dataValue}, else {@code value}. */
    public Optional<JsonValue> data() {
        return dataValue.or(() -> value);
    }
}
