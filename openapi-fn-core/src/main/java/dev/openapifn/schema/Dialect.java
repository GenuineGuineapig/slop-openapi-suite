package dev.openapifn.schema;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The JSON Schema dialect a schema is interpreted with. Keywords a dialect does not have are
 * ignored (see {@link #foreignKeywords()}); the flags below capture keywords whose meaning differs.
 */
public enum Dialect {
    /** The OpenAPI 3.0 Schema Object (an extended subset of JSON Schema draft 4/5). */
    OAS_3_0(true, true, true, true, null),
    DRAFT_4(true, true, true, false, "id"),
    DRAFT_6(true, false, true, false, "$id"),
    DRAFT_7(true, false, true, false, "$id"),
    DRAFT_2019_09(false, false, true, false, "$id"),
    /** JSON Schema 2020-12, which is also the base of the OpenAPI 3.1 and 3.2 dialects. */
    DRAFT_2020_12(false, false, false, false, "$id");

    private final boolean refOverridesSiblings;
    private final boolean booleanExclusiveBounds;
    private final boolean arrayFormItems;
    private final boolean nullableKeyword;
    private final String idKeyword;

    Dialect(boolean refOverridesSiblings, boolean booleanExclusiveBounds, boolean arrayFormItems, boolean nullableKeyword, String idKeyword) {
        this.refOverridesSiblings = refOverridesSiblings;
        this.booleanExclusiveBounds = booleanExclusiveBounds;
        this.arrayFormItems = arrayFormItems;
        this.nullableKeyword = nullableKeyword;
        this.idKeyword = idKeyword;
    }

    /** Whether keywords next to {@code $ref} are ignored (draft 7 and earlier, OpenAPI 3.0). */
    public boolean refOverridesSiblings() {
        return refOverridesSiblings;
    }

    /** Whether {@code exclusiveMinimum}/{@code exclusiveMaximum} are booleans modifying {@code minimum}/{@code maximum}. */
    public boolean booleanExclusiveBounds() {
        return booleanExclusiveBounds;
    }

    /** Whether {@code items} may be an array of schemas (tuple validation) with {@code additionalItems}. */
    public boolean arrayFormItems() {
        return arrayFormItems;
    }

    /** Whether the OpenAPI 3.0 {@code nullable} keyword applies. */
    public boolean nullableKeyword() {
        return nullableKeyword;
    }

    /** The keyword that establishes a new base URI, if the dialect has one. */
    public Optional<String> idKeyword() {
        return Optional.ofNullable(idKeyword);
    }

    /**
     * Keywords other dialects evaluate that this one does not have. They are ignored, so that a
     * schema referenced across drafts is processed as its own draft. The OpenAPI 3.0 dialect
     * honours them all: descriptions often use them, although the 3.0 Schema Object lacks them.
     */
    public Set<String> foreignKeywords() {
        return switch (this) {
            case OAS_3_0 -> Set.of();
            case DRAFT_4 -> Keywords.FOREIGN_TO_4;
            case DRAFT_6 -> Keywords.FOREIGN_TO_6;
            case DRAFT_7 -> Keywords.FOREIGN_TO_7;
            case DRAFT_2019_09 -> Keywords.SINCE_2020_12;
            case DRAFT_2020_12 -> Keywords.UNTIL_2019_09;
        };
    }

    /** Evaluated keywords by the draft that introduced (or last had) them. */
    private static final class Keywords {
        static final Set<String> SINCE_6 = Set.of("const", "contains", "propertyNames");
        static final Set<String> SINCE_7 = Set.of("if", "then", "else", "contentEncoding", "contentMediaType");
        static final Set<String> SINCE_2019_09 = Set.of("$recursiveRef", "dependentRequired", "dependentSchemas", "unevaluatedProperties",
            "unevaluatedItems", "minContains", "maxContains", "contentSchema");
        static final Set<String> SINCE_2020_12 = Set.of("prefixItems", "$dynamicRef");
        static final Set<String> UNTIL_2019_09 = Set.of("$recursiveRef");
        static final Set<String> FOREIGN_TO_7 = union(SINCE_2019_09, SINCE_2020_12);
        static final Set<String> FOREIGN_TO_6 = union(SINCE_7, FOREIGN_TO_7);
        static final Set<String> FOREIGN_TO_4 = union(SINCE_6, FOREIGN_TO_6);

        private static Set<String> union(Set<String> first, Set<String> second) {
            var result = new HashSet<>(first);
            result.addAll(second);
            return Set.copyOf(result);
        }
    }

    /** Identifies a dialect from a {@code $schema} or {@code jsonSchemaDialect} URI. */
    public static Optional<Dialect> fromUri(String uri) {
        var normalized = uri.replaceFirst("^https?://", "").replaceFirst("#$", "");
        if (normalized.startsWith("spec.openapis.org/oas/3.0")) {
            return Optional.of(OAS_3_0);
        }
        if (normalized.startsWith("spec.openapis.org/oas/3.")) {
            return Optional.of(DRAFT_2020_12);
        }
        return Optional.ofNullable(switch (normalized) {
            case "json-schema.org/draft-04/schema" -> DRAFT_4;
            case "json-schema.org/draft-06/schema" -> DRAFT_6;
            case "json-schema.org/draft-07/schema" -> DRAFT_7;
            case "json-schema.org/draft/2019-09/schema" -> DRAFT_2019_09;
            case "json-schema.org/draft/2020-12/schema" -> DRAFT_2020_12;
            default -> null;
        });
    }
}
