package dev.openapifn.schema;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The standard vocabularies of JSON Schema 2019-09 and 2020-12 and the keywords they define. A
 * custom metaschema's {@code $vocabulary} enables some of them; the keywords of the others are
 * then ignored. The core vocabulary always applies, and keywords of no standard vocabulary (such
 * as OpenAPI's) are kept.
 */
final class Vocabularies {

    /** The 2020-12 vocabulary under which {@code format} is an assertion. */
    static final String FORMAT_ASSERTION = "https://json-schema.org/draft/2020-12/vocab/format-assertion";

    private static final String V2020 = "https://json-schema.org/draft/2020-12/vocab/";
    private static final String V2019 = "https://json-schema.org/draft/2019-09/vocab/";
    private static final Set<String> VALIDATION = Set.of("type", "const", "enum", "multipleOf", "maximum", "exclusiveMaximum", "minimum",
        "exclusiveMinimum", "maxLength", "minLength", "pattern", "maxItems", "minItems", "uniqueItems", "maxContains", "minContains",
        "maxProperties", "minProperties", "required", "dependentRequired");
    private static final Set<String> META_DATA = Set.of("title", "description", "default", "deprecated", "readOnly", "writeOnly", "examples");
    private static final Set<String> CONTENT = Set.of("contentEncoding", "contentMediaType", "contentSchema");
    private static final Set<String> APPLICATOR = Set.of("items", "contains", "additionalProperties", "properties", "patternProperties",
        "dependentSchemas", "propertyNames", "if", "then", "else", "allOf", "anyOf", "oneOf", "not");

    private static final Map<String, Set<String>> KEYWORDS = Map.ofEntries(
        Map.entry(V2020 + "core", Set.of()),
        Map.entry(V2020 + "applicator", with(APPLICATOR, "prefixItems")),
        Map.entry(V2020 + "unevaluated", Set.of("unevaluatedItems", "unevaluatedProperties")),
        Map.entry(V2020 + "validation", VALIDATION),
        Map.entry(V2020 + "meta-data", META_DATA),
        Map.entry(V2020 + "format-annotation", Set.of("format")),
        Map.entry(FORMAT_ASSERTION, Set.of("format")),
        Map.entry(V2020 + "content", CONTENT),
        Map.entry(V2019 + "core", Set.of()),
        Map.entry(V2019 + "applicator", with(APPLICATOR, "additionalItems", "unevaluatedItems", "unevaluatedProperties")),
        Map.entry(V2019 + "validation", VALIDATION),
        Map.entry(V2019 + "meta-data", META_DATA),
        Map.entry(V2019 + "format", Set.of("format")),
        Map.entry(V2019 + "content", CONTENT));

    private static final Set<String> ALL = KEYWORDS.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Map<Set<String>, Set<String>> INACTIVE = new ConcurrentHashMap<>();

    private Vocabularies() {
    }

    private static Set<String> with(Set<String> base, String... more) {
        var result = new HashSet<>(base);
        result.addAll(Set.of(more));
        return Set.copyOf(result);
    }

    /** Whether {@code uri} names a standard vocabulary. */
    static boolean isKnown(String uri) {
        return KEYWORDS.containsKey(uri);
    }

    /** The keywords of standard vocabularies that none of the {@code active} vocabularies defines. */
    static Set<String> inactiveKeywords(Set<String> active) {
        return INACTIVE.computeIfAbsent(active, vocabularies -> {
            var inactive = new HashSet<>(ALL);
            vocabularies.forEach(uri -> inactive.removeAll(KEYWORDS.getOrDefault(uri, Set.of())));
            return Set.copyOf(inactive);
        });
    }
}
