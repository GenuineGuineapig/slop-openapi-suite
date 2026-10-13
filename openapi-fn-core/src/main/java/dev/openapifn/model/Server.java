package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/**
 * The Server Object.
 *
 * @param url URL template, possibly relative and containing {@code {variables}}
 * @param description description
 * @param name short name (3.2)
 * @param variables variable definitions
 * @param extensions specification extensions
 */
public record Server(String url, Optional<String> description, Optional<String> name, Map<String, ServerVariable> variables,
                     Map<String, JsonValue> extensions) {

    public Server {
        variables = Immutable.map(variables);
        extensions = Immutable.map(extensions);
    }

    /** The URL with every variable replaced by its default value. */
    public String defaultUrl() {
        var result = url;
        for (var entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue().defaultValue());
        }
        return result;
    }
}
