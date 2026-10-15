package dev.openapifn.parse;

import dev.openapifn.json.JsonPointer;
import dev.openapifn.load.Diagnostic;
import dev.openapifn.load.Uris;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.model.ApiOperation.Kind;
import dev.openapifn.model.CallbackOperation;
import dev.openapifn.model.Link;
import dev.openapifn.model.OpenApi;
import dev.openapifn.model.PathItem;
import dev.openapifn.schema.SchemaRegistry;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        return flatten(model.paths().items(), Kind.PATH);
    }

    /**
     * All webhook operations (3.1+); the path of each is the webhook name. Webhooks are sent by
     * the API provider, so the top-level security requirements do not apply to them.
     */
    public List<ApiOperation> webhooks() {
        return flatten(model.webhooks(), Kind.WEBHOOK);
    }

    /**
     * The callback operations of all path and webhook operations, in document order. Like
     * webhooks, callbacks are sent by the API provider.
     */
    public List<CallbackOperation> callbacks() {
        var result = new ArrayList<CallbackOperation>();
        var owners = new ArrayList<>(operations());
        owners.addAll(webhooks());
        owners.forEach(owner -> owner.operation().callbacks().forEach((name, callback) -> flatten(callback.expressions(), Kind.CALLBACK)
            .forEach(operation -> result.add(new CallbackOperation(owner, name, operation)))));
        return result;
    }

    /** Path, webhook and callback operations. */
    public List<ApiOperation> allOperations() {
        var all = new ArrayList<>(operations());
        all.addAll(webhooks());
        callbacks().forEach(callback -> all.add(callback.operation()));
        return all;
    }

    /**
     * The operation (path, webhook or callback) an identifier names: its {@code operationId}, else
     * {@code METHOD path} for path and webhook operations and {@link CallbackOperation#id()} for callbacks.
     */
    public Optional<ApiOperation> operation(String id) {
        var all = allOperations();
        return all.stream().filter(op -> op.operationId().filter(id::equals).isPresent()).findFirst()
            .or(() -> all.stream().filter(op -> op.kind() != Kind.CALLBACK && op.displayName().equals(id)).findFirst())
            .or(() -> callbacks().stream().filter(callback -> callback.id().equals(id)).map(CallbackOperation::operation).findFirst());
    }

    /**
     * The operation a Link Object leads to: the one its {@code operationId} names, or the path or
     * webhook operation its {@code operationRef} points to in this description (such as
     * {@code #/paths/~1pets~1{petId}/get}). Operations of other APIs are not known.
     */
    public Optional<ApiOperation> target(Link link) {
        if (link.operationId().isPresent()) {
            var id = link.operationId().get();
            return allOperations().stream().filter(op -> op.operationId().filter(id::equals).isPresent()).findFirst();
        }
        return link.operationRef().flatMap(ref -> Uris.resolve(uri, ref).toOptional())
            .filter(ref -> Uris.withoutFragment(ref).equals(Uris.withoutFragment(uri)))
            .flatMap(ref -> JsonPointer.parse(Uris.fragment(ref)).toOptional())
            .filter(pointer -> pointer.tokens().size() == 3)
            .flatMap(pointer -> {
                var tokens = pointer.tokens();
                var candidates = switch (tokens.getFirst()) {
                    case "paths" -> operations();
                    case "webhooks" -> webhooks();
                    default -> List.<ApiOperation>of();
                };
                return candidates.stream().filter(op -> op.path().equals(tokens.get(1)) && op.method().equals(tokens.get(2).toUpperCase(Locale.ROOT)))
                    .findFirst();
            });
    }

    private List<ApiOperation> flatten(Map<String, PathItem> items, Kind kind) {
        var result = new ArrayList<ApiOperation>();
        items.forEach((path, item) -> item.operations().forEach((method, operation) -> result.add(new ApiOperation(path, method, item,
            operation, operation.effectiveParameters(item), operation.security().orElse(kind == Kind.PATH ? model.security() : List.of()),
            !operation.servers().isEmpty() ? operation.servers() : !item.servers().isEmpty() ? item.servers() : model.servers(),
            kind))));
        return result;
    }
}
