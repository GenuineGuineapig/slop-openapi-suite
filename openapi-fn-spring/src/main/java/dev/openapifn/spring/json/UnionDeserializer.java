package dev.openapifn.spring.json;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deserializes a {@code oneOf}/{@code anyOf} without discriminator by trying each member type
 * strictly (unknown properties, missing required properties and scalar coercion are rejected).
 * For {@code anyOf} the first accepting member wins; for {@code oneOf} a unique match is preferred
 * and ambiguity is resolved in declaration order (schema validation reports true violations).
 *
 * @param <T> the union type
 */
public abstract class UnionDeserializer<T> extends ValueDeserializer<T> {

    /** Mixed into the members so that they use their own deserializers rather than inheriting the union's. */
    @JsonDeserialize(using = ValueDeserializer.None.class)
    private interface OwnDeserializer {
    }

    private final Class<T> type;
    private final boolean exactlyOne;
    private final List<Class<? extends T>> candidates;
    private final JsonMapper strict;

    protected UnionDeserializer(Class<T> type, boolean exactlyOne, List<Class<? extends T>> candidates) {
        this.type = type;
        this.exactlyOne = exactlyOne;
        this.candidates = List.copyOf(candidates);
        var builder = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        candidates.forEach(candidate -> builder.addMixIn(candidate, OwnDeserializer.class));
        this.strict = builder.build();
    }

    @Override
    public T deserialize(JsonParser parser, DeserializationContext context) {
        var node = context.readTree(parser);
        var matches = new ArrayList<T>();
        for (var candidate : candidates) {
            try {
                matches.add(strict.treeToValue(node, candidate));
            } catch (JacksonException e) {
                continue;
            }
            if (!exactlyOne) {
                break;
            }
        }
        if (matches.isEmpty()) {
            throw MismatchedInputException.from(parser, type, "JSON matches none of the types of " + type.getSimpleName());
        }
        return matches.getFirst();
    }
}
