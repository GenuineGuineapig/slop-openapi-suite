package dev.openapifn.generator.example;

import dev.openapifn.http.SchemaShapes;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonNull;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.schema.Direction;
import dev.openapifn.schema.Schema;
import dev.openapifn.schema.SchemaRegistry;
import dev.openapifn.schema.SchemaValidator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Derives example values from schemas: the first declared example, constant, default or enum
 * value that is valid, otherwise a synthesized value that respects types, formats, bounds,
 * lengths, required properties, discriminators, dynamic references and
 * {@code readOnly}/{@code writeOnly} for the direction. When several {@code allOf} members declare
 * a property, later ones refine earlier ones and the schema's own declaration wins. Results are
 * validated; values that do not conform (for instance because of a {@code pattern}) are not
 * returned.
 */
public final class ExampleGenerator {

    private static final Map<String, String> FORMATS = Map.ofEntries(
        Map.entry("date", "2024-01-15"),
        Map.entry("date-time", "2024-01-15T10:30:00Z"),
        Map.entry("time", "10:30:00Z"),
        Map.entry("duration", "P1D"),
        Map.entry("email", "user@example.com"),
        Map.entry("idn-email", "user@example.com"),
        Map.entry("hostname", "example.com"),
        Map.entry("idn-hostname", "example.com"),
        Map.entry("ipv4", "192.0.2.1"),
        Map.entry("ipv6", "2001:db8::1"),
        Map.entry("uri", "https://example.com/resource"),
        Map.entry("uri-reference", "/resource"),
        Map.entry("iri", "https://example.com/resource"),
        Map.entry("iri-reference", "/resource"),
        Map.entry("uri-template", "/resource/{id}"),
        Map.entry("json-pointer", "/a/b"),
        Map.entry("relative-json-pointer", "0/a"),
        Map.entry("regex", "^[a-z]+$"),
        Map.entry("uuid", "3fa85f64-5717-4562-b3fc-2c963f66afa6"),
        Map.entry("byte", "ZXhhbXBsZQ=="),
        Map.entry("binary", "example"),
        Map.entry("password", "secret"));
    private static final int MAX_DEPTH = 8;

    private final SchemaRegistry registry;
    private final SchemaShapes shapes;
    private final SchemaValidator validator;

    public ExampleGenerator(SchemaRegistry registry) {
        this.registry = registry;
        this.shapes = new SchemaShapes(registry);
        this.validator = new SchemaValidator(registry);
    }

    /** An example for {@code schema} that is valid in {@code direction}, if one can be derived. */
    public Optional<JsonValue> example(Schema schema, Direction direction) {
        var value = generate(schema, direction, 0, new HashSet<>(), List.of());
        return validator.validate(schema, value, direction).isEmpty() ? Optional.of(value) : Optional.empty();
    }

    /**
     * {@code scope} lists the schemas entered so far that declare dynamic anchors, outermost first:
     * a {@code $dynamicRef} uses the outermost binding, as validation does.
     */
    private JsonValue generate(Schema schema, Direction direction, int depth, Set<String> active, List<Schema> scope) {
        var bound = schema.dynamicRef().flatMap(ref -> scope.stream().flatMap(s -> dynamicAnchor(s, ref.replaceFirst("^#", "")).stream()).findFirst());
        if (bound.isPresent()) {
            return generate(bound.get(), direction, depth, active, scope);
        }
        var resolved = registry.deref(schema);
        var inner = scoped(scoped(scope, schema), resolved);
        var declared = Stream.of(resolved.examples().stream(), resolved.constValue().stream(), resolved.defaultValue().stream(),
                resolved.enumValues().stream().filter(v -> !v.isNull()))
            .flatMap(values -> values)
            .filter(value -> validator.validate(resolved, value, direction).isEmpty())
            .findFirst();
        if (declared.isPresent()) {
            return declared.get();
        }
        var key = resolved.location().toString();
        if (depth > MAX_DEPTH || !active.add(key)) {
            return JsonNull.INSTANCE;
        }
        try {
            var choice = resolved.subschemaList("oneOf").stream().findFirst().or(() -> resolved.subschemaList("anyOf").stream().findFirst());
            if (choice.isPresent()) {
                return withDiscriminator(resolved, choice.get(), generate(choice.get(), direction, depth, active, inner));
            }
            return switch (shapes.shape(resolved)) {
                case OBJECT -> object(resolved, direction, depth, active, inner);
                case ARRAY -> array(resolved, direction, depth, active, inner);
                case PRIMITIVE -> primitive(resolved);
            };
        } finally {
            active.remove(key);
        }
    }

    /** {@code scope} extended by {@code schema} if it declares dynamic anchors (and is not in it yet). */
    private static List<Schema> scoped(List<Schema> scope, Schema schema) {
        if (schema.subschemaMap("$defs").values().stream().noneMatch(definition -> definition.object().has("$dynamicAnchor")) || scope.contains(schema)) {
            return scope;
        }
        var extended = new ArrayList<>(scope);
        extended.add(schema);
        return List.copyOf(extended);
    }

    /** The {@code $defs} entry of {@code schema} declaring the dynamic anchor {@code name}. */
    private static Optional<Schema> dynamicAnchor(Schema schema, String name) {
        return schema.subschemaMap("$defs").values().stream().filter(definition -> definition.object().string("$dynamicAnchor").filter(name::equals)
            .isPresent()).findFirst();
    }

    private JsonValue withDiscriminator(Schema owner, Schema choice, JsonValue value) {
        var discriminator = owner.discriminator();
        if (discriminator.isEmpty() || !(value instanceof JsonObject object)) {
            return value;
        }
        var target = registry.deref(choice).location();
        var name = discriminator.get().mapping().entrySet().stream()
            .filter(e -> registry.resolveMapping(owner, e.getValue()).map(Schema::location).filter(target::equals).isPresent())
            .map(Map.Entry::getKey).findFirst()
            .orElse(choice.ref().map(ref -> ref.substring(ref.lastIndexOf('/') + 1)).orElse(registry.deref(choice).name()));
        return object.with(discriminator.get().propertyName(), JsonString.of(name));
    }

    private JsonValue object(Schema schema, Direction direction, int depth, Set<String> active, List<Schema> scope) {
        var members = new LinkedHashMap<String, JsonValue>();
        var required = new HashSet<String>();
        var closure = shapes.closure(schema);
        closure.forEach(s -> required.addAll(s.required()));
        var precedence = new ArrayList<>(closure.subList(1, closure.size()));
        precedence.add(closure.getFirst());
        for (var name : shapes.propertyNames(schema)) {
            var declarations = precedence.stream().flatMap(s -> Optional.ofNullable(s.subschemaMap("properties").get(name)).stream()).toList();
            var property = declarations.getLast();
            var excluded = declarations.stream().map(registry::deref).anyMatch(declaration -> (direction == Direction.REQUEST && declaration.readOnly())
                || (direction == Direction.RESPONSE && declaration.writeOnly()));
            if (!excluded && (required.contains(name) || depth == 0)) {
                var value = generate(property, direction, depth + 1, active, scope);
                if (!value.isNull() || required.contains(name)) {
                    members.put(name, value);
                }
            }
        }
        return new JsonObject(members);
    }

    private JsonValue array(Schema schema, Direction direction, int depth, Set<String> active, List<Schema> scope) {
        var count = Math.max(1, number(schema, "minItems").map(BigDecimal::intValue).orElse(1));
        var item = shapes.items(schema).map(items -> generate(items, direction, depth + 1, active, scope)).orElse(JsonString.of("item"));
        if (count == 1 || !shapes.closure(schema).stream().anyMatch(s -> s.object().bool("uniqueItems").orElse(false))) {
            return new JsonArray(Collections.nCopies(count, item));
        }
        var values = new ArrayList<JsonValue>();
        for (int i = 0; i < count; i++) {
            values.add(item instanceof JsonNumber n ? JsonNumber.of(n.value().add(BigDecimal.valueOf(i))) : JsonString.of(text(item) + i));
        }
        return new JsonArray(values);
    }

    private static String text(JsonValue value) {
        return value.asString().orElse(value.toJson());
    }

    private JsonValue primitive(Schema schema) {
        var types = shapes.types(schema);
        if (types.contains("integer") || types.contains("number")) {
            return number(schema, types.contains("integer"));
        }
        if (types.contains("boolean")) {
            return JsonBoolean.TRUE;
        }
        if (types.contains("string") || types.isEmpty()) {
            return string(schema);
        }
        return JsonNull.INSTANCE;
    }

    private JsonValue number(Schema schema, boolean integral) {
        var step = integral ? BigDecimal.ONE : new BigDecimal("0.5");
        var min = number(schema, "minimum").map(m -> flag(schema, "exclusiveMinimum") ? m.add(step) : m)
            .or(() -> number(schema, "exclusiveMinimum").map(m -> m.add(step)));
        var max = number(schema, "maximum").map(m -> flag(schema, "exclusiveMaximum") ? m.subtract(step) : m)
            .or(() -> number(schema, "exclusiveMaximum").map(m -> m.subtract(step)));
        var value = min.or(() -> max.map(m -> m.min(BigDecimal.ONE))).orElse(BigDecimal.ONE);
        var multiple = number(schema, "multipleOf");
        if (multiple.isPresent() && multiple.get().signum() > 0) {
            value = value.divide(multiple.get(), 0, RoundingMode.CEILING).multiply(multiple.get());
        }
        return JsonNumber.of(integral ? value.setScale(0, RoundingMode.CEILING) : value);
    }

    /** An OpenAPI 3.0 / draft 4 boolean exclusive bound. */
    private boolean flag(Schema schema, String keyword) {
        return shapes.closure(schema).stream().anyMatch(s -> s.object().bool(keyword).orElse(false));
    }

    private Optional<BigDecimal> number(Schema schema, String keyword) {
        return shapes.closure(schema).stream().flatMap(s -> s.number(keyword).stream()).findFirst();
    }

    private JsonValue string(Schema schema) {
        var format = shapes.closure(schema).stream().flatMap(s -> s.format().stream()).findFirst();
        var text = format.map(FORMATS::get).orElse("example");
        var minLength = number(schema, "minLength").map(BigDecimal::intValue).orElse(0);
        var maxLength = number(schema, "maxLength").map(BigDecimal::intValue);
        if (text.length() < minLength) {
            text = text + "x".repeat(minLength - text.length());
        }
        if (maxLength.isPresent() && text.length() > maxLength.get()) {
            text = text.substring(0, maxLength.get());
        }
        return JsonString.of(text);
    }
}
