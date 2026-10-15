package dev.openapifn.schema;

import dev.openapifn.fn.Result;
import dev.openapifn.json.Json;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonNull;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.Uris;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Validates JSON instances against schemas. Supports JSON Schema draft 4 through 2020-12 and the
 * OpenAPI 3.0 Schema Object, including {@code unevaluatedProperties}/{@code unevaluatedItems},
 * {@code $dynamicRef}, {@code $recursiveRef}, format assertion, OpenAPI {@code nullable} and
 * direction-aware {@code readOnly}/{@code writeOnly}. A {@code discriminator} does not change the
 * outcome, as the OpenAPI Specification requires; it only explains a failed {@code oneOf} or
 * {@code anyOf} by the member its value selects.
 *
 * <p>Each schema's keywords are read once into a {@link Plan}, which later evaluations of the
 * schema use instead of its JSON. Instances are immutable (but for that cache) and thread-safe.
 */
public final class SchemaValidator {

    /**
     * Validation options.
     *
     * @param assertFormats whether {@code format} is asserted (rather than treated as an annotation)
     * @param assertContent whether {@code contentEncoding}/{@code contentMediaType}/{@code contentSchema} are asserted
     * @param formats format validators keyed by format name
     */
    public record Options(boolean assertFormats, boolean assertContent, Map<String, FormatValidator> formats) {

        public static final Options DEFAULT = new Options(true, false, Formats.standard());

        public Options {
            formats = Map.copyOf(formats);
        }

        public Options withFormat(String name, FormatValidator validator) {
            var copy = new HashMap<>(formats);
            copy.put(name, validator);
            return new Options(assertFormats, assertContent, copy);
        }

        public Options withAssertFormats(boolean value) {
            return new Options(value, assertContent, formats);
        }

        public Options withAssertContent(boolean value) {
            return new Options(assertFormats, value, formats);
        }
    }

    private final SchemaRegistry registry;
    private final Options options;
    private final Map<PlanKey, Plan> plans = new ConcurrentHashMap<>();
    private final Map<Restriction, Set<String>> restricted = new ConcurrentHashMap<>();
    private final Map<KeywordAt, URI> keywordLocations = new ConcurrentHashMap<>();

    /** A keyword of a schema, whose location errors report (see {@link Run#keywordLocation}). */
    private record KeywordAt(Schema.Key schema, String keyword) {
    }

    public SchemaValidator(SchemaRegistry registry, Options options) {
        this.registry = registry;
        this.options = options;
    }

    public SchemaValidator(SchemaRegistry registry) {
        this(registry, Options.DEFAULT);
    }

    public SchemaRegistry registry() {
        return registry;
    }

    public Options options() {
        return options;
    }

    /** Validates {@code instance} without request/response semantics. */
    public List<SchemaError> validate(Schema schema, JsonValue instance) {
        return validate(schema, instance, Direction.NONE);
    }

    /** Validates {@code instance}, enforcing {@code readOnly}/{@code writeOnly} for {@code direction}. */
    public List<SchemaError> validate(Schema schema, JsonValue instance, Direction direction) {
        return new Run(direction).evaluate(schema, instance, JsonPointer.ROOT).errors();
    }

    // ---------------------------------------------------------------------------------------------
    // Plans: the keywords of a schema, read once

    /** What a plan is kept by: a schema's location with what its meaning there depends on. */
    private record PlanKey(Schema.Key schema, URI baseUri, Dialect dialect) {
    }

    /**
     * A schema's keywords as evaluating it needs them: subschemas made, patterns compiled, bounds
     * read. The groups of type-specific keywords are present only when the schema has one of them.
     *
     * @param schema the schema without the keywords its dialect or vocabularies do not have
     * @param nullable whether {@code null} is valid outright (OpenAPI 3.0 {@code nullable})
     * @param enumValues the {@code enum} values, when the keyword is present
     * @param format the validator of an asserted {@code format}
     */
    private record Plan(Schema schema, boolean nullable, Optional<String> ref, Optional<String> dynamicRef, Optional<String> recursiveRef,
                        Set<String> types, Optional<List<JsonValue>> enumValues, Optional<JsonValue> constValue, Optional<FormatValidator> format,
                        Optional<NumberKeywords> numbers, Optional<StringKeywords> strings, Optional<ArrayKeywords> arrays,
                        Optional<ObjectKeywords> objects, Combinators combinators) {
    }

    /** A {@code maximum} or {@code minimum}, exclusive by the boolean {@code exclusiveMaximum}/{@code exclusiveMinimum} of draft 4. */
    private record Bound(BigDecimal value, boolean exclusive) {
    }

    private record NumberKeywords(Optional<BigDecimal> multipleOf, Optional<Bound> maximum, Optional<Bound> minimum, Optional<BigDecimal> exclusiveMaximum,
                                  Optional<BigDecimal> exclusiveMinimum) {
    }

    /** A {@code pattern}, as written and compiled (or why it does not compile). */
    private record Regex(String source, Result<Pattern, String> compiled) {

        static Regex of(String source) {
            return new Regex(source, EcmaRegex.compile(source));
        }

        boolean finds(String text) {
            return compiled.toOptional().filter(pattern -> pattern.matcher(text).find()).isPresent();
        }

        /** Why {@code text} fails the pattern: it does not compile, or finds no match. */
        Optional<String> mismatch(String text) {
            return compiled.fold(pattern -> pattern.matcher(text).find() ? Optional.<String>empty() : Optional.of("must match pattern " + source),
                Optional::of);
        }
    }

    /** {@code contentEncoding}, {@code contentMediaType} and {@code contentSchema}, when asserted. */
    private record Content(boolean base64, boolean json, Optional<Schema> schema) {
    }

    private record StringKeywords(Optional<Integer> maxLength, Optional<Integer> minLength, Optional<Regex> pattern, Optional<Content> content) {
    }

    private record Contains(Schema schema, int min, Optional<Integer> max) {
    }

    /**
     * Array keywords.
     *
     * @param tuple the schemas of the leading items ({@code prefixItems}, or the array form of {@code items})
     * @param rest the schema of the other items ({@code items}, or {@code additionalItems})
     */
    private record ArrayKeywords(Optional<Integer> maxItems, Optional<Integer> minItems, boolean uniqueItems, List<Schema> tuple, Optional<Schema> rest,
                                 Optional<Contains> contains, Optional<Schema> unevaluatedItems) {
    }

    /** A property's schema, and whether it is read-only or write-only once dereferenced. */
    private record Property(Schema schema, boolean readOnly, boolean writeOnly) {
    }

    private record PatternProperty(Regex pattern, Schema schema) {
    }

    private record ObjectKeywords(Optional<Integer> maxProperties, Optional<Integer> minProperties, List<String> required,
                                  Map<String, List<String>> dependentRequired, Map<String, Property> properties, List<PatternProperty> patternProperties,
                                  Optional<Schema> additionalProperties, Optional<Schema> propertyNames, Map<String, Schema> dependentSchemas,
                                  Optional<Schema> unevaluatedProperties) {
    }

    private record Combinators(List<Schema> allOf, List<Schema> anyOf, List<Schema> oneOf, Optional<Schema> not, Optional<Schema> condition,
                               Optional<Schema> then, Optional<Schema> otherwise) {
    }

    private static final Set<String> NUMBER_KEYWORDS = Set.of("multipleOf", "maximum", "minimum", "exclusiveMaximum", "exclusiveMinimum");
    private static final Set<String> STRING_KEYWORDS = Set.of("maxLength", "minLength", "pattern", "contentEncoding", "contentMediaType");
    private static final Set<String> ARRAY_KEYWORDS = Set.of("maxItems", "minItems", "uniqueItems", "items", "prefixItems", "additionalItems", "contains",
        "unevaluatedItems");
    private static final Set<String> OBJECT_KEYWORDS = Set.of("maxProperties", "minProperties", "required", "dependentRequired", "dependencies",
        "properties", "patternProperties", "additionalProperties", "propertyNames", "dependentSchemas", "unevaluatedProperties");

    /** The plan of a schema that is not a boolean schema. */
    private Plan plan(Schema schema) {
        return plans.computeIfAbsent(new PlanKey(schema.key(), schema.baseUri(), schema.dialect()), key -> planOf(effective(schema)));
    }

    /**
     * {@code schema} without the keywords its dialect does not have and, under a custom metaschema,
     * those of the vocabularies it does not enable.
     */
    private Schema effective(Schema schema) {
        var foreign = schema.dialect().foreignKeywords();
        var inactive = registry.vocabularies(schema).map(Vocabularies::inactiveKeywords).orElse(Set.of());
        var object = schema.object();
        if (object.keys().stream().noneMatch(keyword -> foreign.contains(keyword) || inactive.contains(keyword))) {
            return schema;
        }
        var kept = new LinkedHashMap<>(object.members());
        kept.keySet().removeIf(keyword -> foreign.contains(keyword) || inactive.contains(keyword));
        return new Schema(new JsonObject(kept), schema.document(), schema.pointer(), schema.baseUri(), schema.dialect());
    }

    private Plan planOf(Schema schema) {
        var object = schema.object();
        var dialect = schema.dialect();
        var asserted = options.assertFormats() || registry.vocabularies(schema).filter(v -> v.contains(Vocabularies.FORMAT_ASSERTION)).isPresent();
        return new Plan(schema, dialect.nullableKeyword() && object.bool("nullable").orElse(false), schema.ref(), object.string("$dynamicRef"),
            object.string("$recursiveRef"), Set.copyOf(schema.types()), object.has("enum") ? Optional.of(schema.enumValues()) : Optional.empty(),
            schema.constValue(), schema.format().filter(format -> asserted).flatMap(format -> formatValidator(format, dialect)),
            any(object, NUMBER_KEYWORDS) ? Optional.of(numbers(schema)) : Optional.empty(),
            any(object, STRING_KEYWORDS) ? Optional.of(strings(schema)) : Optional.empty(),
            any(object, ARRAY_KEYWORDS) ? Optional.of(arrays(schema)) : Optional.empty(),
            any(object, OBJECT_KEYWORDS) ? Optional.of(objects(schema)) : Optional.empty(),
            new Combinators(schema.subschemaList("allOf"), schema.subschemaList("anyOf"), schema.subschemaList("oneOf"), schema.subschema("not"),
                schema.subschema("if"), schema.subschema("then"), schema.subschema("else")));
    }

    private static boolean any(JsonObject object, Set<String> keywords) {
        return object.keys().stream().anyMatch(keywords::contains);
    }

    /** Draft 4 and 6 host names follow RFC 1123, unless another validator was configured. */
    private Optional<FormatValidator> formatValidator(String format, Dialect dialect) {
        return Optional.ofNullable(options.formats().get(format)).map(validator -> validator == Formats.HOSTNAME
            && (dialect == Dialect.DRAFT_4 || dialect == Dialect.DRAFT_6) ? Formats.RFC_1123_HOSTNAME : validator);
    }

    private static NumberKeywords numbers(Schema schema) {
        var object = schema.object();
        var booleanBounds = schema.dialect().booleanExclusiveBounds();
        return new NumberKeywords(schema.number("multipleOf"),
            schema.number("maximum").map(max -> new Bound(max, booleanBounds && object.bool("exclusiveMaximum").orElse(false))),
            schema.number("minimum").map(min -> new Bound(min, booleanBounds && object.bool("exclusiveMinimum").orElse(false))),
            schema.number("exclusiveMaximum"), schema.number("exclusiveMinimum"));
    }

    private StringKeywords strings(Schema schema) {
        var object = schema.object();
        var content = Optional.of(schema).filter(s -> options.assertContent()).map(s -> new Content(
            object.string("contentEncoding").filter("base64"::equalsIgnoreCase).isPresent(),
            object.string("contentMediaType").filter(type -> type.equals("application/json") || type.endsWith("+json")).isPresent(),
            schema.subschema("contentSchema")));
        return new StringKeywords(integer(schema, "maxLength"), integer(schema, "minLength"), schema.pattern().map(Regex::of), content);
    }

    private static ArrayKeywords arrays(Schema schema) {
        var legacyTuple = schema.keyword("items").filter(JsonArray.class::isInstance).isPresent();
        var contains = schema.subschema("contains").map(c -> new Contains(c, integer(schema, "minContains").orElse(1), integer(schema, "maxContains")));
        return new ArrayKeywords(integer(schema, "maxItems"), integer(schema, "minItems"), schema.object().bool("uniqueItems").orElse(false),
            schema.subschemaList(legacyTuple ? "items" : "prefixItems"), schema.subschema(legacyTuple ? "additionalItems" : "items"), contains,
            schema.subschema("unevaluatedItems"));
    }

    private ObjectKeywords objects(Schema schema) {
        var dependentRequired = new LinkedHashMap<String, List<String>>();
        for (var keyword : List.of("dependentRequired", "dependencies")) {
            schema.object().object(keyword).ifPresent(map -> map.members().forEach((name, required) -> {
                if (required instanceof JsonArray array) {
                    dependentRequired.put(name, array.values().stream().flatMap(item -> item.asString().stream()).toList());
                }
            }));
        }
        var properties = new LinkedHashMap<String, Property>();
        schema.subschemaMap("properties").forEach((name, property) -> {
            var resolved = registry.deref(property);
            properties.put(name, new Property(property, resolved.readOnly(), resolved.writeOnly()));
        });
        var patterns = schema.subschemaMap("patternProperties").entrySet().stream()
            .map(entry -> new PatternProperty(Regex.of(entry.getKey()), entry.getValue())).toList();
        var dependentSchemas = new LinkedHashMap<>(schema.subschemaMap("dependencies"));
        dependentSchemas.putAll(schema.subschemaMap("dependentSchemas"));
        return new ObjectKeywords(integer(schema, "maxProperties"), integer(schema, "minProperties"), schema.required(),
            Collections.unmodifiableMap(dependentRequired), Collections.unmodifiableMap(properties), patterns, schema.subschema("additionalProperties"),
            schema.subschema("propertyNames"), Collections.unmodifiableMap(dependentSchemas), schema.subschema("unevaluatedProperties"));
    }

    private static Optional<Integer> integer(Schema schema, String keyword) {
        return schema.number(keyword).map(BigDecimal::intValue);
    }

    // ---------------------------------------------------------------------------------------------
    // Evaluation

    /** The annotations and errors of one schema evaluated against one instance location. */
    private record Evaluation(List<SchemaError> errors, Set<String> properties, Set<Integer> items) {
        static final Evaluation VALID = new Evaluation(List.of(), Set.of(), Set.of());

        boolean valid() {
            return errors.isEmpty();
        }
    }

    /** Mutable accumulator for one evaluation. */
    private static final class Collector {
        final List<SchemaError> errors = new ArrayList<>();
        final Set<String> properties = new HashSet<>();
        final Set<Integer> items = new HashSet<>();

        /** Takes errors and (when valid) annotations of an evaluation at the same instance location. */
        void absorb(Evaluation evaluation) {
            errors.addAll(evaluation.errors());
            if (evaluation.valid()) {
                annotate(evaluation);
            }
        }

        /** Takes only the errors of an evaluation at a child instance location. */
        void child(Evaluation evaluation) {
            errors.addAll(evaluation.errors());
        }

        void annotate(Evaluation evaluation) {
            properties.addAll(evaluation.properties());
            items.addAll(evaluation.items());
        }

        /** The evaluation collected; the collector is not used afterwards, so its sets need no copies. */
        Evaluation result() {
            return errors.isEmpty() ? new Evaluation(List.of(), Collections.unmodifiableSet(properties), Collections.unmodifiableSet(items))
                : new Evaluation(Collections.unmodifiableList(errors), Set.of(), Set.of());
        }
    }

    /** A schema evaluated at an instance location: evaluating it again there, within itself, is a cycle. */
    private record Visit(Schema.Key schema, JsonPointer path) {
    }

    /** A single validation run, carrying the dynamic scope. */
    private final class Run {
        private final Direction direction;
        private final Deque<URI> dynamicScope = new ArrayDeque<>();
        private final Set<Visit> active = new HashSet<>();
        private final Map<JsonPointer, Set<String>> exemptions = new HashMap<>();

        Run(Direction direction) {
            this.direction = direction;
        }

        Evaluation evaluate(Schema schema, JsonValue instance, JsonPointer path) {
            if (schema.json() instanceof JsonBoolean(var accept)) {
                return accept ? Evaluation.VALID
                    : new Evaluation(List.of(error(path, schema, "false", "no value is allowed here")), Set.of(), Set.of());
            }
            var plan = plan(schema);
            var visit = new Visit(schema.key(), path);
            if (!active.add(visit)) {
                return Evaluation.VALID;
            }
            var enteredResource = dynamicScope.isEmpty() || !dynamicScope.peekLast().equals(schema.baseUri());
            if (enteredResource) {
                dynamicScope.addLast(schema.baseUri());
            }
            var ownsExemptions = direction != Direction.NONE && instance instanceof JsonObject && !exemptions.containsKey(path);
            if (ownsExemptions) {
                exemptions.put(path, restrictedProperties(plan.schema(), direction));
            }
            try {
                return evaluateKeywords(plan, instance, path);
            } finally {
                if (enteredResource) {
                    dynamicScope.removeLast();
                }
                if (ownsExemptions) {
                    exemptions.remove(path);
                }
                active.remove(visit);
            }
        }

        private Evaluation evaluateKeywords(Plan plan, JsonValue instance, JsonPointer path) {
            var schema = plan.schema();
            var out = new Collector();
            if (plan.nullable() && instance == JsonNull.INSTANCE) {
                return Evaluation.VALID;
            }
            if (plan.ref().isPresent()) {
                out.absorb(reference(schema, instance, path, plan.ref().get()));
                if (schema.dialect().refOverridesSiblings()) {
                    return out.result();
                }
            }
            plan.dynamicRef().ifPresent(ref -> out.absorb(dynamicReference(schema, instance, path, ref)));
            plan.recursiveRef().ifPresent(ref -> out.absorb(recursiveReference(schema, instance, path, ref)));
            generic(plan, instance, path, out);
            switch (instance) {
                case JsonNumber number -> plan.numbers().ifPresent(keywords -> numeric(schema, keywords, number, path, out));
                case JsonString string -> plan.strings().ifPresent(keywords -> text(schema, keywords, string, path, out));
                case JsonArray array -> plan.arrays().ifPresent(keywords -> array(schema, keywords, array, path, out));
                case JsonObject value -> plan.objects().ifPresent(keywords -> object(schema, keywords, value, path, out));
                default -> {
                }
            }
            combinators(schema, plan.combinators(), instance, path, out);
            if (instance instanceof JsonArray array) {
                plan.arrays().flatMap(ArrayKeywords::unevaluatedItems).ifPresent(items -> unevaluatedItems(items, array, path, out));
            }
            if (instance instanceof JsonObject value) {
                plan.objects().flatMap(ObjectKeywords::unevaluatedProperties).ifPresent(properties -> unevaluatedProperties(schema, properties, value, path, out));
            }
            return out.result();
        }

        // -----------------------------------------------------------------------------------------
        // References

        private Evaluation reference(Schema schema, JsonValue instance, JsonPointer path, String ref) {
            var target = registry.resolve(schema, ref);
            if (target.isEmpty()) {
                return failure(path, schema, "$ref", "unresolvable reference '" + ref + "'");
            }
            return evaluate(target.get(), instance, path);
        }

        private Evaluation dynamicReference(Schema schema, JsonValue instance, JsonPointer path, String ref) {
            var initial = Uris.resolve(schema.baseUri(), ref).toOptional().flatMap(registry::resolve);
            if (initial.isEmpty()) {
                return failure(path, schema, "$dynamicRef", "unresolvable reference '" + ref + "'");
            }
            var anchor = Uris.parse(ref).toOptional().map(Uris::fragment).orElse("");
            var target = initial.get();
            if (!anchor.isEmpty() && !anchor.startsWith("/") && initial.get().object().string("$dynamicAnchor").filter(anchor::equals).isPresent()) {
                target = outermostDynamicAnchor(anchor).orElse(target);
            }
            return evaluate(target, instance, path);
        }

        private Evaluation recursiveReference(Schema schema, JsonValue instance, JsonPointer path, String ref) {
            var initial = registry.resolve(schema, ref);
            if (initial.isEmpty()) {
                return failure(path, schema, "$recursiveRef", "unresolvable reference '" + ref + "'");
            }
            var target = initial.get();
            if (recursiveAnchor(target)) {
                target = dynamicScope.stream().map(registry::resolve).flatMap(Optional::stream).filter(Run::recursiveAnchor)
                    .findFirst().orElse(target);
            }
            return evaluate(target, instance, path);
        }

        private static boolean recursiveAnchor(Schema schema) {
            return schema.object().bool("$recursiveAnchor").orElse(false);
        }

        private Optional<Schema> outermostDynamicAnchor(String anchor) {
            return dynamicScope.stream().map(resource -> registry.dynamicAnchor(resource, anchor)).flatMap(Optional::stream).findFirst();
        }

        // -----------------------------------------------------------------------------------------
        // Keywords applying to any type

        private void generic(Plan plan, JsonValue instance, JsonPointer path, Collector out) {
            var schema = plan.schema();
            var types = plan.types();
            if (!types.isEmpty() && types.stream().noneMatch(type -> matchesType(type, instance, schema.dialect()))) {
                out.errors.add(error(path, schema, "type", "must be " + describeTypes(schema.types()) + " but was " + instance.typeName()));
            }
            plan.enumValues().filter(values -> !values.contains(instance))
                .ifPresent(values -> out.errors.add(error(path, schema, "enum", "must be one of " + values.stream().map(JsonValue::toJson).toList())));
            plan.constValue().filter(expected -> !expected.equals(instance))
                .ifPresent(expected -> out.errors.add(error(path, schema, "const", "must be " + expected.toJson())));
            plan.format().flatMap(validator -> validator.validate(instance)).ifPresent(message -> out.errors.add(error(path, schema, "format", message)));
        }

        /** Draft 4 tells integers from numbers with a fraction by their spelling: {@code 1.0} is not an integer there. */
        private static boolean matchesType(String type, JsonValue instance, Dialect dialect) {
            return switch (type) {
                case "integer" -> instance instanceof JsonNumber number && number.isIntegral() && (dialect != Dialect.DRAFT_4 || number.value().scale() <= 0);
                case "number" -> instance instanceof JsonNumber;
                default -> instance.typeName().equals(type);
            };
        }

        private static String describeTypes(Set<String> types) {
            return types.size() == 1 ? "of type " + types.iterator().next() : "one of types " + types;
        }

        // -----------------------------------------------------------------------------------------
        // Numbers

        private void numeric(Schema schema, NumberKeywords keywords, JsonNumber number, JsonPointer path, Collector out) {
            var value = number.value();
            keywords.multipleOf().filter(divisor -> divisor.signum() > 0 && value.remainder(divisor).signum() != 0)
                .ifPresent(divisor -> out.errors.add(error(path, schema, "multipleOf", "must be a multiple of " + plain(divisor))));
            keywords.maximum().filter(max -> max.exclusive() ? value.compareTo(max.value()) >= 0 : value.compareTo(max.value()) > 0)
                .ifPresent(max -> out.errors.add(error(path, schema, "maximum", "must be " + (max.exclusive() ? "< " : "<= ") + plain(max.value()))));
            keywords.minimum().filter(min -> min.exclusive() ? value.compareTo(min.value()) <= 0 : value.compareTo(min.value()) < 0)
                .ifPresent(min -> out.errors.add(error(path, schema, "minimum", "must be " + (min.exclusive() ? "> " : ">= ") + plain(min.value()))));
            keywords.exclusiveMaximum().filter(max -> value.compareTo(max) >= 0)
                .ifPresent(max -> out.errors.add(error(path, schema, "exclusiveMaximum", "must be < " + plain(max))));
            keywords.exclusiveMinimum().filter(min -> value.compareTo(min) <= 0)
                .ifPresent(min -> out.errors.add(error(path, schema, "exclusiveMinimum", "must be > " + plain(min))));
        }

        private static String plain(BigDecimal value) {
            return value.stripTrailingZeros().toPlainString();
        }

        // -----------------------------------------------------------------------------------------
        // Strings

        private void text(Schema schema, StringKeywords keywords, JsonString string, JsonPointer path, Collector out) {
            var length = string.codePointLength();
            keywords.maxLength().filter(max -> length > max)
                .ifPresent(max -> out.errors.add(error(path, schema, "maxLength", "must be at most " + max + " characters long")));
            keywords.minLength().filter(min -> length < min)
                .ifPresent(min -> out.errors.add(error(path, schema, "minLength", "must be at least " + min + " characters long")));
            keywords.pattern().flatMap(pattern -> pattern.mismatch(string.value()))
                .ifPresent(message -> out.errors.add(error(path, schema, "pattern", message)));
            keywords.content().ifPresent(content -> content(schema, content, string, path, out));
        }

        private void content(Schema schema, Content content, JsonString string, JsonPointer path, Collector out) {
            var decoded = string.value();
            if (content.base64()) {
                try {
                    decoded = new String(Base64.getDecoder().decode(string.value()), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    out.errors.add(error(path, schema, "contentEncoding", "must be base64 encoded"));
                    return;
                }
            }
            if (!content.json()) {
                return;
            }
            switch (Json.parse(decoded)) {
                case Result.Err(var invalid) -> out.errors.add(error(path, schema, "contentMediaType", "must contain valid JSON"));
                case Result.Ok(var parsed) -> content.schema().ifPresent(contentSchema -> out.errors.addAll(evaluate(contentSchema, parsed, path).errors()));
            }
        }

        // -----------------------------------------------------------------------------------------
        // Arrays

        private void array(Schema schema, ArrayKeywords keywords, JsonArray array, JsonPointer path, Collector out) {
            var size = array.size();
            keywords.maxItems().filter(max -> size > max)
                .ifPresent(max -> out.errors.add(error(path, schema, "maxItems", "must have at most " + max + " items")));
            keywords.minItems().filter(min -> size < min)
                .ifPresent(min -> out.errors.add(error(path, schema, "minItems", "must have at least " + min + " items")));
            if (keywords.uniqueItems() && new HashSet<>(array.values()).size() != size) {
                out.errors.add(error(path, schema, "uniqueItems", "must not contain duplicate items"));
            }
            var tuple = keywords.tuple();
            int prefix = Math.min(tuple.size(), size);
            for (int i = 0; i < prefix; i++) {
                out.child(evaluate(tuple.get(i), array.values().get(i), path.child(i)));
                out.items.add(i);
            }
            keywords.rest().ifPresent(itemSchema -> {
                for (int i = prefix; i < size; i++) {
                    out.child(evaluate(itemSchema, array.values().get(i), path.child(i)));
                    out.items.add(i);
                }
            });
            keywords.contains().ifPresent(contains -> {
                var matched = new ArrayList<Integer>();
                for (int i = 0; i < size; i++) {
                    if (evaluate(contains.schema(), array.values().get(i), path.child(i)).valid()) {
                        matched.add(i);
                    }
                }
                out.items.addAll(matched);
                contains.max().filter(max -> matched.size() > max).ifPresent(max ->
                    out.errors.add(error(path, schema, "maxContains", "must contain at most " + max + " matching items")));
                var min = contains.min();
                if (matched.size() < min) {
                    out.errors.add(error(path, schema, min == 1 ? "contains" : "minContains",
                        min == 1 ? "must contain at least one matching item" : "must contain at least " + min + " matching items"));
                }
            });
        }

        private void unevaluatedItems(Schema itemSchema, JsonArray array, JsonPointer path, Collector out) {
            for (int i = 0; i < array.size(); i++) {
                if (!out.items.contains(i)) {
                    out.child(evaluate(itemSchema, array.values().get(i), path.child(i)));
                    out.items.add(i);
                }
            }
        }

        // -----------------------------------------------------------------------------------------
        // Objects

        private void object(Schema schema, ObjectKeywords keywords, JsonObject value, JsonPointer path, Collector out) {
            var size = value.size();
            keywords.maxProperties().filter(max -> size > max)
                .ifPresent(max -> out.errors.add(error(path, schema, "maxProperties", "must have at most " + max + " properties")));
            keywords.minProperties().filter(min -> size < min)
                .ifPresent(min -> out.errors.add(error(path, schema, "minProperties", "must have at least " + min + " properties")));
            var exempt = exemptions.getOrDefault(path, Set.of());
            for (var name : keywords.required()) {
                if (!value.has(name) && !exempt.contains(name)) {
                    out.errors.add(error(path, schema, "required", "missing required property '" + name + "'"));
                }
            }
            keywords.dependentRequired().forEach((name, required) -> {
                if (value.has(name)) {
                    required.stream().filter(other -> !value.has(other)).forEach(other -> out.errors.add(
                        error(path, schema, "dependentRequired", "property '" + other + "' is required when '" + name + "' is present")));
                }
            });
            for (var member : value.members().entrySet()) {
                var name = member.getKey();
                var childPath = path.child(name);
                var matched = false;
                var property = keywords.properties().get(name);
                if (property != null) {
                    matched = true;
                    accessMode(property, childPath, out);
                    out.child(evaluate(property.schema(), member.getValue(), childPath));
                }
                for (var pattern : keywords.patternProperties()) {
                    if (pattern.pattern().finds(name)) {
                        matched = true;
                        out.child(evaluate(pattern.schema(), member.getValue(), childPath));
                    }
                }
                if (matched) {
                    out.properties.add(name);
                    continue;
                }
                var additional = keywords.additionalProperties();
                if (additional.isPresent()) {
                    if (additional.get().rejectsEverything()) {
                        out.errors.add(error(childPath, schema, "additionalProperties", "property '" + name + "' is not allowed"));
                    } else {
                        out.child(evaluate(additional.get(), member.getValue(), childPath));
                    }
                    out.properties.add(name);
                }
            }
            keywords.propertyNames().ifPresent(names -> value.keys().forEach(name ->
                evaluate(names, JsonString.of(name), path.child(name)).errors().forEach(e ->
                    out.errors.add(error(path.child(name), schema, "propertyNames", "property name '" + name + "' is invalid: " + e.message())))));
            keywords.dependentSchemas().forEach((name, dependent) -> {
                if (value.has(name)) {
                    out.absorb(evaluate(dependent, value, path));
                }
            });
        }

        private void accessMode(Property property, JsonPointer path, Collector out) {
            if (direction == Direction.REQUEST && property.readOnly()) {
                out.errors.add(error(path, property.schema(), "readOnly", "is read-only and must not be sent in a request"));
            } else if (direction == Direction.RESPONSE && property.writeOnly()) {
                out.errors.add(error(path, property.schema(), "writeOnly", "is write-only and must not be returned in a response"));
            }
        }

        private void unevaluatedProperties(Schema schema, Schema propertySchema, JsonObject value, JsonPointer path, Collector out) {
            for (var member : value.members().entrySet()) {
                var name = member.getKey();
                if (out.properties.contains(name)) {
                    continue;
                }
                if (propertySchema.rejectsEverything()) {
                    out.errors.add(error(path.child(name), schema, "unevaluatedProperties", "property '" + name + "' is not allowed"));
                } else {
                    out.child(evaluate(propertySchema, member.getValue(), path.child(name)));
                }
                out.properties.add(name);
            }
        }

        // -----------------------------------------------------------------------------------------
        // Combinators

        private void combinators(Schema schema, Combinators keywords, JsonValue instance, JsonPointer path, Collector out) {
            for (var member : keywords.allOf()) {
                out.absorb(evaluate(member, instance, path));
            }
            if (!keywords.anyOf().isEmpty()) {
                var evaluations = keywords.anyOf().stream().map(member -> evaluate(member, instance, path)).toList();
                var valid = evaluations.stream().filter(Evaluation::valid).toList();
                if (valid.isEmpty()) {
                    out.errors.addAll(combinatorFailure(schema, keywords, instance, path, "anyOf", "must match at least one schema in anyOf", evaluations));
                }
                valid.forEach(out::annotate);
            }
            if (!keywords.oneOf().isEmpty()) {
                var evaluations = keywords.oneOf().stream().map(member -> evaluate(member, instance, path)).toList();
                var valid = evaluations.stream().filter(Evaluation::valid).toList();
                if (valid.isEmpty()) {
                    out.errors.addAll(combinatorFailure(schema, keywords, instance, path, "oneOf", "must match exactly one schema in oneOf", evaluations));
                } else if (valid.size() > 1) {
                    // A discriminator MUST NOT change the validation outcome: an instance valid for several members is invalid.
                    var indices = IntStream.range(0, evaluations.size()).filter(i -> evaluations.get(i).valid()).boxed().toList();
                    out.errors.add(error(path, schema, "oneOf", "must match exactly one schema in oneOf but matched schemas at " + indices));
                } else {
                    out.annotate(valid.getFirst());
                }
            }
            keywords.not().filter(not -> evaluate(not, instance, path).valid())
                .ifPresent(not -> out.errors.add(error(path, schema, "not", "must not match the schema in 'not'")));
            keywords.condition().ifPresent(condition -> {
                var test = evaluate(condition, instance, path);
                if (test.valid()) {
                    out.annotate(test);
                    keywords.then().ifPresent(then -> out.absorb(evaluate(then, instance, path)));
                } else {
                    keywords.otherwise().ifPresent(otherwise -> out.absorb(evaluate(otherwise, instance, path)));
                }
            });
        }

        /**
         * Explains a failed {@code anyOf}/{@code oneOf}. With a discriminator whose value selects a
         * branch, the errors of that branch are reported directly.
         */
        private List<SchemaError> combinatorFailure(Schema schema, Combinators keywords, JsonValue instance, JsonPointer path, String keyword,
                                                    String message, List<Evaluation> evaluations) {
            var discriminator = schema.discriminator();
            if (discriminator.isPresent() && instance instanceof JsonObject object) {
                var property = discriminator.get().propertyName();
                var value = object.string(property);
                if (value.isEmpty()) {
                    return List.of(error(path, schema, "discriminator", "missing discriminator property '" + property + "'"));
                }
                var selected = discriminatedSchema(schema, keywords, discriminator.get(), value.get());
                if (selected.isEmpty()) {
                    return List.of(error(path.child(property), schema, "discriminator",
                        "unknown discriminator value '" + value.get() + "' for property '" + property + "'"));
                }
                return evaluate(selected.get(), instance, path).errors();
            }
            var causes = evaluations.stream().flatMap(e -> e.errors().stream()).toList();
            return List.of(new SchemaError(path, keyword, message, keywordLocation(schema, keyword), causes));
        }

        private Optional<Schema> discriminatedSchema(Schema schema, Combinators keywords, Discriminator discriminator, String value) {
            return Optional.ofNullable(discriminator.mapping().get(value)).flatMap(target -> registry.resolveMapping(schema, target))
                .or(() -> Stream.concat(keywords.oneOf().stream(), keywords.anyOf().stream())
                    .filter(candidate -> candidate.ref().map(ref -> ref.substring(ref.lastIndexOf('/') + 1)).filter(value::equals).isPresent())
                    .findFirst())
                .or(() -> registry.resolveMapping(schema, value))
                .or(() -> discriminator.defaultMapping().flatMap(target -> registry.resolveMapping(schema, target)));
        }

        // -----------------------------------------------------------------------------------------

        private Evaluation failure(JsonPointer path, Schema schema, String keyword, String message) {
            return new Evaluation(List.of(error(path, schema, keyword, message)), Set.of(), Set.of());
        }

        private SchemaError error(JsonPointer path, Schema schema, String keyword, String message) {
            return new SchemaError(path, keyword, message, keywordLocation(schema, keyword), List.of());
        }

        /** Where {@code keyword} of {@code schema} is: made once, as failing branches of {@code oneOf} report errors even for valid data. */
        private URI keywordLocation(Schema schema, String keyword) {
            return keywordLocations.computeIfAbsent(new KeywordAt(schema.key(), keyword),
                at -> URI.create(schema.document() + schema.pointer().child(keyword).toUriFragment()));
        }
    }

    /** The properties a schema restricts in a direction (see {@link #restrictedProperties}). */
    private record Restriction(Direction direction, Schema.Key schema) {
    }

    /**
     * The properties that are read-only (for requests) or write-only (for responses) in
     * {@code schema} or any schema applied in place with it ({@code $ref}, {@code allOf},
     * {@code anyOf}, {@code oneOf}, {@code if}/{@code then}/{@code else}, {@code dependentSchemas}).
     * Such properties are exempt from {@code required} in that direction.
     */
    private Set<String> restrictedProperties(Schema schema, Direction direction) {
        return restricted.computeIfAbsent(new Restriction(direction, schema.key()), key -> {
            var names = new HashSet<String>();
            collectRestricted(schema, direction, names, new HashSet<>());
            return Set.copyOf(names);
        });
    }

    private void collectRestricted(Schema schema, Direction direction, Set<String> names, Set<Schema.Key> seen) {
        if (!seen.add(schema.key())) {
            return;
        }
        schema.subschemaMap("properties").forEach((name, property) -> {
            var resolved = registry.deref(property);
            if (direction == Direction.REQUEST ? resolved.readOnly() : resolved.writeOnly()) {
                names.add(name);
            }
        });
        registry.dereference(schema).ifPresent(target -> collectRestricted(target, direction, names, seen));
        for (var keyword : List.of("allOf", "anyOf", "oneOf")) {
            schema.subschemaList(keyword).forEach(member -> collectRestricted(member, direction, names, seen));
        }
        for (var keyword : List.of("if", "then", "else")) {
            schema.subschema(keyword).ifPresent(member -> collectRestricted(member, direction, names, seen));
        }
        schema.subschemaMap("dependentSchemas").values().forEach(member -> collectRestricted(member, direction, names, seen));
    }

    /** All distinct messages of {@code errors} (including nested causes), for diagnostics. */
    public static Set<String> describe(List<SchemaError> errors) {
        return errors.stream().flatMap(error -> Stream.concat(Stream.of(error.toString()), describe(error.causes()).stream()))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
