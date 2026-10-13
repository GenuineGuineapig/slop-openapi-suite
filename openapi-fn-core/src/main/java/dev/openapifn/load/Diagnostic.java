package dev.openapifn.load;

import dev.openapifn.json.JsonPointer;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * A problem found while loading or interpreting a document.
 *
 * @param severity how serious the problem is
 * @param message human readable description
 * @param document the document the problem was found in
 * @param pointer location within the document
 * @param position source position, if known
 */
public record Diagnostic(Severity severity, String message, URI document, JsonPointer pointer, Optional<Position> position) {

    /** Diagnostic severities. */
    public enum Severity {
        ERROR,
        WARNING
    }

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(position, "position");
    }

    public static Diagnostic error(String message, URI document, JsonPointer pointer) {
        return new Diagnostic(Severity.ERROR, message, document, pointer, Optional.empty());
    }

    public static Diagnostic warning(String message, URI document, JsonPointer pointer) {
        return new Diagnostic(Severity.WARNING, message, document, pointer, Optional.empty());
    }

    public Diagnostic withPosition(Optional<Position> newPosition) {
        return new Diagnostic(severity, message, document, pointer, newPosition);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return severity + " " + document + pointer.toUriFragment() + position.map(p -> " (" + p + ")").orElse("") + ": " + message;
    }
}
