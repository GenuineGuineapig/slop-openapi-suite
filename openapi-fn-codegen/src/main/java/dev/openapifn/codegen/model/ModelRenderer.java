package dev.openapifn.codegen.model;

import dev.openapifn.codegen.java.Imports;
import dev.openapifn.codegen.java.Names;
import dev.openapifn.codegen.java.Source;
import dev.openapifn.codegen.java.TypeRef;
import dev.openapifn.codegen.java.TypeRef.ClassName;
import dev.openapifn.codegen.model.ModelDef.EnumDef;
import dev.openapifn.codegen.model.ModelDef.ParentDef;
import dev.openapifn.codegen.model.ModelDef.Property;
import dev.openapifn.codegen.model.ModelDef.RecordDef;
import dev.openapifn.codegen.model.ModelDef.UnionDef;
import dev.openapifn.codegen.model.ModelDef.WrapperDef;
import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Renders {@link ModelDef}s as Java source. */
public final class ModelRenderer {

    public static final ClassName GENERATED = ClassName.of("javax.annotation.processing", "Generated");
    private static final ClassName JSON_PROPERTY = ClassName.of("com.fasterxml.jackson.annotation", "JsonProperty");
    private static final ClassName JSON_INCLUDE = ClassName.of("com.fasterxml.jackson.annotation", "JsonInclude");
    private static final ClassName JSON_VALUE = ClassName.of("com.fasterxml.jackson.annotation", "JsonValue");
    private static final ClassName JSON_CREATOR = ClassName.of("com.fasterxml.jackson.annotation", "JsonCreator");
    private static final ClassName JSON_TYPE_INFO = ClassName.of("com.fasterxml.jackson.annotation", "JsonTypeInfo");
    private static final ClassName JSON_SUB_TYPES = ClassName.of("com.fasterxml.jackson.annotation", "JsonSubTypes");
    private static final ClassName JSON_ANY_SETTER = ClassName.of("com.fasterxml.jackson.annotation", "JsonAnySetter");
    private static final ClassName JSON_ANY_GETTER = ClassName.of("com.fasterxml.jackson.annotation", "JsonAnyGetter");
    private static final ClassName JSON_DESERIALIZE = ClassName.of("tools.jackson.databind.annotation", "JsonDeserialize");
    private static final ClassName VALUE_DESERIALIZER = ClassName.of("tools.jackson.databind", "ValueDeserializer");
    private static final ClassName UNION_DESERIALIZER = ClassName.of("dev.openapifn.spring.json", "UnionDeserializer");
    private static final ClassName OBJECTS = ClassName.of("java.util", "Objects");
    private static final ClassName COLLECTIONS = ClassName.of("java.util", "Collections");
    private static final ClassName ARRAY_LIST = ClassName.of("java.util", "ArrayList");
    private static final ClassName LINKED_HASH_SET = ClassName.of("java.util", "LinkedHashSet");
    private static final ClassName LINKED_HASH_MAP = ClassName.of("java.util", "LinkedHashMap");
    private static final ClassName NULLABLE = TypeMapper.NULLABLE;
    private static final ClassName BASE64 = ClassName.of("java.util", "Base64");

    /** Types whose defaults are parsed from strings: the factory method and a parser that rejects invalid values at generation time. */
    private static final Map<TypeRef, Map.Entry<String, Function<String, Object>>> PARSED = Map.of(
        TypeRef.LOCAL_DATE, Map.entry("parse", LocalDate::parse),
        TypeRef.OFFSET_DATE_TIME, Map.entry("parse", OffsetDateTime::parse),
        TypeRef.OFFSET_TIME, Map.entry("parse", OffsetTime::parse),
        TypeRef.DURATION, Map.entry("parse", Duration::parse),
        TypeRef.UUID, Map.entry("fromString", UUID::fromString),
        TypeRef.URI, Map.entry("create", URI::create));

    private final TypeMapper mapper;

    public ModelRenderer(TypeMapper mapper) {
        this.mapper = mapper;
    }

    /** The complete compilation unit of a top-level definition. */
    public String file(ModelDef definition) {
        var name = definition.name();
        var imports = new Imports(name, nestedNames(name));
        var body = new Source();
        render(definition, body, imports, true);
        return "package " + name.packageName() + ";\n\n" + imports.declarations() + "\n\n" + body;
    }

    /** Simple names of all types nested (at any depth) in {@code owner}. */
    public Set<String> nestedNames(ClassName owner) {
        return mapper.definitions().stream().map(ModelDef::name)
            .filter(name -> name.isNested() && name.simpleNames().getFirst().equals(owner.simpleNames().getFirst())
                && name.packageName().equals(owner.packageName()))
            .flatMap(name -> name.simpleNames().subList(1, name.simpleNames().size()).stream())
            .collect(Collectors.toSet());
    }

    /** Renders the definitions directly nested in {@code owner}. */
    public void renderNested(ClassName owner, Source out, Imports imports) {
        mapper.definitions().stream()
            .filter(definition -> definition.name().simpleNames().size() == owner.simpleNames().size() + 1
                && definition.name().simpleNames().subList(0, owner.simpleNames().size()).equals(owner.simpleNames())
                && definition.name().packageName().equals(owner.packageName()))
            .toList()
            .forEach(definition -> {
                out.blank();
                render(definition, out, imports, false);
            });
    }

    private void render(ModelDef definition, Source out, Imports imports, boolean topLevel) {
        out.javadoc(description(definition));
        if (topLevel) {
            out.line("@" + imports.ref(GENERATED) + "(\"openapi-fn\")");
        }
        if (definition.schema().deprecated()) {
            out.line("@Deprecated");
        }
        ownDeserialization(definition, out, imports);
        switch (definition) {
            case RecordDef record -> record(record, out, imports);
            case EnumDef enumDef -> enumeration(enumDef, out, imports);
            case UnionDef union -> union(union, out, imports);
            case ParentDef parent -> parent(parent, out, imports);
            case WrapperDef wrapper -> wrapper(wrapper, out, imports);
        }
    }

    /**
     * Jackson inherits class annotations from interfaces, so a type that is also read on its own
     * opts out of the union deserializer and the polymorphic type handling of its unions and parents.
     */
    private void ownDeserialization(ModelDef definition, Source out, Imports imports) {
        var inherited = ancestors(definition.name()).map(mapper::definition).flatMap(Optional::stream).toList();
        if (!hasCustomDeserializer(definition) && inherited.stream().anyMatch(ModelRenderer::hasCustomDeserializer)) {
            out.line("@" + imports.ref(JSON_DESERIALIZE) + "(using = " + imports.ref(VALUE_DESERIALIZER) + ".None.class)");
        }
        if (!hasTypeInfo(definition) && inherited.stream().anyMatch(ModelRenderer::hasTypeInfo)) {
            out.line("@" + imports.ref(JSON_TYPE_INFO) + "(use = " + imports.ref(JSON_TYPE_INFO) + ".Id.NONE)");
        }
    }

    private Stream<ClassName> ancestors(ClassName name) {
        return mapper.interfacesOf(name).stream().flatMap(parent -> Stream.concat(Stream.of(parent), ancestors(parent)));
    }

    private static boolean hasCustomDeserializer(ModelDef definition) {
        return definition instanceof UnionDef union && union.discriminator().isEmpty();
    }

    private static boolean hasTypeInfo(ModelDef definition) {
        return definition instanceof ParentDef || definition instanceof UnionDef union && union.discriminator().isPresent();
    }

    private static String description(ModelDef definition) {
        var schema = definition.schema();
        return Stream.of(schema.title(), schema.description()).flatMap(Optional::stream).collect(Collectors.joining("\n\n"));
    }

    private String implementsClause(ClassName name, Imports imports, String keyword) {
        var interfaces = mapper.interfacesOf(name);
        return interfaces.isEmpty() ? "" : " " + keyword + " " + interfaces.stream().map(imports::ref).collect(Collectors.joining(", "));
    }

    // ---------------------------------------------------------------------------------------------
    // Records

    private void record(RecordDef record, Source out, Imports imports) {
        var name = record.name();
        var components = new ArrayList<String>();
        for (var property : record.properties()) {
            var resolved = mapper.registry().deref(property.schema());
            var include = property.required() && property.nullable() && !resolved.readOnly() && !resolved.writeOnly()
                ? "@" + imports.ref(JSON_INCLUDE) + "(" + imports.ref(JSON_INCLUDE) + ".Include.ALWAYS) " : "";
            components.add(include + "@" + imports.ref(JSON_PROPERTY) + "(" + Names.literal(property.jsonName()) + ") " + type(property, imports)
                + " " + property.javaName());
        }
        record.additionalProperties().ifPresent(value -> components.add("@" + imports.ref(JSON_ANY_SETTER) + " @" + imports.ref(JSON_ANY_GETTER) + " "
            + imports.ref(TypeRef.MAP.of(TypeRef.STRING, value), NULLABLE) + " additionalProperties"));
        out.line("@" + imports.ref(JSON_INCLUDE) + "(" + imports.ref(JSON_INCLUDE) + ".Include.NON_NULL)");
        var header = "public record " + name.simpleName() + record.generics() + "(" + (components.isEmpty() ? "" : "\n        " + String.join(",\n        ", components)) + ")"
            + implementsClause(name, imports, "implements");
        out.open(header);
        compactConstructor(record, out, imports);
        var parameters = new ArrayList<String>();
        record.properties().forEach(p -> parameters.add(p.javaName()));
        record.additionalProperties().ifPresent(v -> parameters.add("additionalProperties"));
        var arguments = String.join(", ", parameters);
        if (!record.properties().isEmpty()) {
            out.blank().line("/** A builder for {@code " + name.simpleName() + "}. */");
            out.open("public static " + (record.generics().isEmpty() ? "" : record.generics() + " ") + "Builder" + record.generics() + " builder()")
                .line("return new Builder" + record.diamond() + "();").close();
            for (var property : record.properties()) {
                out.blank().line("/** A copy with a different {@code " + property.javaName() + "}. */");
                out.open("public " + name.simpleName() + record.generics() + " with" + Character.toUpperCase(property.javaName().charAt(0))
                    + property.javaName().substring(1) + "(" + type(property, imports) + " " + property.javaName() + ")");
                out.line("return new " + name.simpleName() + record.diamond() + "(" + arguments + ");").close();
            }
            builder(record, out, imports, arguments);
        }
        renderNested(name, out, imports);
        out.close();
    }

    private void compactConstructor(RecordDef record, Source out, Imports imports) {
        var statements = new ArrayList<String>();
        for (var property : record.properties()) {
            var name = property.javaName();
            defaultLiteral(property, imports).ifPresent(literal -> statements.add("if (" + name + " == null) {\n    " + name + " = " + literal + ";\n}"));
            if (property.required() && !property.nullable()) {
                statements.add(imports.ref(OBJECTS) + ".requireNonNull(" + name + ", " + Names.literal(name) + ");");
            }
            copy(property.type(), name, property.nullable(), imports).ifPresent(statements::add);
        }
        record.additionalProperties().ifPresent(v -> statements.add("additionalProperties = additionalProperties == null ? new " + imports.ref(LINKED_HASH_MAP)
            + "<>() : new " + imports.ref(LINKED_HASH_MAP) + "<>(additionalProperties);"));
        if (statements.isEmpty()) {
            return;
        }
        out.blank().open("public " + record.name().simpleName());
        statements.forEach(statement -> statement.lines().forEach(out::line));
        out.close();
    }

    /** An immutable copy of a collection-valued property (tolerating {@code null} elements). */
    private static Optional<String> copy(TypeRef type, String name, boolean nullable, Imports imports) {
        if (!(type instanceof TypeRef.Parameterized parameterized)) {
            return Optional.empty();
        }
        var raw = parameterized.raw();
        var collections = imports.ref(COLLECTIONS);
        var copy = raw.equals(TypeRef.LIST) ? collections + ".unmodifiableList(new " + imports.ref(ARRAY_LIST) + "<>(" + name + "))"
            : raw.equals(TypeRef.SET) ? collections + ".unmodifiableSet(new " + imports.ref(LINKED_HASH_SET) + "<>(" + name + "))"
            : collections + ".unmodifiableMap(new " + imports.ref(LINKED_HASH_MAP) + "<>(" + name + "))";
        return Optional.of(name + " = " + (nullable ? name + " == null ? null : " : "") + copy + ";");
    }

    /** A Java literal for a property's {@code default}, when it has a simple type. */
    /** A Java literal a {@code null} component is replaced with: its {@code const}, else (when optional) its {@code default}. */
    private Optional<String> defaultLiteral(Property property, Imports imports) {
        return property.schema().constValue().or(() -> property.required() ? Optional.empty() : property.defaultValue())
            .flatMap(value -> literal(value, property.type(), imports));
    }

    Optional<String> literal(JsonValue value, TypeRef type, Imports imports) {
        if (type instanceof ClassName className && mapper.definition(className).filter(EnumDef.class::isInstance).isPresent()) {
            var enumDef = (EnumDef) mapper.definition(className).orElseThrow();
            return enumDef.constants().entrySet().stream().filter(e -> e.getValue().equals(value)).findFirst()
                .map(e -> imports.ref(className) + "." + e.getKey());
        }
        return switch (value) {
            case JsonString string when type.equals(TypeRef.STRING) -> Optional.of(Names.literal(string.value()));
            case JsonString string when PARSED.containsKey(type) && parses(PARSED.get(type).getValue(), string.value()) ->
                Optional.of(imports.ref(type) + "." + PARSED.get(type).getKey() + "(" + Names.literal(string.value()) + ")");
            case JsonString string when type.equals(TypeRef.BYTES) && parses(Base64.getDecoder()::decode, string.value()) ->
                Optional.of(imports.ref(BASE64) + ".getDecoder().decode(" + Names.literal(string.value()) + ")");
            case JsonBoolean bool when type.equals(TypeRef.BOOLEAN) -> Optional.of(Boolean.toString(bool.value()));
            case JsonNumber number when type.equals(TypeRef.INTEGER) && number.isIntegral() -> Optional.of(number.value().toBigInteger().toString());
            case JsonNumber number when type.equals(TypeRef.LONG) && number.isIntegral() -> Optional.of(number.value().toBigInteger() + "L");
            case JsonNumber number when type.equals(TypeRef.DOUBLE) -> Optional.of(number.value().doubleValue() + "d");
            case JsonNumber number when type.equals(TypeRef.FLOAT) -> Optional.of(number.value().floatValue() + "f");
            case JsonNumber number when type.equals(TypeRef.BIG_DECIMAL) -> Optional.of("new " + imports.ref(TypeRef.BIG_DECIMAL) + "("
                + Names.literal(number.value().toString()) + ")");
            default -> Optional.empty();
        };
    }

    private static boolean parses(Function<String, ?> parser, String text) {
        try {
            parser.apply(text);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void builder(RecordDef record, Source out, Imports imports, String arguments) {
        var name = record.name().simpleName();
        out.blank().line("/** Builds {@code " + name + "} instances; not thread-safe. */");
        out.open("public static final class Builder" + record.generics());
        for (var property : record.properties()) {
            out.line("private " + imports.ref(property.type(), NULLABLE) + " " + property.javaName() + ";");
        }
        record.additionalProperties().ifPresent(value -> out.line("private " + imports.ref(TypeRef.MAP.of(TypeRef.STRING, value), NULLABLE)
            + " additionalProperties;"));
        out.blank().open("private Builder()").close();
        for (var property : record.properties()) {
            out.blank().open("public Builder" + record.generics() + " " + property.javaName() + "(" + imports.ref(property.type(), NULLABLE) + " "
                + property.javaName() + ")");
            out.line("this." + property.javaName() + " = " + property.javaName() + ";").line("return this;").close();
        }
        record.additionalProperties().ifPresent(value -> {
            out.blank().open("public Builder" + record.generics() + " additionalProperties(" + imports.ref(TypeRef.MAP.of(TypeRef.STRING, value), NULLABLE)
                + " additionalProperties)");
            out.line("this.additionalProperties = additionalProperties;").line("return this;").close();
        });
        out.blank().open("public " + name + record.generics() + " build()").line("return new " + name + record.diamond() + "(" + arguments + ");").close();
        out.close();
    }

    private static String type(Property property, Imports imports) {
        return property.nullable() ? imports.ref(property.type(), NULLABLE) : imports.ref(property.type());
    }

    // ---------------------------------------------------------------------------------------------
    // Enums, wrappers, unions and parents

    private void enumeration(EnumDef enumDef, Source out, Imports imports) {
        var name = enumDef.name().simpleName();
        var valueType = imports.ref(enumDef.valueType());
        out.open("public enum " + name + implementsClause(enumDef.name(), imports, "implements"));
        var constants = new ArrayList<String>();
        enumDef.constants().forEach((constant, value) -> constants.add(constant + "(" + literal(value, enumDef.valueType(), imports).orElseThrow() + ")"));
        var names = List.copyOf(enumDef.constants().keySet());
        for (int i = 0; i < constants.size(); i++) {
            Optional.ofNullable(enumDef.descriptions().get(names.get(i))).ifPresent(out::javadoc);
            out.line(constants.get(i) + (i < constants.size() - 1 ? "," : ";"));
        }
        out.blank().line("private final " + valueType + " value;");
        out.blank().open(name + "(" + valueType + " value)").line("this.value = value;").close();
        out.blank().line("/** The JSON value. */").line("@" + imports.ref(JSON_VALUE));
        out.open("public " + valueType + " value()").line("return value;").close();
        out.blank().line("/** The constant for a JSON value. */").line("@" + imports.ref(JSON_CREATOR));
        out.open("public static " + name + " fromValue(" + valueType + " value)");
        out.open("for (var constant : values())").open("if (constant.value.equals(value))").line("return constant;").close().close();
        out.line("throw new IllegalArgumentException(\"Unexpected " + name + " value '\" + value + \"'\");").close();
        out.blank().line("@Override").open("public String toString()").line("return String.valueOf(value);").close();
        out.close();
    }

    private void wrapper(WrapperDef wrapper, Source out, Imports imports) {
        var name = wrapper.name().simpleName();
        out.line("@" + imports.ref(JSON_INCLUDE) + "(" + imports.ref(JSON_INCLUDE) + ".Include.ALWAYS)");
        out.open("public record " + name + "(@" + imports.ref(JSON_VALUE) + " " + imports.ref(wrapper.valueType()) + " value)"
            + implementsClause(wrapper.name(), imports, "implements"));
        out.line("@" + imports.ref(JSON_CREATOR) + "(mode = " + imports.ref(JSON_CREATOR) + ".Mode.DELEGATING)");
        out.open("public " + name).line(imports.ref(OBJECTS) + ".requireNonNull(value, \"value\");").close();
        out.close();
    }

    private void union(UnionDef union, Source out, Imports imports) {
        var name = union.name();
        union.discriminator().ifPresentOrElse(
            property -> typeInfo(out, imports, property, union.names(), mapper.defaultMapping(union.schema()).filter(union.members()::contains)),
            () -> out.line("@" + imports.ref(JSON_DESERIALIZE) + "(using = " + imports.ref(name) + ".Deserializer.class)"));
        out.open("public sealed interface " + name.simpleName() + implementsClause(name, imports, "extends") + " permits "
            + union.members().stream().map(member -> imports.ref(member)).collect(Collectors.joining(", ")));
        if (union.discriminator().isEmpty()) {
            out.blank().line("/** Deserializes the first member type that accepts the JSON. */");
            out.open("final class Deserializer extends " + imports.ref(UNION_DESERIALIZER) + "<" + name.simpleName() + ">");
            out.open("public Deserializer()");
            out.line("super(" + name.simpleName() + ".class, " + union.exactlyOne() + ", " + imports.ref(TypeRef.LIST) + ".of("
                + union.members().stream().map(member -> imports.ref(member) + ".class").collect(Collectors.joining(", ")) + "));");
            out.close().close();
        }
        renderNested(name, out, imports);
        out.close();
    }

    private void parent(ParentDef parent, Source out, Imports imports) {
        var name = parent.name();
        typeInfo(out, imports, parent.discriminator(), parent.subtypes(), mapper.defaultMapping(parent.schema()).filter(parent.subtypes()::containsKey)
            .or(() -> Optional.of(parent.defaultName())));
        out.open("public sealed interface " + name.simpleName() + implementsClause(name, imports, "extends") + " permits "
            + parent.subtypes().keySet().stream().map(imports::ref).collect(Collectors.joining(", ")));
        for (var property : sharedProperties(parent)) {
            out.blank().line(type(property, imports) + " " + property.javaName() + "();");
        }
        renderNested(name, out, imports);
        out.close();
    }

    /** Parent properties that every subtype declares with the same Java type, so they can be interface accessors. */
    private List<Property> sharedProperties(ParentDef parent) {
        return parent.properties().stream().filter(property -> parent.subtypes().keySet().stream().allMatch(subtype -> mapper.definition(subtype)
            .filter(RecordDef.class::isInstance).map(RecordDef.class::cast)
            .filter(record -> record.properties().stream().anyMatch(p -> p.javaName().equals(property.javaName()) && p.type().equals(property.type())
                && p.nullable() == property.nullable()))
            .isPresent())).toList();
    }

    private void typeInfo(Source out, Imports imports, String property, java.util.Map<ClassName, List<String>> names, Optional<ClassName> defaultImpl) {
        var typeInfo = imports.ref(JSON_TYPE_INFO);
        out.line("@" + typeInfo + "(use = " + typeInfo + ".Id.NAME, include = " + typeInfo + ".As.EXISTING_PROPERTY, property = "
            + Names.literal(property) + ", visible = true" + defaultImpl.map(d -> ", defaultImpl = " + imports.ref(d) + ".class").orElse("") + ")");
        var subTypes = imports.ref(JSON_SUB_TYPES);
        out.line("@" + subTypes + "({");
        var entries = new ArrayList<String>();
        names.forEach((type, values) -> entries.add("    @" + subTypes + ".Type(value = " + imports.ref(type) + ".class, names = {"
            + values.stream().map(Names::literal).collect(Collectors.joining(", ")) + "})"));
        out.line(String.join(",\n", entries)).line("})");
    }
}
