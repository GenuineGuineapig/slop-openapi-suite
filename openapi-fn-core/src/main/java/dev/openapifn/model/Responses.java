package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/**
 * The Responses Object.
 *
 * @param byStatus responses keyed by status code ({@code "200"}) or range ({@code "2XX"})
 * @param defaultResponse the {@code default} response
 * @param extensions specification extensions
 */
public record Responses(Map<String, Response> byStatus, Optional<Response> defaultResponse, Map<String, JsonValue> extensions) {

    public static final Responses EMPTY = new Responses(Map.of(), Optional.empty(), Map.of());

    public Responses {
        byStatus = Immutable.map(byStatus);
        extensions = Immutable.map(extensions);
    }

    /** Looks up the response for {@code status}: exact code first, then the {@code NXX} range, then {@code default}. */
    public Optional<Response> forStatus(int status) {
        var exact = byStatus.get(Integer.toString(status));
        if (exact != null) {
            return Optional.of(exact);
        }
        var range = byStatus.get((status / 100) + "XX");
        return range != null ? Optional.of(range) : defaultResponse;
    }
}
