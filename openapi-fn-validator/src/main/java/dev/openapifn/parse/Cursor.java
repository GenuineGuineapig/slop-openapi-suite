package dev.openapifn.parse;

import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.Document;
import java.net.URI;

/** A position in a document during parsing. */
record Cursor(Document document, JsonPointer pointer, JsonValue value) {

    static Cursor root(Document document) {
        return new Cursor(document, JsonPointer.ROOT, document.root());
    }

    Cursor child(String token, JsonValue childValue) {
        return new Cursor(document, pointer.child(token), childValue);
    }

    URI location() {
        return URI.create(document.uri() + pointer.toUriFragment());
    }
}
