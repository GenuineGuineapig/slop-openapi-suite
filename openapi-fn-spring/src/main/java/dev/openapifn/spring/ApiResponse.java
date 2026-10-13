package dev.openapifn.spring;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A response of an operation. Generated code implements this with one record per described
 * status code, so handlers return typed, exhaustive results that the runtime maps to HTTP.
 */
public interface ApiResponse {

    /** The HTTP status code. */
    int status();

    /** The body, or {@code null} for no content. */
    default @Nullable Object body() {
        return null;
    }

    /** Response header values by header name; {@code null} values are omitted. */
    default Map<String, @Nullable Object> headers() {
        return Map.of();
    }

    /** The media type to respond with; {@code null} negotiates among the described ones. */
    default @Nullable String contentType() {
        return null;
    }
}
