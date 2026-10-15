package dev.openapifn.schema;

import dev.openapifn.json.JsonPointer;
import java.net.URI;
import java.util.List;

/**
 * A schema validation failure.
 *
 * @param instancePath location of the offending value in the validated instance
 * @param keyword the failing keyword, e.g. {@code required} or {@code type}
 * @param message human readable description
 * @param schemaLocation absolute location of the failing keyword
 * @param causes nested failures explaining a combinator failure ({@code anyOf}, {@code oneOf})
 */
public record SchemaError(JsonPointer instancePath, String keyword, String message, URI schemaLocation, List<SchemaError> causes) {

    public SchemaError {
        causes = List.copyOf(causes);
    }

    @Override
    public String toString() {
        return (instancePath.isRoot() ? "/" : instancePath.toString()) + ": " + message;
    }
}
