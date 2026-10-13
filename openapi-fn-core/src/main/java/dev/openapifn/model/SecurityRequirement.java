package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import java.util.List;
import java.util.Map;

/**
 * A Security Requirement Object: all listed schemes must be satisfied. An empty requirement
 * makes security optional.
 *
 * @param schemes scheme names to required scopes or roles
 */
public record SecurityRequirement(Map<String, List<String>> schemes) {

    public SecurityRequirement {
        var copy = new java.util.LinkedHashMap<String, List<String>>();
        schemes.forEach((name, scopes) -> copy.put(name, List.copyOf(scopes)));
        schemes = Immutable.map(copy);
    }
}
