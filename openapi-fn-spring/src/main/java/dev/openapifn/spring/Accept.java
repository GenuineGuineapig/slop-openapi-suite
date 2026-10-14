package dev.openapifn.spring;

import dev.openapifn.http.ContentType;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The media types a client accepts: the {@code Accept} header. Request records carry it for
 * operations whose responses offer media types of different Java types, so that implementations
 * can answer with the one the client wants (and clients can ask for one).
 *
 * @param ranges the media ranges, in the order given
 */
public record Accept(List<String> ranges) {

    public Accept {
        ranges = List.copyOf(ranges);
    }

    public static Accept of(String... ranges) {
        return new Accept(List.of(ranges));
    }

    /** The ranges of an {@code Accept} header; {@code null} (no header) accepts everything. */
    public static Accept parse(@Nullable String header) {
        return new Accept(header == null ? List.of() : Arrays.stream(header.split(",")).map(String::trim).filter(range -> !range.isEmpty()).toList());
    }

    /** Whether {@code mediaType} is acceptable: no ranges accept everything, ranges with {@code q=0} accept nothing. */
    public boolean accepts(String mediaType) {
        var type = ContentType.parse(mediaType);
        return ranges.isEmpty() || type.isPresent() && ranges.stream().flatMap(range -> ContentType.parse(range).stream())
            .filter(range -> range.parameter("q").filter(q -> q.matches("0(\\.0*)?")).isEmpty())
            .anyMatch(range -> range.includes(type.get()));
    }

    @Override
    public String toString() {
        return String.join(", ", ranges);
    }
}
