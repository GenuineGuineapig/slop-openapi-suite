package dev.openapifn.parse;

import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonWriter;
import dev.openapifn.load.Diagnostic;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.model.CallbackOperation;
import dev.openapifn.model.MediaType;
import dev.openapifn.schema.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Checks how receivers tell callbacks apart. Callbacks of one operation with one URL expression are
 * sent to one URL, so a subscriber receives them at one place: by method, then by request. Those
 * whose requests are described alike cannot be told apart there.
 */
final class Receivers {

    private Receivers() {
    }

    /** Warnings about callbacks sent to one URL with one method whose requests are described alike. */
    static List<Diagnostic> ambiguities(OpenApiDocument api) {
        var shared = new LinkedHashMap<List<Object>, List<CallbackOperation>>();
        api.callbacks().forEach(callback -> shared.computeIfAbsent(List.of(callback.owner(), callback.operation().path(), callback.operation().method()),
            key -> new ArrayList<>()).add(callback));
        var warnings = new ArrayList<Diagnostic>();
        shared.values().forEach(callbacks -> {
            for (int i = 1; i < callbacks.size(); i++) {
                var later = callbacks.get(i);
                callbacks.subList(0, i).stream().filter(earlier -> request(api, earlier.operation()).equals(request(api, later.operation()))).findFirst()
                    .ifPresent(earlier -> warnings.add(Diagnostic.warning("Callbacks '" + earlier.name() + "' and '" + later.name() + "' of '"
                        + later.owner().displayName() + "' are sent to the same URL (" + later.operation().path() + ") with " + later.operation().method()
                        + " and requests described alike: receivers cannot tell them apart, '" + earlier.name() + "' receives both", api.uri(),
                        JsonPointer.ROOT)));
            }
        });
        return warnings;
    }

    /** What a receiver can tell a request apart by: its parameters and body content, by media type and schema. */
    private static String request(OpenApiDocument api, ApiOperation operation) {
        var parameters = operation.parameters().stream().map(p -> p.in().value() + "." + p.name() + (p.required() ? "!" : "") + "="
            + schema(api, p.schema()) + content(api, p.content())).sorted().collect(Collectors.joining(","));
        var body = operation.operation().requestBody().map(b -> (b.required() ? "!" : "") + content(api, b.content())).orElse("");
        return parameters + "|" + body;
    }

    private static String content(OpenApiDocument api, Map<String, MediaType> content) {
        return content.entrySet().stream().map(e -> e.getKey() + ":" + schema(api, e.getValue().schema()) + ":" + schema(api, e.getValue().itemSchema()))
            .sorted().collect(Collectors.joining(";", "{", "}"));
    }

    /** A referenced schema by its location, an inline one by its JSON. */
    private static String schema(OpenApiDocument api, Optional<Schema> schema) {
        return schema.map(s -> s.ref().isPresent() ? api.schemas().deref(s).location().toString() : JsonWriter.compact(s.json())).orElse("");
    }
}
