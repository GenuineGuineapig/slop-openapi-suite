package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import dev.openapifn.schema.Schema;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Media Type Object.
 *
 * @param schema schema of the whole content
 * @param itemSchema schema of each item of a sequential media type such as {@code application/jsonl} (3.2)
 * @param example example value
 * @param examples named examples
 * @param encoding per-property encodings for form and multipart content
 * @param prefixEncoding positional encodings for {@code multipart/mixed} (3.2)
 * @param itemEncoding encoding of each item for {@code multipart/mixed} (3.2)
 * @param extensions specification extensions
 */
public record MediaType(Optional<Schema> schema, Optional<Schema> itemSchema, Optional<JsonValue> example, Map<String, Example> examples,
                        Map<String, Encoding> encoding, List<Encoding> prefixEncoding, Optional<Encoding> itemEncoding,
                        Map<String, JsonValue> extensions) {

    public static final MediaType EMPTY = new MediaType(Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Map.of(), List.of(),
        Optional.empty(), Map.of());

    public MediaType {
        examples = Immutable.map(examples);
        encoding = Immutable.map(encoding);
        prefixEncoding = List.copyOf(prefixEncoding);
        extensions = Immutable.map(extensions);
    }
}
