package dev.openapifn.schema;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/**
 * The OpenAPI XML Object.
 *
 * @param name element or attribute name
 * @param namespace namespace URI
 * @param prefix namespace prefix
 * @param attribute whether the property is an attribute (deprecated in 3.2 in favour of {@code nodeType})
 * @param wrapped whether arrays are wrapped (deprecated in 3.2 in favour of {@code nodeType})
 * @param nodeType {@code element}, {@code attribute}, {@code text}, {@code cdata} or {@code none} (3.2)
 * @param extensions specification extensions
 */
public record Xml(Optional<String> name, Optional<String> namespace, Optional<String> prefix, boolean attribute, boolean wrapped,
                  Optional<String> nodeType, Map<String, JsonValue> extensions) {

    public Xml {
        extensions = Immutable.map(extensions);
    }

    /** The effective node type, taking the deprecated {@code attribute}/{@code wrapped} flags into account. */
    public String effectiveNodeType() {
        return nodeType.orElse(attribute ? "attribute" : "element");
    }
}
