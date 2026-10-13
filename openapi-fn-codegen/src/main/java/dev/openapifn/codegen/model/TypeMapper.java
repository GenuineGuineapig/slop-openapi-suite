package dev.openapifn.codegen.model;

import dev.openapifn.codegen.java.Names;
import dev.openapifn.codegen.java.TypeRef;
import dev.openapifn.codegen.java.TypeRef.ClassName;
import dev.openapifn.codegen.model.ModelDef.EnumDef;
import dev.openapifn.codegen.model.ModelDef.ParentDef;
import dev.openapifn.codegen.model.ModelDef.Property;
import dev.openapifn.codegen.model.ModelDef.RecordDef;
import dev.openapifn.codegen.model.ModelDef.UnionDef;
import dev.openapifn.codegen.model.ModelDef.WrapperDef;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.schema.Schema;
import dev.openapifn.schema.SchemaRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps schemas to Java types and collects the classes to generate: records for objects, enums,
 * sealed interfaces for unions and polymorphic parents, and wrappers for non-object union
 * members. Component schemas and every schema reached through {@code $ref} become top-level
 * classes in the model package; inline schemas become classes nested in their owner.
 */
public final class TypeMapper {

    /** The JSpecify nullness annotation used in generated code. */
    public static final ClassName NULLABLE = ClassName.of("org.jspecify.annotations", "Nullable");

    private static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);

    private enum Kind {
        ENUM, UNION, PARENT, OBJECT, MAP, ARRAY, PRIMITIVE, ANY
    }

    private final OpenApiDocument api;
    private final SchemaRegistry registry;
    private final String modelPackage;
    private final Map<URI, String> names = new HashMap<>();
    private final Set<String> takenNames = new HashSet<>();
    private final Map<URI, TypeRef> named = new HashMap<>();
    private final Map<ClassName, ModelDef> definitions = new LinkedHashMap<>();
    private final Map<ClassName, Set<ClassName>> interfaces = new HashMap<>();
    private final Map<URI, List<Schema>> children = new HashMap<>();
    private final Set<URI> inProgress = new HashSet<>();
    private final Map<URI, TypeRef> inline = new HashMap<>();

    public TypeMapper(OpenApiDocument api, String modelPackage) {
        this.api = api;
        this.registry = api.schemas();
        this.modelPackage = modelPackage;
        api.model().components().schemas().forEach((name, schema) -> names.put(registry.deref(schema).location(), Names.unique(Names.pascal(name), takenNames)));
        var visited = new HashSet<URI>();
        api.model().components().schemas().values().forEach(schema -> index(schema, visited));
        api.operations().forEach(operation -> operationSchemas(operation).forEach(schema -> index(schema, visited)));
    }

    /** The schemas an operation uses directly: parameters, bodies, sequential items and headers. */
    private static List<Schema> operationSchemas(dev.openapifn.model.ApiOperation operation) {
        var schemas = new ArrayList<Schema>();
        operation.parameters().forEach(parameter -> parameter.effectiveSchema().ifPresent(schemas::add));
        var mediaTypes = new ArrayList<dev.openapifn.model.MediaType>();
        operation.operation().requestBody().ifPresent(body -> mediaTypes.addAll(body.content().values()));
        var responses = new ArrayList<>(operation.operation().responses().byStatus().values());
        operation.operation().responses().defaultResponse().ifPresent(responses::add);
        responses.forEach(response -> {
            mediaTypes.addAll(response.content().values());
            response.headers().values().forEach(header -> header.schema().ifPresent(schemas::add));
        });
        mediaTypes.forEach(mediaType -> {
            mediaType.schema().ifPresent(schemas::add);
            mediaType.itemSchema().ifPresent(schemas::add);
        });
        return schemas;
    }

    /** Walks everything reachable from {@code schema} to find polymorphic parents and their children. */
    private void index(Schema schema, Set<URI> visited) {
        var resolved = registry.deref(schema);
        if (!visited.add(resolved.location())) {
            return;
        }
        indexChildren(resolved);
        resolved.discriminator().ifPresent(d -> d.mapping().values().forEach(value -> registry.resolveMapping(resolved, value)
            .ifPresent(target -> index(target, visited))));
        for (var keyword : List.of("allOf", "oneOf", "anyOf", "prefixItems")) {
            resolved.subschemaList(keyword).forEach(member -> index(member, visited));
        }
        for (var keyword : List.of("items", "additionalProperties", "not", "if", "then", "else", "contains", "unevaluatedProperties", "unevaluatedItems")) {
            resolved.subschema(keyword).ifPresent(member -> index(member, visited));
        }
        for (var keyword : List.of("properties", "patternProperties", "$defs", "definitions", "dependentSchemas")) {
            resolved.subschemaMap(keyword).values().forEach(member -> index(member, visited));
        }
    }

    /** Defines the classes of all component schemas. */
    public void defineComponents() {
        api.model().components().schemas().values().forEach(schema -> namedType(registry.deref(schema)));
    }

    /** All classes to generate, top-level and nested. */
    public Collection<ModelDef> definitions() {
        return definitions.values();
    }

    /** The definition of a generated class. */
    public Optional<ModelDef> definition(ClassName name) {
        return Optional.ofNullable(definitions.get(name));
    }

    /** The sealed interfaces {@code name} implements. */
    public Set<ClassName> interfacesOf(ClassName name) {
        return interfaces.getOrDefault(name, Set.of());
    }

    public SchemaRegistry registry() {
        return registry;
    }

    // ---------------------------------------------------------------------------------------------
    // Type mapping

    /**
     * The Java type of {@code schema}. Referenced schemas map to their top-level classes; inline
     * schemas that need a class get one nested in {@code owner}, named after {@code hint}.
     */
    public TypeRef type(Schema schema, ClassName owner, String hint) {
        if (schema.ref().isPresent() || schema.dynamicRef().isPresent()) {
            var target = registry.deref(schema);
            return target == schema ? TypeRef.OBJECT : namedType(target);
        }
        var nullFree = nonNullMember(schema);
        if (nullFree.isPresent()) {
            return type(nullFree.get(), owner, hint);
        }
        var known = inline.get(schema.location());
        if (known != null) {
            return known;
        }
        var kind = kind(schema);
        return switch (kind) {
            case ENUM, UNION, OBJECT, PARENT -> {
                var name = uniqueNested(owner, Names.pascal(hint));
                inline.put(schema.location(), name);
                yield define(schema, kind, name);
            }
            default -> structural(schema, kind, owner, hint);
        };
    }

    /** Whether {@code schema} (resolved) admits {@code null}. */
    public boolean nullable(Schema schema) {
        var resolved = registry.deref(schema);
        if (resolved.nullable() || schema.nullable()) {
            return true;
        }
        return choiceMembers(resolved).stream().anyMatch(member -> registry.deref(member).types().equals(Set.of("null")));
    }

    private TypeRef namedType(Schema target) {
        var location = target.location();
        var existing = named.get(location);
        if (existing != null) {
            return existing;
        }
        var kind = kind(target);
        var name = ClassName.of(modelPackage, names.computeIfAbsent(location, uri -> Names.unique(baseName(target), takenNames)));
        if (kind == Kind.ENUM || kind == Kind.UNION || kind == Kind.OBJECT || kind == Kind.PARENT) {
            named.put(location, name);
            return define(target, kind, name);
        }
        if (!inProgress.add(location)) {
            return TypeRef.OBJECT;
        }
        try {
            var type = nonNullMember(target).map(member -> type(member, name, name.simpleName()))
                .orElseGet(() -> structural(target, kind, name, name.simpleName()));
            named.put(location, type);
            return type;
        } finally {
            inProgress.remove(location);
        }
    }

    private String baseName(Schema schema) {
        var last = schema.pointer().last();
        if (last.isPresent() && !last.get().matches("[0-9]+")) {
            return Names.pascal(last.get());
        }
        var path = schema.document().getPath();
        var file = path == null ? "Model" : path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.[^.]*$", "");
        return Names.pascal(file);
    }

    private ClassName uniqueNested(ClassName owner, String name) {
        var candidate = owner.nested(name);
        for (int i = 2; definitions.containsKey(candidate) || owner.simpleNames().contains(candidate.simpleName()); i++) {
            candidate = owner.nested(name + i);
        }
        return candidate;
    }

    private TypeRef structural(Schema schema, Kind kind, ClassName owner, String hint) {
        return switch (kind) {
            case MAP -> TypeRef.MAP.of(TypeRef.STRING, additional(schema).map(value -> type(value, owner, hint + "Value")).orElse(TypeRef.OBJECT));
            case ARRAY -> {
                var items = closure(schema).stream().flatMap(s -> s.subschema("items").stream()).findFirst();
                var itemType = items.map(item -> type(item, owner, hint + "Item")).orElse(TypeRef.OBJECT);
                var unique = closure(schema).stream().anyMatch(s -> s.object().bool("uniqueItems").orElse(false));
                yield (unique ? TypeRef.SET : TypeRef.LIST).of(itemType);
            }
            case PRIMITIVE -> primitive(schema);
            default -> TypeRef.OBJECT;
        };
    }

    private TypeRef primitive(Schema schema) {
        var types = new LinkedHashSet<>(closureTypes(schema));
        types.remove("null");
        var format = closure(schema).stream().flatMap(s -> s.format().stream()).findFirst().orElse("");
        return switch (types.iterator().next()) {
            case "integer" -> format.equals("int64") || !fitsInt(schema) ? TypeRef.LONG : TypeRef.INTEGER;
            case "number" -> switch (format) {
                case "float" -> TypeRef.FLOAT;
                case "double" -> TypeRef.DOUBLE;
                case "int32" -> TypeRef.INTEGER;
                case "int64" -> TypeRef.LONG;
                default -> TypeRef.BIG_DECIMAL;
            };
            case "boolean" -> TypeRef.BOOLEAN;
            case "string" -> switch (format) {
                case "date" -> TypeRef.LOCAL_DATE;
                case "date-time" -> TypeRef.OFFSET_DATE_TIME;
                case "time" -> TypeRef.OFFSET_TIME;
                case "duration" -> TypeRef.DURATION;
                case "uuid" -> TypeRef.UUID;
                case "uri", "uri-reference", "iri", "iri-reference" -> TypeRef.URI;
                case "byte" -> TypeRef.BYTES;
                case "binary" -> ClassName.of("dev.openapifn.spring", "FilePart");
                default -> TypeRef.STRING;
            };
            default -> TypeRef.OBJECT;
        };
    }

    private boolean fitsInt(Schema schema) {
        var bounds = closure(schema).stream().flatMap(s -> java.util.stream.Stream.of(s.number("minimum"), s.number("maximum"),
            s.number("exclusiveMinimum"), s.number("exclusiveMaximum"))).flatMap(Optional::stream).toList();
        return bounds.stream().allMatch(b -> b.compareTo(INT_MIN) >= 0 && b.compareTo(INT_MAX) <= 0);
    }

    // ---------------------------------------------------------------------------------------------
    // Classification

    private Kind kind(Schema schema) {
        if (enumerable(schema)) {
            return Kind.ENUM;
        }
        if (schema.subschemaMap("properties").isEmpty() && choiceMembers(schema).stream().anyMatch(this::typed)) {
            return Kind.UNION;
        }
        if (isParent(schema)) {
            return Kind.PARENT;
        }
        var members = closure(schema);
        var types = closureTypes(schema);
        var objectLike = types.contains("object") || members.stream().anyMatch(s -> s.has("properties") || s.has("additionalProperties")
            || s.has("patternProperties") || s.has("required"));
        if (objectLike) {
            return members.stream().anyMatch(s -> !s.subschemaMap("properties").isEmpty()) ? Kind.OBJECT : Kind.MAP;
        }
        if (types.contains("array") || members.stream().anyMatch(s -> s.has("items") || s.has("prefixItems"))) {
            return Kind.ARRAY;
        }
        var nonNull = new HashSet<>(types);
        nonNull.remove("null");
        return nonNull.size() == 1 ? Kind.PRIMITIVE : Kind.ANY;
    }

    /** A single non-null member of {@code oneOf}/{@code anyOf} with nothing else around it (the nullable-reference idiom). */
    private Optional<Schema> nonNullMember(Schema schema) {
        var choices = choiceMembers(schema);
        var nonNull = choices.stream().filter(this::typed).toList();
        var allOf = schema.subschemaList("allOf");
        if (nonNull.size() == 1 && choices.size() > nonNull.size() && !schema.has("properties")) {
            return Optional.of(nonNull.getFirst());
        }
        if (choices.isEmpty() && allOf.size() == 1 && !schema.has("properties") && !schema.has("type") && !schema.has("discriminator")) {
            return Optional.of(allOf.getFirst());
        }
        return Optional.empty();
    }

    private static List<Schema> choiceMembers(Schema schema) {
        var oneOf = schema.subschemaList("oneOf");
        return oneOf.isEmpty() ? schema.subschemaList("anyOf") : oneOf;
    }

    /** Whether a union member describes a type rather than only adding constraints. */
    private boolean typed(Schema member) {
        if (isNull(member)) {
            return false;
        }
        var resolved = registry.deref(member);
        return member.ref().isPresent() || !resolved.types().isEmpty() || resolved.has("properties") || resolved.has("items")
            || resolved.has("enum") || resolved.has("const") || !choiceMembers(resolved).isEmpty() || resolved.has("allOf");
    }

    private boolean isNull(Schema schema) {
        return registry.deref(schema).types().equals(Set.of("null"));
    }

    private boolean enumerable(Schema schema) {
        var values = schema.enumValues().stream().filter(value -> !value.isNull()).toList();
        return !values.isEmpty() && (values.stream().allMatch(value -> value instanceof JsonString)
            || values.stream().allMatch(value -> value instanceof JsonNumber number && number.isIntegral()));
    }

    private boolean isParent(Schema schema) {
        return schema.discriminator().isPresent() && children.containsKey(schema.location());
    }

    /** The schema and its {@code allOf} members, recursively and resolved. */
    private List<Schema> closure(Schema schema) {
        var result = new ArrayList<Schema>();
        collect(schema, result, new HashSet<>());
        return result;
    }

    private void collect(Schema schema, List<Schema> result, Set<URI> seen) {
        var resolved = registry.deref(schema);
        if (seen.add(resolved.location())) {
            result.add(resolved);
            resolved.subschemaList("allOf").forEach(member -> collect(member, result, seen));
        }
    }

    private Set<String> closureTypes(Schema schema) {
        var types = new LinkedHashSet<String>();
        closure(schema).forEach(s -> types.addAll(s.types()));
        return types;
    }

    private Optional<Schema> additional(Schema schema) {
        return closure(schema).stream().flatMap(s -> s.subschema("additionalProperties").stream()).filter(s -> !s.isBooleanSchema()).findFirst();
    }

    private void indexChildren(Schema resolved) {
        for (var member : resolved.subschemaList("allOf")) {
            if (member.ref().isPresent()) {
                var parent = registry.deref(member);
                if (parent.discriminator().isPresent() && parent.subschemaList("oneOf").isEmpty() && parent.subschemaList("anyOf").isEmpty()) {
                    children.computeIfAbsent(parent.location(), uri -> new ArrayList<>()).add(resolved);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Definitions

    private TypeRef define(Schema schema, Kind kind, ClassName name) {
        if (definitions.containsKey(name)) {
            return name;
        }
        var placeholder = new RecordDef(name, schema, List.of(), Optional.empty());
        definitions.put(name, placeholder);
        var definition = switch (kind) {
            case ENUM -> enumDef(schema, name);
            case UNION -> unionDef(schema, name);
            case PARENT -> parentDef(schema, name);
            default -> recordDef(schema, name);
        };
        definitions.put(name, definition);
        return name;
    }

    private EnumDef enumDef(Schema schema, ClassName name) {
        var values = schema.enumValues().stream().filter(value -> !value.isNull()).toList();
        var valueType = values.getFirst() instanceof JsonString ? TypeRef.STRING : primitive(schema);
        var constants = new LinkedHashMap<String, JsonValue>();
        var taken = new HashSet<String>();
        for (var value : values) {
            var text = value.asString().orElse(value.toJson());
            var constant = Names.constant(text);
            constants.put(Names.unique(constant.startsWith("_") ? "VALUE" + constant : constant, taken), value);
        }
        return new EnumDef(name, schema, valueType.equals(TypeRef.OBJECT) ? TypeRef.LONG : valueType, constants);
    }

    private RecordDef recordDef(Schema schema, ClassName name) {
        return new RecordDef(name, schema, properties(schema, name), additional(schema).map(value -> type(value, name, "AdditionalProperty"))
            .or(() -> closure(schema).stream().anyMatch(s -> s.keyword("additionalProperties").filter(v -> v.equals(dev.openapifn.json.JsonBoolean.TRUE)).isPresent())
                ? Optional.of(TypeRef.OBJECT) : Optional.empty()));
    }

    private List<Property> properties(Schema schema, ClassName owner) {
        var members = closure(schema);
        var self = registry.deref(schema).location();
        var required = new HashSet<String>();
        members.forEach(member -> required.addAll(member.required()));
        var declared = new LinkedHashMap<String, Schema>();
        var declaringOwner = new HashMap<String, ClassName>();
        for (var member : members.reversed()) {
            var memberOwner = member.location().equals(self) ? owner : ownerOf(member).orElse(owner);
            member.subschemaMap("properties").forEach((key, property) -> {
                declared.put(key, property);
                declaringOwner.put(key, memberOwner);
            });
        }
        var ordered = new LinkedHashMap<String, Schema>();
        members.forEach(member -> member.subschemaMap("properties").keySet().forEach(key -> ordered.putIfAbsent(key, declared.get(key))));
        var javaNames = new HashSet<String>(List.of("builder", "with", "additionalProperties", "hashCode", "toString", "equals", "getClass"));
        var properties = new ArrayList<Property>();
        ordered.forEach((jsonName, property) -> {
            var javaName = Names.unique(Names.camel(jsonName), javaNames);
            var type = type(property, declaringOwner.get(jsonName), Names.pascal(jsonName));
            var resolved = registry.deref(property);
            var nonNull = required.contains(jsonName) && !resolved.readOnly() && !resolved.writeOnly() && !nullable(property);
            properties.add(new Property(jsonName, javaName, type, required.contains(jsonName), !nonNull, property));
        });
        for (var member : members) {
            if (!member.location().equals(self) && isParent(member) && namedType(member) instanceof ClassName parent) {
                interfaces.computeIfAbsent(owner, key -> new LinkedHashSet<>()).add(parent);
            }
        }
        return properties;
    }

    /** The generated class of a named schema composed into another one, which owns its inline property types. */
    private Optional<ClassName> ownerOf(Schema member) {
        return names.containsKey(member.location()) && namedType(member) instanceof ClassName className ? Optional.of(className) : Optional.empty();
    }

    private ParentDef parentDef(Schema schema, ClassName name) {
        var discriminator = schema.discriminator().orElseThrow();
        var subtypes = new LinkedHashMap<ClassName, List<String>>();
        for (var child : children.get(schema.location())) {
            if (namedType(child) instanceof ClassName childName) {
                subtypes.put(childName, discriminatorNames(schema, child));
                interfaces.computeIfAbsent(childName, key -> new LinkedHashSet<>()).add(name);
            }
        }
        var defaultName = name.nested("Default");
        subtypes.put(defaultName, List.of(baseName(schema)));
        var properties = properties(schema, name);
        definitions.put(defaultName, new RecordDef(defaultName, schema, properties, Optional.empty()));
        interfaces.computeIfAbsent(defaultName, key -> new LinkedHashSet<>()).add(name);
        return new ParentDef(name, schema, properties, discriminator.propertyName(), subtypes);
    }

    private List<String> discriminatorNames(Schema owner, Schema member) {
        var target = registry.deref(member).location();
        var mapped = owner.discriminator().orElseThrow().mapping().entrySet().stream()
            .filter(e -> registry.resolveMapping(owner, e.getValue()).map(s -> s.location()).filter(target::equals).isPresent())
            .map(Map.Entry::getKey).toList();
        return mapped.isEmpty() ? List.of(baseName(registry.deref(member))) : mapped;
    }

    private UnionDef unionDef(Schema schema, ClassName name) {
        var exactlyOne = !schema.subschemaList("oneOf").isEmpty();
        var members = new ArrayList<ClassName>();
        var discriminatorNames = new LinkedHashMap<ClassName, List<String>>();
        var index = 1;
        for (var member : choiceMembers(schema)) {
            if (!typed(member)) {
                continue;
            }
            var type = type(member, name, "Option" + index++);
            var memberClass = type instanceof ClassName className && definitions.containsKey(className) ? className : wrapper(name, member, type);
            if (!members.contains(memberClass)) {
                members.add(memberClass);
                interfaces.computeIfAbsent(memberClass, key -> new LinkedHashSet<>()).add(name);
                schema.discriminator().ifPresent(d -> discriminatorNames.put(memberClass, discriminatorNames(schema, member)));
            }
        }
        return new UnionDef(name, schema, members, schema.discriminator().map(d -> d.propertyName()), discriminatorNames, exactlyOne);
    }

    private ClassName wrapper(ClassName union, Schema member, TypeRef type) {
        var base = switch (type) {
            case ClassName className -> className.simpleName();
            case TypeRef.Parameterized parameterized -> parameterized.raw().simpleName();
            default -> "Bytes";
        };
        var name = uniqueNested(union, base + "Value");
        definitions.put(name, new WrapperDef(name, member, type));
        return name;
    }
}
