package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/**
 * The Tag Object.
 *
 * @param name tag name
 * @param summary short summary (3.2)
 * @param description description
 * @param externalDocs external documentation
 * @param parent name of the parent tag (3.2)
 * @param kind machine-readable category such as {@code nav} (3.2)
 * @param extensions specification extensions
 */
public record Tag(String name, Optional<String> summary, Optional<String> description, Optional<ExternalDocs> externalDocs,
                  Optional<String> parent, Optional<String> kind, Map<String, JsonValue> extensions) {

    public Tag {
        extensions = Immutable.map(extensions);
    }
}
