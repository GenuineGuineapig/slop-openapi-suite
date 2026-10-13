package dev.openapifn.parse;

import dev.openapifn.load.Diagnostic;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.model.OpenApi;
import dev.openapifn.model.PathItem;
import dev.openapifn.schema.SchemaRegistry;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A fully loaded and resolved OpenAPI description.
 *
 * @param uri URI of the root document
 * @param model the interpreted model
 * @param schemas resolution of schema references across all loaded documents
 * @param warnings non-fatal problems found while loading
 */
public record OpenApiDocument(URI uri, OpenApi model, SchemaRegistry schemas, List<Diagnostic> warnings) {

    public OpenApiDocument {
        warnings = List.copyOf(warnings);
    }

    /** All operations of all paths, in document order. */
    public List<ApiOperation> operations() {
        return flatten(model.paths().items(), false);
    }

    /**
     * All webhook operations (3.1+); the path of each is the webhook name. Webhooks are sent by
     * the API provider, so the top-level security requirements do not apply to them.
     */
    public List<ApiOperation> webhooks() {
        return flatten(model.webhooks(), true);
    }

    /** The operation (including webhooks) with the given {@code operationId}. */
    public Optional<ApiOperation> operation(String operationId) {
        var all = new ArrayList<>(operations());
        all.addAll(webhooks());
        return all.stream().filter(op -> op.operationId().filter(operationId::equals).isPresent()).findFirst();
    }

    private List<ApiOperation> flatten(Map<String, PathItem> items, boolean webhook) {
        var result = new ArrayList<ApiOperation>();
        items.forEach((path, item) -> item.operations().forEach((method, operation) -> result.add(new ApiOperation(path, method, item,
            operation, operation.effectiveParameters(item), operation.security().orElse(webhook ? List.of() : model.security()),
            !operation.servers().isEmpty() ? operation.servers() : !item.servers().isEmpty() ? item.servers() : model.servers(),
            webhook))));
        return result;
    }
}
