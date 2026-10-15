package dev.openapifn.parse;

import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.Uris;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Makes a Swagger 2.0 description self-contained, so that it can be converted as one document.
 * References into other documents are resolved by what they point at:
 * <ul>
 *   <li>schemas are copied into {@code definitions} (once each, under a unique name derived from the
 *       reference) and referenced there</li>
 *   <li>parameters, responses and path items are inlined</li>
 * </ul>
 * References inside copied or inlined objects are resolved against the document they come from, so
 * documents may reference each other (and back into the root). References that cannot be resolved
 * are kept, so that loading reports them.
 */
final class Swagger2Bundler {

    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch");
    private static final Set<String> DATA_KEYWORDS = Set.of("example", "examples", "default", "enum", "x-example");

    private final URI root;
    private final Function<URI, Optional<JsonValue>> documents;
    private final Map<String, JsonValue> definitions = new LinkedHashMap<>();
    private final Map<URI, String> copied = new HashMap<>();
    private final Set<URI> inlining = new HashSet<>();

    private Swagger2Bundler(URI root, Function<URI, Optional<JsonValue>> documents) {
        this.root = Uris.withoutFragment(root).normalize();
        this.documents = documents;
    }

    /**
     * @param documents the content of a document by URI (without fragment)
     */
    static JsonObject bundle(JsonObject source, URI root, Function<URI, Optional<JsonValue>> documents) {
        return new Swagger2Bundler(root, documents).bundle(source);
    }

    private JsonObject bundle(JsonObject source) {
        source.object("definitions").ifPresent(own -> definitions.putAll(own.members()));
        var out = new LinkedHashMap<>(source.members());
        source.object("paths").ifPresent(paths -> out.put("paths", map(paths, (path, item) -> path.startsWith("x-") ? item : pathItem(item, root))));
        source.object("parameters").ifPresent(parameters -> out.put("parameters", map(parameters, (name, parameter) -> parameter(parameter, root))));
        source.object("responses").ifPresent(responses -> out.put("responses", map(responses, (name, response) -> response(response, root))));
        // The root's own definitions first (their references may add more), then everything copied from other documents.
        for (var name : List.copyOf(definitions.keySet())) {
            definitions.put(name, schema(definitions.get(name), root));
        }
        if (!definitions.isEmpty()) {
            out.put("definitions", new JsonObject(definitions));
        }
        return new JsonObject(out);
    }

    // ---------------------------------------------------------------------------------------------
    // Objects that are inlined

    private JsonValue pathItem(JsonValue value, URI base) {
        var item = value.asObject().orElse(JsonObject.EMPTY);
        var out = new LinkedHashMap<String, JsonValue>();
        var target = item.string("$ref").flatMap(ref -> external(ref, base));
        target.ifPresentOrElse(found -> out.putAll(inlined(found, () -> pathItem(found.value(), found.base())).asObject().orElseThrow().members()),
            () -> item.get("$ref").ifPresent(ref -> out.put("$ref", ref)));
        item.members().forEach((key, member) -> {
            if (key.equals("parameters")) {
                out.put(key, list(member, parameter -> parameter(parameter, base)));
            } else if (METHODS.contains(key)) {
                out.put(key, operation(member.asObject().orElse(JsonObject.EMPTY), base));
            } else if (!key.equals("$ref")) {
                out.put(key, member);
            }
        });
        return new JsonObject(out);
    }

    private JsonValue operation(JsonObject operation, URI base) {
        var out = new LinkedHashMap<>(operation.members());
        operation.get("parameters").ifPresent(parameters -> out.put("parameters", list(parameters, parameter -> parameter(parameter, base))));
        operation.object("responses").ifPresent(responses -> out.put("responses", map(responses, (status, response) -> status.startsWith("x-")
            ? response : response(response, base))));
        return new JsonObject(out);
    }

    private JsonValue parameter(JsonValue value, URI base) {
        var parameter = value.asObject().orElse(JsonObject.EMPTY);
        var target = parameter.string("$ref").flatMap(ref -> external(ref, base));
        if (target.isPresent()) {
            return inlined(target.get(), () -> parameter(target.get().value(), target.get().base()));
        }
        if (parameter.has("$ref")) {
            return localized(parameter, base);
        }
        var out = new LinkedHashMap<>(parameter.members());
        parameter.get("schema").ifPresent(schema -> out.put("schema", schema(schema, base)));
        return new JsonObject(out);
    }

    private JsonValue response(JsonValue value, URI base) {
        var response = value.asObject().orElse(JsonObject.EMPTY);
        var target = response.string("$ref").flatMap(ref -> external(ref, base));
        if (target.isPresent()) {
            return inlined(target.get(), () -> response(target.get().value(), target.get().base()));
        }
        if (response.has("$ref")) {
            return localized(response, base);
        }
        var out = new LinkedHashMap<>(response.members());
        response.get("schema").ifPresent(schema -> out.put("schema", schema(schema, base)));
        return new JsonObject(out);
    }

    /** A reference into the root kept as a local reference (it may come from another document). */
    private JsonObject localized(JsonObject reference, URI base) {
        var ref = reference.string("$ref").orElseThrow();
        return reference.with("$ref", JsonString.of(absolute(ref, base).filter(this::inRoot).map(uri -> "#" + Uris.fragment(uri)).orElse(ref)));
    }

    // ---------------------------------------------------------------------------------------------
    // Schemas, which are copied into definitions

    private JsonValue schema(JsonValue value, URI base) {
        return switch (value) {
            case JsonObject object -> {
                var out = new LinkedHashMap<String, JsonValue>();
                object.members().forEach((key, member) -> out.put(key, key.equals("$ref") ? schemaReference(member, base)
                    : DATA_KEYWORDS.contains(key) || key.startsWith("x-") ? member : schema(member, base)));
                yield new JsonObject(out);
            }
            case JsonArray array -> new JsonArray(array.values().stream().map(item -> schema(item, base)).toList());
            default -> value;
        };
    }

    /** The local reference of a schema: into the root as it is, from elsewhere to its copy in {@code definitions}. */
    private JsonValue schemaReference(JsonValue ref, URI base) {
        var target = ref.asString().flatMap(text -> absolute(text, base));
        if (target.isEmpty()) {
            return ref;
        }
        if (inRoot(target.get())) {
            return JsonString.of("#" + Uris.fragment(target.get()));
        }
        return copy(target.get()).<JsonValue>map(name -> JsonString.of(JsonPointer.of("definitions", name).toUriFragment())).orElse(ref);
    }

    /** The name of the copy of the schema at {@code target}, copying it (and what it references) the first time. */
    private Optional<String> copy(URI target) {
        var existing = copied.get(target);
        if (existing != null) {
            return Optional.of(existing);
        }
        var schema = resolve(target);
        if (schema.isEmpty()) {
            return Optional.empty();
        }
        var name = unique(nameOf(target));
        copied.put(target, name);
        definitions.put(name, JsonObject.EMPTY);
        definitions.put(name, schema(withoutDocumentKeywords(schema.get()), Uris.withoutFragment(target)));
        return Optional.of(name);
    }

    /**
     * A schema document without the keywords of the document rather than of the schema
     * ({@code definitions}, {@code $schema}, {@code id}), which a Schema Object does not have:
     * references into its {@code definitions} are copied like any other.
     */
    private static JsonValue withoutDocumentKeywords(JsonValue schema) {
        return schema.asObject().<JsonValue>map(object -> object.without("definitions").without("$schema").without("id")).orElse(schema);
    }

    /** The last token of the pointer, else the file name without extension. */
    private static String nameOf(URI target) {
        var pointer = JsonPointer.parse(Uris.fragment(target)).toOptional().flatMap(JsonPointer::last);
        return pointer.orElseGet(() -> {
            var path = Uris.withoutFragment(target).getPath();
            var file = path.substring(path.lastIndexOf('/') + 1);
            return file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
        });
    }

    private String unique(String name) {
        var candidate = name;
        for (int i = 2; definitions.containsKey(candidate); i++) {
            candidate = name + i;
        }
        return candidate;
    }

    // ---------------------------------------------------------------------------------------------
    // Resolution

    private record Target(JsonValue value, URI base, URI uri) {
    }

    /** Inlines an object, unless it is being inlined already (a reference cycle, kept as an empty object). */
    private JsonValue inlined(Target target, Supplier<JsonValue> inline) {
        if (!inlining.add(target.uri())) {
            return JsonObject.EMPTY;
        }
        try {
            return inline.get();
        } finally {
            inlining.remove(target.uri());
        }
    }

    /** The object a reference into another document points at, with that document as its base. */
    private Optional<Target> external(String ref, URI base) {
        return absolute(ref, base).filter(uri -> !inRoot(uri))
            .flatMap(uri -> resolve(uri).map(value -> new Target(value, Uris.withoutFragment(uri), uri)));
    }

    private Optional<JsonValue> resolve(URI target) {
        return documents.apply(Uris.withoutFragment(target))
            .flatMap(document -> JsonPointer.parse(Uris.fragment(target)).toOptional().flatMap(pointer -> pointer.resolve(document)));
    }

    private static Optional<URI> absolute(String ref, URI base) {
        return Uris.resolve(base, ref).toOptional();
    }

    private boolean inRoot(URI uri) {
        return Uris.withoutFragment(uri).normalize().equals(root);
    }

    private static JsonObject map(JsonObject source, BiFunction<String, JsonValue, JsonValue> convert) {
        var out = new LinkedHashMap<String, JsonValue>();
        source.members().forEach((key, value) -> out.put(key, convert.apply(key, value)));
        return new JsonObject(out);
    }

    private static JsonValue list(JsonValue source, Function<JsonValue, JsonValue> convert) {
        return source.asArray().<JsonValue>map(array -> new JsonArray(array.values().stream().map(convert).toList())).orElse(source);
    }
}
