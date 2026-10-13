package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Path Item Object.
 *
 * @param summary summary
 * @param description description
 * @param operations operations keyed by upper-case HTTP method, including {@code QUERY} and
 *     {@code additionalOperations} (3.2)
 * @param servers alternative servers
 * @param parameters parameters shared by all operations
 * @param extensions specification extensions
 */
public record PathItem(Optional<String> summary, Optional<String> description, Map<String, Operation> operations, List<Server> servers,
                       List<Parameter> parameters, Map<String, JsonValue> extensions) {

    /** The fixed operation fields, in specification order. */
    public static final List<String> FIXED_METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch", "trace", "query");

    public PathItem {
        operations = Immutable.map(operations);
        servers = List.copyOf(servers);
        parameters = List.copyOf(parameters);
        extensions = Immutable.map(extensions);
    }

    public Optional<Operation> operation(String method) {
        return Optional.ofNullable(operations.get(method.toUpperCase(java.util.Locale.ROOT)));
    }
}
