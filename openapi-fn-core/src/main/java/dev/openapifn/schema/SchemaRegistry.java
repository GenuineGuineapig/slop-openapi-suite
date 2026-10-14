package dev.openapifn.schema;

import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.Diagnostic;
import dev.openapifn.load.Document;
import dev.openapifn.load.DocumentLoader;
import dev.openapifn.load.Uris;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Resolves schema references across documents. The registry indexes every schema reachable from
 * a set of roots: embedded resources ({@code $id}), anchors ({@code $anchor}) and dynamic anchors
 * ({@code $dynamicAnchor}). While indexing, documents referenced by {@code $ref} are loaded until a
 * fixpoint is reached, so resolution never performs I/O afterwards. Instances are immutable
 * (apart from an internal cache) and thread-safe.
 */
public final class SchemaRegistry {

    private static final Pattern COMPONENT_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final List<String> SINGLE_SUBSCHEMAS = List.of("not", "if", "then", "else", "items", "additionalItems",
        "additionalProperties", "contains", "propertyNames", "unevaluatedItems", "unevaluatedProperties", "contentSchema");
    private static final List<String> LIST_SUBSCHEMAS = List.of("allOf", "anyOf", "oneOf", "prefixItems", "items");
    private static final List<String> MAP_SUBSCHEMAS = List.of("properties", "patternProperties", "$defs", "definitions",
        "dependentSchemas", "dependencies");

    /** An embedded schema resource: the document location of a schema with an identifier. */
    private record Resource(URI document, JsonPointer pointer) {
    }

    private final Map<URI, Document> documents;
    private final Function<URI, Optional<Document>> documentLookup;
    private final Map<URI, Resource> resources;
    private final Map<URI, Schema> anchors;
    private final Map<URI, Map<String, Schema>> dynamicAnchors;
    private final Dialect defaultDialect;
    private final Map<URI, Optional<Schema>> cache = new ConcurrentHashMap<>();

    private SchemaRegistry(Map<URI, Document> documents, Function<URI, Optional<Document>> documentLookup, Map<URI, Resource> resources,
                           Map<URI, Schema> anchors, Map<URI, Map<String, Schema>> dynamicAnchors, Dialect defaultDialect) {
        this.documents = documents;
        this.documentLookup = documentLookup;
        this.resources = resources;
        this.anchors = anchors;
        this.dynamicAnchors = dynamicAnchors;
        this.defaultDialect = defaultDialect;
    }

    /** The outcome of building a registry: the registry and the problems found. */
    public record Build(SchemaRegistry registry, List<Diagnostic> diagnostics) {
    }

    /**
     * Indexes everything reachable from {@code roots}, loading referenced documents through
     * {@code loader}.
     */
    public static Build build(List<Schema> roots, DocumentLoader loader, Dialect defaultDialect) {
        var builder = new Builder(loader, defaultDialect);
        roots.forEach(root -> loader.loaded(root.document()).ifPresent(builder::visitDocument));
        roots.forEach(builder::visit);
        builder.resolvePending();
        return new Build(builder.registry(), List.copyOf(builder.diagnostics));
    }

    /** A registry without documents, for schema-less processing. */
    public static SchemaRegistry empty() {
        return new SchemaRegistry(Map.of(), uri -> Optional.empty(), Map.of(), Map.of(), Map.of(), Dialect.DRAFT_2020_12);
    }

    /** Resolves an absolute reference (already resolved against the referencing schema's base URI). */
    public Optional<Schema> resolve(URI reference) {
        return cache.computeIfAbsent(reference, this::lookup);
    }

    /** Resolves {@code reference} relative to the base URI of {@code from} (see {@link #documentFallback}). */
    public Optional<Schema> resolve(Schema from, String reference) {
        return Uris.resolve(from.baseUri(), reference).toOptional().flatMap(this::resolve)
            .or(() -> documentFallback(from, reference).flatMap(this::resolve));
    }

    /**
     * Where a JSON Pointer reference such as {@code #/components/schemas/Pet} points when read
     * relative to the containing document. JSON Schema resolves it against the embedded resource
     * ({@code $id}) it occurs in, but many API descriptions mean the document; this is the fallback
     * when the resource has no such location.
     */
    static Optional<URI> documentFallback(Schema from, String reference) {
        return reference.startsWith("#/") ? Optional.of(URI.create(Uris.withoutFragment(from.document()) + reference)) : Optional.empty();
    }

    /** The target of {@code schema}'s {@code $ref}, if it has one. */
    public Optional<Schema> dereference(Schema schema) {
        return schema.ref().flatMap(ref -> resolve(schema, ref));
    }

    /** Follows {@code $ref} chains until reaching a schema without one (or an unresolvable reference). */
    public Schema deref(Schema schema) {
        var current = schema;
        var seen = new HashSet<URI>();
        while (current.ref().isPresent() && seen.add(current.location())) {
            var next = dereference(current);
            if (next.isEmpty()) {
                return current;
            }
            current = next.get();
        }
        return current;
    }

    /** The schema with dynamic anchor {@code name} in the resource identified by {@code resource}. */
    public Optional<Schema> dynamicAnchor(URI resource, String name) {
        return Optional.ofNullable(dynamicAnchors.getOrDefault(resource, Map.of()).get(name));
    }

    /**
     * Resolves a discriminator mapping value: either a component name (resolved to
     * {@code #/components/schemas/<name>} of the owning document) or a URI reference.
     */
    public Optional<Schema> resolveMapping(Schema owner, String value) {
        if (COMPONENT_NAME.matcher(value).matches()) {
            var byName = resolve(URI.create(owner.document() + JsonPointer.of("components", "schemas", value).toUriFragment()));
            if (byName.isPresent()) {
                return byName;
            }
        }
        return resolve(owner, value);
    }

    /** All loaded documents, keyed by URI. */
    public Map<URI, Document> documents() {
        return documents;
    }

    private Optional<Schema> lookup(URI reference) {
        var fragment = Uris.fragment(reference);
        var base = Uris.withoutFragment(reference).normalize();
        if (!fragment.isEmpty() && !fragment.startsWith("/")) {
            return Optional.ofNullable(anchors.get(reference));
        }
        var resource = Optional.ofNullable(resources.get(base))
            .or(() -> documentLookup.apply(base).map(doc -> new Resource(doc.uri(), JsonPointer.ROOT)));
        return resource.flatMap(r -> JsonPointer.parse(fragment).toOptional()
            .flatMap(relative -> schemaAt(documentLookup.apply(r.document()).orElseThrow(), r.pointer().append(relative), defaultDialect)));
    }

    /** The schema at {@code pointer} in {@code document}, with base URI and dialect derived from its ancestors. */
    static Optional<Schema> schemaAt(Document document, JsonPointer pointer, Dialect defaultDialect) {
        return document.resolve(pointer)
            .filter(json -> json instanceof JsonObject || json instanceof JsonBoolean)
            .map(json -> {
                var dialect = dialectAt(document, pointer, defaultDialect);
                return Schema.at(json, document.uri(), pointer, baseUri(document, pointer, dialect), dialect);
            });
    }

    /**
     * The base URI in effect for a schema at {@code pointer}: the document base adjusted by the
     * identifiers of the enclosing schemas (not of the schema itself).
     */
    public static URI baseUri(Document document, JsonPointer pointer, Dialect dialect) {
        var base = document.baseUri();
        var current = JsonPointer.ROOT;
        for (var token : pointer.tokens()) {
            var value = current.resolve(document.root()).orElseThrow();
            base = Schema.at(value, document.uri(), current, base, dialect).baseUri();
            current = current.child(token);
        }
        return base;
    }

    private static Dialect dialectAt(Document document, JsonPointer pointer, Dialect defaultDialect) {
        var dialect = defaultDialect;
        var current = JsonPointer.ROOT;
        for (var token : pointer.tokens()) {
            var declared = current.resolve(document.root()).flatMap(JsonValue::asObject).flatMap(o -> o.string("$schema"))
                .flatMap(Dialect::fromUri);
            dialect = declared.orElse(dialect);
            current = current.child(token);
        }
        return dialect;
    }

    /** Collects the index; single use. */
    private static final class Builder {
        private final DocumentLoader loader;
        private final Dialect defaultDialect;
        private final Set<URI> visited = new HashSet<>();
        private final Set<URI> visitedDocuments = new HashSet<>();
        private final Map<URI, Resource> resources = new HashMap<>();
        private final Map<URI, Schema> anchors = new HashMap<>();
        private final Map<URI, Map<String, Schema>> dynamicAnchors = new HashMap<>();
        private final ArrayDeque<Map.Entry<Schema, String>> pending = new ArrayDeque<>();
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final SchemaRegistry live;

        Builder(DocumentLoader loader, Dialect defaultDialect) {
            this.loader = loader;
            this.defaultDialect = defaultDialect;
            this.live = new SchemaRegistry(Map.of(), loader::loaded, resources, anchors, dynamicAnchors, defaultDialect);
        }

        /**
         * Indexes the schemas of a whole document, so that identifiers and anchors in parts that
         * are not referenced directly are found: the root (for pure JSON Schema documents) and the
         * component schemas (for OpenAPI documents).
         */
        void visitDocument(Document document) {
            if (!visitedDocuments.add(document.uri())) {
                return;
            }
            var dialect = SchemaRegistry.dialectAt(document, JsonPointer.ROOT, defaultDialect);
            var root = document.root();
            if (root instanceof JsonObject object && object.has("openapi")) {
                object.object("components").flatMap(c -> c.object("schemas")).ifPresent(schemas -> schemas.keys().forEach(name ->
                    schemaAt(document, JsonPointer.of("components", "schemas", name), dialect).ifPresent(this::visit)));
            } else {
                schemaAt(document, JsonPointer.ROOT, dialect).ifPresent(this::visit);
            }
        }

        void visit(Schema schema) {
            if (!visited.add(schema.location())) {
                return;
            }
            var object = schema.object();
            var resourceBase = schema.baseUri();
            var identifier = Schema.identifier(object, schema.dialect()).filter(id -> !(schema.dialect().refOverridesSiblings() && object.has("$ref")));
            identifier.filter(id -> !id.startsWith("#") && Uris.parse(id).isOk())
                .ifPresent(id -> resources.putIfAbsent(resourceBase, new Resource(schema.document(), schema.pointer())));
            identifier.flatMap(id -> Uris.parse(id).toOptional()).map(Uris::fragment).filter(fragment -> !fragment.isEmpty() && !fragment.startsWith("/"))
                .ifPresent(anchor -> anchors.putIfAbsent(anchor(resourceBase, anchor), schema));
            object.string("$anchor").ifPresent(anchor -> anchors.putIfAbsent(anchor(resourceBase, anchor), schema));
            object.string("$dynamicAnchor").ifPresent(anchor -> {
                anchors.putIfAbsent(anchor(resourceBase, anchor), schema);
                dynamicAnchors.computeIfAbsent(resourceBase, uri -> new HashMap<>()).putIfAbsent(anchor, schema);
            });
            object.string("$ref").ifPresent(ref -> pending.add(Map.entry(schema, ref)));
            object.string("$dynamicRef").ifPresent(ref -> pending.add(Map.entry(schema, ref)));
            schema.discriminator().ifPresent(d -> d.mapping().values().forEach(value -> pending.add(Map.entry(schema, mappingTarget(schema, value)))));
            SINGLE_SUBSCHEMAS.forEach(keyword -> schema.subschema(keyword).ifPresent(this::visit));
            LIST_SUBSCHEMAS.forEach(keyword -> schema.subschemaList(keyword).forEach(this::visit));
            MAP_SUBSCHEMAS.forEach(keyword -> schema.subschemaMap(keyword).values().forEach(this::visit));
        }

        /** A component name as an absolute reference when such a component exists; otherwise the value itself. */
        private String mappingTarget(Schema schema, String value) {
            var byName = schema.document() + JsonPointer.of("components", "schemas", value).toUriFragment();
            return COMPONENT_NAME.matcher(value).matches() && live.lookup(URI.create(byName)).isPresent() ? byName : value;
        }

        private static URI anchor(URI base, String anchor) {
            return URI.create(base + "#" + anchor);
        }

        void resolvePending() {
            while (!pending.isEmpty()) {
                var entry = pending.poll();
                var from = entry.getKey();
                var ref = entry.getValue();
                var target = Uris.resolve(from.baseUri(), ref);
                if (target.isErr()) {
                    report(from, target.failure().orElseThrow());
                    continue;
                }
                var uri = target.toOptional().orElseThrow();
                loader.loaded(uri).ifPresent(this::visitDocument);
                var resolved = live.lookup(uri);
                if (resolved.isEmpty() && !resources.containsKey(Uris.withoutFragment(uri))) {
                    var document = loader.load(uri);
                    if (document.isErr()) {
                        report(from, "Cannot resolve '" + ref + "': " + document.failure().orElseThrow().message());
                        continue;
                    }
                    visitDocument(document.toOptional().orElseThrow());
                    resolved = live.lookup(uri);
                }
                if (resolved.isEmpty()) {
                    resolved = documentFallback(from, ref).flatMap(live::lookup);
                    resolved.ifPresent(fallback -> warn(from, "'" + ref + "' does not exist in the schema resource " + Uris.withoutFragment(uri)
                        + "; resolved against the document instead"));
                }
                resolved.ifPresentOrElse(this::visit, () -> report(from, "Cannot resolve '" + ref + "': target not found"));
            }
        }

        private void report(Schema from, String message) {
            add(Diagnostic.error(message, from.document(), from.pointer()), from);
        }

        private void warn(Schema from, String message) {
            add(Diagnostic.warning(message, from.document(), from.pointer()), from);
        }

        private void add(Diagnostic diagnostic, Schema from) {
            var document = loader.load(from.document()).toOptional();
            diagnostics.add(diagnostic.withPosition(document.flatMap(d -> d.position(from.pointer()))));
        }

        SchemaRegistry registry() {
            var frozenDynamic = new HashMap<URI, Map<String, Schema>>();
            dynamicAnchors.forEach((uri, map) -> frozenDynamic.put(uri, Map.copyOf(map)));
            var documents = loader.documents();
            return new SchemaRegistry(documents, uri -> Optional.ofNullable(documents.get(uri)), Map.copyOf(resources), Map.copyOf(anchors),
                Collections.unmodifiableMap(frozenDynamic), defaultDialect);
        }
    }
}
