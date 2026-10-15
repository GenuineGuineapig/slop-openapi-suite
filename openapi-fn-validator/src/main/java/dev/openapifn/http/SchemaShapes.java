package dev.openapifn.http;

import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.schema.Schema;
import dev.openapifn.schema.SchemaRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural questions about schemas that serialized (string) values are interpreted against:
 * whether a value is an array, an object or a primitive, what the item and property schemas
 * are, and how a string is converted to the JSON type the schema expects.
 */
public final class SchemaShapes {

    /** The structural shape of a value. */
    public enum Shape {
        PRIMITIVE,
        ARRAY,
        OBJECT
    }

    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final List<String> COMPOSITIONS = List.of("allOf", "anyOf", "oneOf");

    private final SchemaRegistry registry;

    public SchemaShapes(SchemaRegistry registry) {
        this.registry = registry;
    }

    /** The schema itself and every schema it composes ({@code $ref}, {@code allOf}, {@code anyOf}, {@code oneOf}), in order. */
    public List<Schema> closure(Schema schema) {
        var result = new ArrayList<Schema>();
        collect(schema, result, new HashSet<>());
        return result;
    }

    private void collect(Schema schema, List<Schema> result, Set<java.net.URI> seen) {
        var resolved = registry.deref(schema);
        if (!seen.add(resolved.location())) {
            return;
        }
        result.add(resolved);
        COMPOSITIONS.forEach(keyword -> resolved.subschemaList(keyword).forEach(member -> collect(member, result, seen)));
    }

    /** The JSON types the schema (or its compositions) declares; empty when unconstrained. */
    public Set<String> types(Schema schema) {
        var types = new LinkedHashSet<String>();
        closure(schema).forEach(member -> types.addAll(member.types()));
        return types;
    }

    public Shape shape(Schema schema) {
        var types = types(schema);
        if (types.contains("array")) {
            return Shape.ARRAY;
        }
        if (types.contains("object")) {
            return Shape.OBJECT;
        }
        if (!types.isEmpty()) {
            return Shape.PRIMITIVE;
        }
        var members = closure(schema);
        if (members.stream().anyMatch(s -> s.has("items") || s.has("prefixItems"))) {
            return Shape.ARRAY;
        }
        return members.stream().anyMatch(s -> s.has("properties") || s.has("additionalProperties") || s.has("patternProperties"))
            ? Shape.OBJECT : Shape.PRIMITIVE;
    }

    /** The schema of array items, if declared. */
    public Optional<Schema> items(Schema schema) {
        return closure(schema).stream().flatMap(s -> s.subschema("items").stream()).findFirst();
    }

    /** The schema of the array item at {@code index}: its {@code prefixItems} entry, else {@code items}. */
    public Optional<Schema> item(Schema schema, int index) {
        return closure(schema).stream().map(s -> s.subschemaList("prefixItems")).filter(prefix -> index < prefix.size()).map(prefix -> prefix.get(index))
            .findFirst().or(() -> items(schema));
    }

    /** The schema of property {@code name}: declared properties first, then {@code additionalProperties}. */
    public Optional<Schema> property(Schema schema, String name) {
        var members = closure(schema);
        return members.stream().flatMap(s -> Optional.ofNullable(s.subschemaMap("properties").get(name)).stream()).findFirst()
            .or(() -> members.stream().flatMap(s -> s.subschema("additionalProperties").stream()).findFirst());
    }

    /** Names of the declared properties across all compositions. */
    public Set<String> propertyNames(Schema schema) {
        var names = new LinkedHashSet<String>();
        closure(schema).forEach(s -> names.addAll(s.subschemaMap("properties").keySet()));
        return names;
    }

    /**
     * Converts a serialized value to the JSON type the schema expects: integers, numbers and
     * booleans are parsed when allowed and well-formed; anything else stays a string so that
     * schema validation reports the mismatch.
     */
    public JsonValue coerce(String raw, Optional<Schema> schema) {
        var types = schema.map(this::types).orElse(Set.of());
        if (types.contains("integer") && INTEGER.matcher(raw).matches()) {
            return JsonNumber.of(new BigDecimal(raw));
        }
        if (types.contains("number") && NUMBER.matcher(raw).matches()) {
            return JsonNumber.of(new BigDecimal(raw));
        }
        if (types.contains("boolean") && (raw.equals("true") || raw.equals("false"))) {
            return JsonBoolean.of(raw.equals("true"));
        }
        return JsonString.of(raw);
    }
}
