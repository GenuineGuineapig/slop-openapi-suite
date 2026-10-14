package dev.openapifn.http;

import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/**
 * Swagger 2.0 collection formats that OpenAPI 3 styles cannot express ({@code tsv}, and formats
 * other than {@code csv} outside the query). The conversion keeps them as the
 * {@code x-collection-format} extension of parameters, headers and encodings, and parameters are
 * parsed and serialized with the delimiter it names.
 */
public final class CollectionFormat {

    /** The extension naming the collection format: {@code csv}, {@code ssv}, {@code tsv} or {@code pipes}. */
    public static final String EXTENSION = "x-collection-format";

    private CollectionFormat() {
    }

    /** The delimiter of array items that the extension among {@code extensions} names, if any. */
    public static Optional<String> delimiter(Map<String, JsonValue> extensions) {
        return Optional.ofNullable(extensions.get(EXTENSION)).flatMap(JsonValue::asString).flatMap(CollectionFormat::delimiter);
    }

    /** The delimiter of a collection format. */
    public static Optional<String> delimiter(String format) {
        return switch (format) {
            case "csv" -> Optional.of(",");
            case "ssv" -> Optional.of(" ");
            case "tsv" -> Optional.of("\t");
            case "pipes" -> Optional.of("|");
            default -> Optional.empty();
        };
    }
}
