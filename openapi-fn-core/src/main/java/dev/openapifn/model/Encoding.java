package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import dev.openapifn.schema.Schema;

/**
 * The Encoding Object.
 *
 * @param contentType content type of the part or property
 * @param headers part headers (multipart only)
 * @param style serialization style (form-urlencoded only)
 * @param explode explode flag (form-urlencoded only)
 * @param allowReserved whether reserved characters are allowed (form-urlencoded only)
 * @param encoding nested encodings (3.2)
 * @param prefixEncoding nested positional encodings (3.2)
 * @param itemEncoding nested item encoding (3.2)
 * @param extensions specification extensions
 */
public record Encoding(Optional<String> contentType, Map<String, Header> headers, Optional<ParameterStyle> style, Optional<Boolean> explode,
                       boolean allowReserved, Map<String, Encoding> encoding, List<Encoding> prefixEncoding, Optional<Encoding> itemEncoding,
                       Map<String, JsonValue> extensions) {

    public Encoding {
        headers = Immutable.map(headers);
        encoding = Immutable.map(encoding);
        prefixEncoding = List.copyOf(prefixEncoding);
        extensions = Immutable.map(extensions);
    }

    /**
     * Whether it says how a form property is serialized: by {@code style}, {@code explode}, or a
     * Swagger 2.0 collection format. Other properties are sent as repeated names.
     */
    public boolean serializes() {
        return style.isPresent() || explode.isPresent() || extensions.containsKey("x-collection-format");
    }

    /**
     * A property of an {@code application/x-www-form-urlencoded} body as the query parameter it is
     * serialized like: {@code form} style by default, exploded when the style is {@code form} (unless
     * a collection format joins the items).
     */
    public Parameter asParameter(String property, Optional<Schema> schema) {
        var parameterStyle = style.orElse(ParameterStyle.FORM);
        var delimited = extensions.containsKey("x-collection-format");
        return new Parameter(property, ParameterLocation.QUERY, Optional.empty(), false, false, false, parameterStyle,
            explode.orElse(parameterStyle == ParameterStyle.FORM && !delimited), allowReserved, schema, Optional.empty(), Map.of(), Map.of(), extensions);
    }
}
