package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Operation Object.
 *
 * @param tags tags
 * @param summary summary
 * @param description description
 * @param externalDocs external documentation
 * @param operationId unique identifier
 * @param parameters operation parameters (not merged with path item parameters; see {@link #effectiveParameters})
 * @param requestBody request body
 * @param responses possible responses
 * @param callbacks callbacks keyed by name
 * @param deprecated whether the operation is deprecated
 * @param security security requirements; empty optional means the global requirements apply
 * @param servers alternative servers
 * @param extensions specification extensions
 */
public record Operation(List<String> tags, Optional<String> summary, Optional<String> description, Optional<ExternalDocs> externalDocs,
                        Optional<String> operationId, List<Parameter> parameters, Optional<RequestBody> requestBody, Responses responses,
                        Map<String, Callback> callbacks, boolean deprecated, Optional<List<SecurityRequirement>> security,
                        List<Server> servers, Map<String, JsonValue> extensions) {

    public Operation {
        tags = List.copyOf(tags);
        parameters = List.copyOf(parameters);
        callbacks = Immutable.map(callbacks);
        security = security.map(List::copyOf);
        servers = List.copyOf(servers);
        extensions = Immutable.map(extensions);
    }

    /**
     * Path item parameters overridden by operation parameters with the same name and location.
     */
    public List<Parameter> effectiveParameters(PathItem pathItem) {
        var result = new ArrayList<Parameter>();
        for (var shared : pathItem.parameters()) {
            if (parameters.stream().noneMatch(own -> own.sameIdentity(shared))) {
                result.add(shared);
            }
        }
        result.addAll(parameters);
        return List.copyOf(result);
    }
}
