package dev.openapifn.load;

import dev.openapifn.json.JsonPointer;
import java.util.Map;
import java.util.Optional;

/** Maps JSON pointers within a document to the source positions of the corresponding nodes. */
public record SourceMap(Map<JsonPointer, Position> positions) {

    public static final SourceMap EMPTY = new SourceMap(Map.of());

    public SourceMap {
        positions = Map.copyOf(positions);
    }

    /** The position of {@code pointer}, or of its nearest ancestor that has one. */
    public Optional<Position> position(JsonPointer pointer) {
        for (var current = Optional.of(pointer); current.isPresent(); current = current.get().parent()) {
            var position = positions.get(current.get());
            if (position != null) {
                return Optional.of(position);
            }
        }
        return Optional.empty();
    }
}
