package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/**
 * The Response Object.
 *
 * @param summary short summary (3.2)
 * @param description description (optional since 3.2)
 * @param headers response headers keyed by name
 * @param content response content keyed by media type range
 * @param links design-time links
 * @param extensions specification extensions
 */
public record Response(Optional<String> summary, Optional<String> description, Map<String, Header> headers, Map<String, MediaType> content,
                       Map<String, Link> links, Map<String, JsonValue> extensions) {

    public Response {
        headers = Immutable.map(headers);
        content = Immutable.map(content);
        links = Immutable.map(links);
        extensions = Immutable.map(extensions);
    }
}
