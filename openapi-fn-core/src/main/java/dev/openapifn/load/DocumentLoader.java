package dev.openapifn.load;

import dev.openapifn.fn.Result;
import dev.openapifn.json.JsonPointer;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and caches documents by URI. Instances are not thread-safe; they are used for the
 * duration of a single parse and then frozen into an immutable snapshot via {@link #documents()}.
 */
public final class DocumentLoader {

    private final ResourceLoader resources;
    private final Map<URI, Result<Document, Diagnostic>> cache = new LinkedHashMap<>();

    public DocumentLoader(ResourceLoader resources) {
        this.resources = resources;
    }

    /** Adds an already parsed document (for example one supplied as a string). */
    public void register(Document document) {
        cache.put(document.uri(), Result.ok(document));
        alias(document);
    }

    /** Loads (or returns the cached) document at {@code uri}; any fragment is ignored. */
    public Result<Document, Diagnostic> load(URI uri) {
        var key = Uris.withoutFragment(uri).normalize();
        var cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        var loaded = fetch(key);
        cache.put(key, loaded);
        loaded.peek(this::alias);
        return loaded;
    }

    /** The already loaded document at {@code uri}, without attempting to load it. */
    public Optional<Document> loaded(URI uri) {
        return Optional.ofNullable(cache.get(Uris.withoutFragment(uri).normalize())).flatMap(Result::toOptional);
    }

    /** Makes a document with a {@code $self} URI reachable under that URI as well. */
    private void alias(Document document) {
        var base = document.baseUri();
        if (!base.equals(document.uri())) {
            cache.putIfAbsent(base, Result.ok(document));
        }
    }

    private Result<Document, Diagnostic> fetch(URI uri) {
        return resources.load(uri)
            .mapError(message -> Diagnostic.error(message, uri, JsonPointer.ROOT))
            .flatMap(content -> DocumentParser.parse(uri, content));
    }

    /** All successfully loaded documents keyed by retrieval URI and {@code $self} URI, in load order. */
    public Map<URI, Document> documents() {
        var documents = new LinkedHashMap<URI, Document>();
        cache.forEach((uri, result) -> result.peek(document -> documents.put(uri, document)));
        return Collections.unmodifiableMap(documents);
    }
}
