package dev.openapifn.load;

import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonValue;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * A loaded source document.
 *
 * @param uri absolute URI without fragment
 * @param root parsed content
 * @param sourceMap positions of the nodes in the source text
 */
public record Document(URI uri, JsonValue root, SourceMap sourceMap) {

    public Document {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(sourceMap, "sourceMap");
    }

    /**
     * The base URI for resolving references in this document: its {@code $self} (OpenAPI 3.2)
     * resolved against the retrieval URI, or else the retrieval URI.
     */
    public URI baseUri() {
        return root.asObject().flatMap(object -> object.string("$self"))
            .flatMap(self -> Uris.resolve(uri, self).toOptional())
            .map(Uris::withoutFragment)
            .orElse(uri);
    }

    public Optional<JsonValue> resolve(JsonPointer pointer) {
        return pointer.resolve(root);
    }

    public Optional<Position> position(JsonPointer pointer) {
        return sourceMap.position(pointer);
    }
}
