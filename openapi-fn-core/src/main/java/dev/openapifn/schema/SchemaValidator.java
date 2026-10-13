package dev.openapifn.schema;

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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates JSON instances against schemas. Supports JSON Schema draft 4 through 2020-12 and the
 * OpenAPI 3.0 Schema Object, including {@code unevaluatedProperties}/{@code unevaluatedItems},
 * {@code $dynamicRef}, {@code $recursiveRef}, format assertion, OpenAPI {@code nullable},
 * {@code discriminator} and direction-aware {@code readOnly}/{@code writeOnly}. Instances are
 * immutable and thread-safe.
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

        Evaluation result() {
            return errors.isEmpty() ? new Evaluation(List.of(), Set.copyOf(properties), Set.copyOf(items))
                : new Evaluation(List.copyOf(errors), Set.of(), Set.of());
        }
    }

    /** A single validation run, carrying the dynamic scope. */
    private final class Run {
        private final Direction direction;
        private final Deque<URI> dynamicScope = new ArrayDeque<>();
        private final Set<String> active = new HashSet<>();

        Run(Direction direction) {
            this.direction = direction;
        }

        Evaluation evaluate(Schema schema, JsonValue instance, JsonPointer path) {
            if (schema.json() instanceof JsonBoolean(var accept)) {
                return accept ? Evaluation.VALID
                    : new Evaluation(List.of(error(path, schema, "false", "no value is allowed here")), Set.of(), Set.of());
            }
            var key = schema.location() + "@" + path;
            if (!active.add(key)) {
                return Evaluation.VALID;
            }
            var enteredResource = dynamicScope.isEmpty() || !dynamicScope.peekLast().equals(schema.baseUri());
            if (enteredResource) {
                dynamicScope.addLast(schema.baseUri());
            }
            try {
                return evaluateKeywords(schema, instance, path);
            } finally {
                if (enteredResource) {
                    dynamicScope.removeLast();
                }
                active.remove(key);
            }
        }

        private Evaluation evaluateKeywords(Schema schema, JsonValue instance, JsonPointer path) {
            var dialect = schema.dialect();
            var object = schema.object();
            var out = new Collector();
            if (dialect.nullableKeyword() && instance == JsonNull.INSTANCE && object.bool("nullable").orElse(false)) {
                return Evaluation.VALID;
            }
            if (object.has("$ref")) {
                out.absorb(reference(schema, instance, path, "$ref", object.string("$ref").orElse("")));
                if (dialect.refOverridesSiblings()) {
                    return out.result();
                }
            }
            object.string("$dynamicRef").ifPresent(ref -> out.absorb(dynamicReference(schema, instance, path, ref)));
            object.string("$recursiveRef").ifPresent(ref -> out.absorb(recursiveReference(schema, instance, path, ref)));
            generic(schema, instance, path, out);
            switch (instance) {
                case JsonNumber number -> numeric(schema, number, path, out);
                case JsonString string -> text(schema, string, path, out);
                case JsonArray array -> array(schema, array, path, out);
                case JsonObject value -> object(schema, value, path, out);
                default -> {
                }
            }
            combinators(schema, instance, path, out);
            discriminatorDispatch(schema, instance, path, out);
            if (instance instanceof JsonArray array) {
                unevaluatedItems(schema, array, path, out);
            }
            if (instance instanceof JsonObject value) {
                unevaluatedProperties(schema, value, path, out);
            }
            return out.result();
        }

        // -----------------------------------------------------------------------------------------
        // References

        private Evaluation reference(Schema schema, JsonValue instance, JsonPointer path, String keyword, String ref) {
            var target = registry.resolve(schema, ref);
            if (target.isEmpty()) {
                return failure(path, schema, keyword, "unresolvable reference '" + ref + "'");
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
            if (target.object().bool("$recursiveAnchor").orElse(false)) {
                for (var resource : dynamicScope) {
                    var root = registry.resolve(resource);
                    if (root.isPresent() && root.get().object().bool("$recursiveAnchor").orElse(false)) {
                        target = root.get();
                        break;
                    }
                }
            }
            return evaluate(target, instance, path);
        }

        private Optional<Schema> outermostDynamicAnchor(String anchor) {
            for (var resource : dynamicScope) {
                var found = registry.dynamicAnchor(resource, anchor);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }

        // -----------------------------------------------------------------------------------------
        // Keywords applying to any type

        private void generic(Schema schema, JsonValue instance, JsonPointer path, Collector out) {
            var types = schema.types();
            if (!types.isEmpty() && types.stream().noneMatch(type -> matchesType(type, instance))) {
                out.errors.add(error(path, schema, "type", "must be " + describeTypes(types) + " but was " + instance.typeName()));
            }
            var enumValues = schema.enumValues();
            if (schema.has("enum") && !enumValues.contains(instance)) {
                out.errors.add(error(path, schema, "enum", "must be one of " + enumValues.stream().map(JsonValue::toJson).toList()));
            }
            schema.constValue().filter(expected -> !expected.equals(instance))
                .ifPresent(expected -> out.errors.add(error(path, schema, "const", "must be " + expected.toJson())));
            if (options.assertFormats()) {
                schema.format().flatMap(format -> Optional.ofNullable(options.formats().get(format)))
                    .flatMap(validator -> validator.validate(instance))
                    .ifPresent(message -> out.errors.add(error(path, schema, "format", message)));
            }
        }

        private static boolean matchesType(String type, JsonValue instance) {
            return switch (type) {
                case "integer" -> instance instanceof JsonNumber number && number.isIntegral();
                case "number" -> instance instanceof JsonNumber;
                default -> instance.typeName().equals(type);
            };
        }

        private static String describeTypes(Set<String> types) {
            return types.size() == 1 ? "of type " + types.iterator().next() : "one of types " + types;
        }

        // -----------------------------------------------------------------------------------------
        // Numbers

        private void numeric(Schema schema, JsonNumber number, JsonPointer path, Collector out) {
            var value = number.value();
            schema.number("multipleOf").filter(divisor -> divisor.signum() > 0 && value.remainder(divisor).signum() != 0)
                .ifPresent(divisor -> out.errors.add(error(path, schema, "multipleOf", "must be a multiple of " + divisor.stripTrailingZeros().toPlainString())));
            var booleanBounds = schema.dialect().booleanExclusiveBounds();
            schema.number("maximum").ifPresent(max -> {
                var exclusive = booleanBounds && schema.object().bool("exclusiveMaximum").orElse(false);
                if (exclusive ? value.compareTo(max) >= 0 : value.compareTo(max) > 0) {
                    out.errors.add(error(path, schema, "maximum", "must be " + (exclusive ? "< " : "<= ") + plain(max)));
                }
            });
            schema.number("minimum").ifPresent(min -> {
                var exclusive = booleanBounds && schema.object().bool("exclusiveMinimum").orElse(false);
                if (exclusive ? value.compareTo(min) <= 0 : value.compareTo(min) < 0) {
                    out.errors.add(error(path, schema, "minimum", "must be " + (exclusive ? "> " : ">= ") + plain(min)));
                }
            });
            schema.number("exclusiveMaximum").filter(max -> value.compareTo(max) >= 0)
                .ifPresent(max -> out.errors.add(error(path, schema, "exclusiveMaximum", "must be < " + plain(max))));
            schema.number("exclusiveMinimum").filter(min -> value.compareTo(min) <= 0)
                .ifPresent(min -> out.errors.add(error(path, schema, "exclusiveMinimum", "must be > " + plain(min))));
        }

        private static String plain(BigDecimal value) {
            return value.stripTrailingZeros().toPlainString();
        }

        // -----------------------------------------------------------------------------------------
        // Strings

        private void text(Schema schema, JsonString string, JsonPointer path, Collector out) {
            var length = string.codePointLength();
            schema.number("maxLength").filter(max -> length > max.intValue())
                .ifPresent(max -> out.errors.add(error(path, schema, "maxLength", "must be at most " + max.intValue() + " characters long")));
            schema.number("minLength").filter(min -> length < min.intValue())
                .ifPresent(min -> out.errors.add(error(path, schema, "minLength", "must be at least " + min.intValue() + " characters long")));
            schema.pattern().ifPresent(pattern -> {
                var compiled = EcmaRegex.compile(pattern);
                if (compiled.isErr()) {
                    out.errors.add(error(path, schema, "pattern", compiled.failure().orElseThrow()));
                } else if (!compiled.toOptional().orElseThrow().matcher(string.value()).find()) {
                    out.errors.add(error(path, schema, "pattern", "must match pattern " + pattern));
                }
            });
            if (options.assertContent()) {
                content(schema, string, path, out);
            }
        }

        private void content(Schema schema, JsonString string, JsonPointer path, Collector out) {
            var decoded = Optional.of(string.value());
            if (schema.object().string("contentEncoding").filter("base64"::equalsIgnoreCase).isPresent()) {
                try {
                    decoded = Optional.of(new String(Base64.getDecoder().decode(string.value()), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    out.errors.add(error(path, schema, "contentEncoding", "must be base64 encoded"));
                    return;
                }
            }
            var isJson = schema.object().string("contentMediaType").filter(type -> type.equals("application/json") || type.endsWith("+json"));
            if (isJson.isEmpty()) {
                return;
            }
            var parsed = Json.parse(decoded.get());
            if (parsed.isErr()) {
                out.errors.add(error(path, schema, "contentMediaType", "must contain valid JSON"));
                return;
            }
            schema.subschema("contentSchema").ifPresent(contentSchema ->
                out.errors.addAll(evaluate(contentSchema, parsed.toOptional().orElseThrow(), path).errors()));
        }

        // -----------------------------------------------------------------------------------------
        // Arrays

        private void array(Schema schema, JsonArray array, JsonPointer path, Collector out) {
            var size = array.size();
            schema.number("maxItems").filter(max -> size > max.intValue())
                .ifPresent(max -> out.errors.add(error(path, schema, "maxItems", "must have at most " + max.intValue() + " items")));
            schema.number("minItems").filter(min -> size < min.intValue())
                .ifPresent(min -> out.errors.add(error(path, schema, "minItems", "must have at least " + min.intValue() + " items")));
            if (schema.object().bool("uniqueItems").orElse(false) && new HashSet<>(array.values()).size() != size) {
                out.errors.add(error(path, schema, "uniqueItems", "must not contain duplicate items"));
            }
            var legacyTuple = schema.keyword("items").filter(JsonArray.class::isInstance).isPresent();
            var tuple = schema.subschemaList(legacyTuple ? "items" : "prefixItems");
            var rest = schema.subschema(legacyTuple ? "additionalItems" : "items");
            int prefix = Math.min(tuple.size(), size);
            for (int i = 0; i < prefix; i++) {
                out.child(evaluate(tuple.get(i), array.values().get(i), path.child(i)));
                out.items.add(i);
            }
            rest.ifPresent(itemSchema -> {
                for (int i = prefix; i < size; i++) {
                    out.child(evaluate(itemSchema, array.values().get(i), path.child(i)));
                    out.items.add(i);
                }
            });
            schema.subschema("contains").ifPresent(contains -> {
                var matched = new ArrayList<Integer>();
                for (int i = 0; i < size; i++) {
                    if (evaluate(contains, array.values().get(i), path.child(i)).valid()) {
                        matched.add(i);
                    }
                }
                out.items.addAll(matched);
                var min = schema.number("minContains").map(BigDecimal::intValue).orElse(1);
                schema.number("maxContains").filter(max -> matched.size() > max.intValue()).ifPresent(max ->
                    out.errors.add(error(path, schema, "maxContains", "must contain at most " + max.intValue() + " matching items")));
                if (matched.size() < min) {
                    out.errors.add(error(path, schema, min == 1 ? "contains" : "minContains",
                        min == 1 ? "must contain at least one matching item" : "must contain at least " + min + " matching items"));
                }
            });
        }

        private void unevaluatedItems(Schema schema, JsonArray array, JsonPointer path, Collector out) {
            schema.subschema("unevaluatedItems").ifPresent(itemSchema -> {
                for (int i = 0; i < array.size(); i++) {
                    if (!out.items.contains(i)) {
                        out.child(evaluate(itemSchema, array.values().get(i), path.child(i)));
                        out.items.add(i);
                    }
                }
            });
        }

        // -----------------------------------------------------------------------------------------
        // Objects

        private void object(Schema schema, JsonObject value, JsonPointer path, Collector out) {
            var size = value.size();
            schema.number("maxProperties").filter(max -> size > max.intValue())
                .ifPresent(max -> out.errors.add(error(path, schema, "maxProperties", "must have at most " + max.intValue() + " properties")));
            schema.number("minProperties").filter(min -> size < min.intValue())
                .ifPresent(min -> out.errors.add(error(path, schema, "minProperties", "must have at least " + min.intValue() + " properties")));
            for (var name : schema.required()) {
                if (!value.has(name) && !exemptFromRequired(schema, name)) {
                    out.errors.add(error(path, schema, "required", "missing required property '" + name + "'"));
                }
            }
            dependentRequired(schema, value, path, out);
            var properties = schema.subschemaMap("properties");
            var patterns = schema.subschemaMap("patternProperties");
            for (var member : value.members().entrySet()) {
                var name = member.getKey();
                var childPath = path.child(name);
                var matched = false;
                var propertySchema = properties.get(name);
                if (propertySchema != null) {
                    matched = true;
                    accessMode(propertySchema, childPath, out);
                    out.child(evaluate(propertySchema, member.getValue(), childPath));
                }
                for (var pattern : patterns.entrySet()) {
                    var compiled = EcmaRegex.compile(pattern.getKey());
                    if (compiled.isOk() && compiled.toOptional().orElseThrow().matcher(name).find()) {
                        matched = true;
                        out.child(evaluate(pattern.getValue(), member.getValue(), childPath));
                    }
                }
                if (matched) {
                    out.properties.add(name);
                    continue;
                }
                var additional = schema.subschema("additionalProperties");
                if (additional.isPresent()) {
                    if (additional.get().rejectsEverything()) {
                        out.errors.add(error(childPath, schema, "additionalProperties", "property '" + name + "' is not allowed"));
                    } else {
                        out.child(evaluate(additional.get(), member.getValue(), childPath));
                    }
                    out.properties.add(name);
                }
            }
            schema.subschema("propertyNames").ifPresent(names -> value.keys().forEach(name ->
                evaluate(names, JsonString.of(name), path.child(name)).errors().forEach(e ->
                    out.errors.add(error(path.child(name), schema, "propertyNames", "property name '" + name + "' is invalid: " + e.message())))));
            for (var dependent : dependentSchemas(schema).entrySet()) {
                if (value.has(dependent.getKey())) {
                    out.absorb(evaluate(dependent.getValue(), value, path));
                }
            }
        }

        private void dependentRequired(Schema schema, JsonObject value, JsonPointer path, Collector out) {
            var dependencies = new HashMap<String, List<String>>();
            for (var keyword : List.of("dependentRequired", "dependencies")) {
                schema.object().object(keyword).ifPresent(map -> map.members().forEach((name, required) -> {
                    if (required instanceof JsonArray array) {
                        dependencies.put(name, array.values().stream().flatMap(item -> item.asString().stream()).toList());
                    }
                }));
            }
            dependencies.forEach((name, required) -> {
                if (value.has(name)) {
                    required.stream().filter(other -> !value.has(other)).forEach(other -> out.errors.add(
                        error(path, schema, "dependentRequired", "property '" + other + "' is required when '" + name + "' is present")));
                }
            });
        }

        private Map<String, Schema> dependentSchemas(Schema schema) {
            var result = new java.util.LinkedHashMap<>(schema.subschemaMap("dependencies"));
            result.putAll(schema.subschemaMap("dependentSchemas"));
            return result;
        }

        private void accessMode(Schema propertySchema, JsonPointer path, Collector out) {
            var resolved = registry.deref(propertySchema);
            if (direction == Direction.REQUEST && resolved.readOnly()) {
                out.errors.add(error(path, propertySchema, "readOnly", "is read-only and must not be sent in a request"));
            } else if (direction == Direction.RESPONSE && resolved.writeOnly()) {
                out.errors.add(error(path, propertySchema, "writeOnly", "is write-only and must not be returned in a response"));
            }
        }

        /** Read-only properties are not required in requests, write-only ones not in responses. */
        private boolean exemptFromRequired(Schema schema, String name) {
            if (direction == Direction.NONE) {
                return false;
            }
            var candidates = new ArrayList<Schema>();
            candidates.add(schema);
            schema.subschemaList("allOf").forEach(member -> candidates.add(registry.deref(member)));
            return candidates.stream()
                .map(candidate -> candidate.subschemaMap("properties").get(name))
                .filter(java.util.Objects::nonNull)
                .map(registry::deref)
                .anyMatch(property -> direction == Direction.REQUEST ? property.readOnly() : property.writeOnly());
        }

        private void unevaluatedProperties(Schema schema, JsonObject value, JsonPointer path, Collector out) {
            schema.subschema("unevaluatedProperties").ifPresent(propertySchema -> {
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
            });
        }

        // -----------------------------------------------------------------------------------------
        // Combinators

        private void combinators(Schema schema, JsonValue instance, JsonPointer path, Collector out) {
            for (var member : schema.subschemaList("allOf")) {
                out.absorb(evaluate(member, instance, path));
            }
            var anyOf = schema.subschemaList("anyOf");
            if (!anyOf.isEmpty()) {
                var evaluations = anyOf.stream().map(member -> evaluate(member, instance, path)).toList();
                var valid = evaluations.stream().filter(Evaluation::valid).toList();
                if (valid.isEmpty()) {
                    out.errors.addAll(combinatorFailure(schema, instance, path, "anyOf", "must match at least one schema in anyOf", evaluations));
                }
                valid.forEach(out::annotate);
            }
            var oneOf = schema.subschemaList("oneOf");
            if (!oneOf.isEmpty()) {
                var evaluations = oneOf.stream().map(member -> evaluate(member, instance, path)).toList();
                var valid = evaluations.stream().filter(Evaluation::valid).toList();
                if (valid.isEmpty()) {
                    out.errors.addAll(combinatorFailure(schema, instance, path, "oneOf", "must match exactly one schema in oneOf", evaluations));
                } else if (valid.size() > 1) {
                    var indices = new ArrayList<Integer>();
                    for (int i = 0; i < evaluations.size(); i++) {
                        if (evaluations.get(i).valid()) {
                            indices.add(i);
                        }
                    }
                    out.errors.add(error(path, schema, "oneOf", "must match exactly one schema in oneOf but matched schemas at " + indices));
                } else {
                    out.annotate(valid.getFirst());
                }
            }
            schema.subschema("not").ifPresent(not -> {
                if (evaluate(not, instance, path).valid()) {
                    out.errors.add(error(path, schema, "not", "must not match the schema in 'not'"));
                }
            });
            schema.subschema("if").ifPresent(condition -> {
                var test = evaluate(condition, instance, path);
                if (test.valid()) {
                    out.annotate(test);
                    schema.subschema("then").ifPresent(then -> out.absorb(evaluate(then, instance, path)));
                } else {
                    schema.subschema("else").ifPresent(otherwise -> out.absorb(evaluate(otherwise, instance, path)));
                }
            });
        }

        /**
         * Explains a failed {@code anyOf}/{@code oneOf}. With a discriminator whose value selects a
         * branch, the errors of that branch are reported directly.
         */
        private List<SchemaError> combinatorFailure(Schema schema, JsonValue instance, JsonPointer path, String keyword, String message,
                                                    List<Evaluation> evaluations) {
            var discriminator = schema.discriminator();
            if (discriminator.isPresent() && instance instanceof JsonObject object) {
                var property = discriminator.get().propertyName();
                var value = object.string(property);
                if (value.isEmpty()) {
                    return List.of(error(path, schema, "discriminator", "missing discriminator property '" + property + "'"));
                }
                var selected = discriminatedSchema(schema, discriminator.get(), value.get());
                if (selected.isEmpty()) {
                    return List.of(error(path.child(property), schema, "discriminator",
                        "unknown discriminator value '" + value.get() + "' for property '" + property + "'"));
                }
                return evaluate(selected.get(), instance, path).errors();
            }
            var causes = evaluations.stream().flatMap(e -> e.errors().stream()).toList();
            return List.of(new SchemaError(path, keyword, message, keywordLocation(schema, keyword), causes));
        }

        private Optional<Schema> discriminatedSchema(Schema schema, Discriminator discriminator, String value) {
            var candidates = new ArrayList<>(schema.subschemaList("oneOf"));
            candidates.addAll(schema.subschemaList("anyOf"));
            return Optional.ofNullable(discriminator.mapping().get(value)).flatMap(target -> registry.resolveMapping(schema, target))
                .or(() -> candidates.stream()
                    .filter(candidate -> candidate.ref().map(ref -> ref.substring(ref.lastIndexOf('/') + 1)).filter(value::equals).isPresent())
                    .findFirst())
                .or(() -> registry.resolveMapping(schema, value))
                .or(() -> discriminator.defaultMapping().flatMap(target -> registry.resolveMapping(schema, target)));
        }

        /**
         * A discriminator on a schema without {@code oneOf}/{@code anyOf} (OpenAPI 3.0 style
         * {@code allOf} inheritance) dispatches to the schema selected by the discriminator value.
         */
        private void discriminatorDispatch(Schema schema, JsonValue instance, JsonPointer path, Collector out) {
            if (schema.has("oneOf") || schema.has("anyOf") || !(instance instanceof JsonObject object)) {
                return;
            }
            schema.discriminator().ifPresent(discriminator -> object.string(discriminator.propertyName())
                .flatMap(value -> discriminatedSchema(schema, discriminator, value))
                .filter(target -> !target.location().equals(schema.location()))
                .ifPresent(target -> out.absorb(evaluate(target, instance, path))));
        }

        // -----------------------------------------------------------------------------------------

        private Evaluation failure(JsonPointer path, Schema schema, String keyword, String message) {
            return new Evaluation(List.of(error(path, schema, keyword, message)), Set.of(), Set.of());
        }

        private SchemaError error(JsonPointer path, Schema schema, String keyword, String message) {
            return new SchemaError(path, keyword, message, keywordLocation(schema, keyword), List.of());
        }

        private URI keywordLocation(Schema schema, String keyword) {
            return URI.create(schema.document() + schema.pointer().child(keyword).toUriFragment());
        }
    }

    /** All distinct messages of {@code errors} (including nested causes), for diagnostics. */
    public static Set<String> describe(List<SchemaError> errors) {
        return errors.stream().flatMap(error -> java.util.stream.Stream.concat(java.util.stream.Stream.of(error.toString()),
            describe(error.causes()).stream())).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
