package dev.openapifn.schema;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/**
 * The OpenAPI Discriminator Object.
 *
 * @param propertyName name of the property holding the discriminating value
 * @param mapping discriminating values to schema names or references
 * @param defaultMapping schema used when the value is absent or unmapped (3.2)
 * @param extensions specification extensions
 */
public record Discriminator(String propertyName, Map<String, String> mapping, Optional<String> defaultMapping, Map<String, JsonValue> extensions) {

    public Discriminator {
        mapping = Immutable.map(mapping);
        extensions = Immutable.map(extensions);
    }
}
