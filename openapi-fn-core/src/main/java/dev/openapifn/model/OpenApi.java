package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The OpenAPI Object: the root of an API description.
 *
 * @param openapi the declared version string
 * @param version the interpreted specification version
 * @param self the {@code $self} URI (3.2)
 * @param info API metadata
 * @param jsonSchemaDialect default schema dialect (3.1+)
 * @param servers servers; never empty (defaults to {@code /})
 * @param paths the paths
 * @param webhooks incoming webhooks (3.1+)
 * @param components reusable components
 * @param security global security requirements
 * @param tags tag metadata
 * @param externalDocs external documentation
 * @param extensions specification extensions
 */
public record OpenApi(String openapi, SpecVersion version, Optional<String> self, Info info, Optional<String> jsonSchemaDialect,
                      List<Server> servers, Paths paths, Map<String, PathItem> webhooks, Components components,
                      List<SecurityRequirement> security, List<Tag> tags, Optional<ExternalDocs> externalDocs,
                      Map<String, JsonValue> extensions) {

    public OpenApi {
        servers = List.copyOf(servers);
        webhooks = Immutable.map(webhooks);
        security = List.copyOf(security);
        tags = List.copyOf(tags);
        extensions = Immutable.map(extensions);
    }
}
