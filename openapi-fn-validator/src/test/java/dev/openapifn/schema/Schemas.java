package dev.openapifn.schema;

import dev.openapifn.json.Json;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.DocumentLoader;
import dev.openapifn.load.DocumentParser;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test helpers for building schemas and registries from text. */
final class Schemas {

    static final URI ROOT = URI.create("mem:/root.json");

    private Schemas() {
    }

    static JsonValue json(String text) {
        return Json.parse(text).orElseThrow(e -> new AssertionError(e.toString()));
    }

    /** A registry rooted at a standalone schema document, with further documents by name. */
    static SchemaRegistry.Build build(String schema, Dialect dialect, Map<String, String> others) {
        var documents = new HashMap<URI, String>();
        others.forEach((name, text) -> documents.put(ROOT.resolve(name), text));
        var loader = new DocumentLoader(ResourceLoaders.inMemory(documents));
        var document = DocumentParser.parse(ROOT, schema).orElseThrow(d -> new AssertionError(d.toString()));
        loader.register(document);
        var root = Schema.at(document.root(), ROOT, JsonPointer.ROOT, ROOT, dialect);
        return SchemaRegistry.build(List.of(root), loader, dialect);
    }

    static Schema root(SchemaRegistry registry) {
        return registry.resolve(ROOT).orElseThrow();
    }

    static List<SchemaError> validate(String schema, Dialect dialect, String instance) {
        var registry = build(schema, dialect, Map.of()).registry();
        return new SchemaValidator(registry).validate(root(registry), json(instance));
    }

    static List<String> messages(String schema, Dialect dialect, String instance) {
        return validate(schema, dialect, instance).stream().map(SchemaError::toString).toList();
    }

    static OpenApiDocument api(String yaml) {
        return new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), yaml)
            .orElseThrow(d -> new AssertionError(d.toString()));
    }
}
