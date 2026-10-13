package dev.openapifn.codegen.model;

import dev.openapifn.codegen.java.TypeRef;
import dev.openapifn.codegen.java.TypeRef.ClassName;
import dev.openapifn.json.JsonValue;
import dev.openapifn.schema.Schema;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A Java type to generate for a schema. */
public sealed interface ModelDef {

    ClassName name();

    Schema schema();

    /**
     * A record property.
     *
     * @param jsonName the property name in JSON
     * @param javaName the record component name
     * @param type the Java type
     * @param required whether the property is required
     * @param nullable whether {@code null} is allowed (not required, or nullable)
     * @param schema the property schema
     */
    record Property(String jsonName, String javaName, TypeRef type, boolean required, boolean nullable, Schema schema) {

        public Optional<JsonValue> defaultValue() {
            return schema.defaultValue();
        }
    }

    /**
     * An immutable record for an object schema.
     *
     * @param name class name
     * @param schema the schema
     * @param properties properties in declaration order
     * @param additionalProperties type of additional property values, if they are captured
     */
    record RecordDef(ClassName name, Schema schema, List<Property> properties, Optional<TypeRef> additionalProperties) implements ModelDef {
        public RecordDef {
            properties = List.copyOf(properties);
        }
    }

    /**
     * An enum for a schema with an {@code enum} of strings or integers.
     *
     * @param name class name
     * @param schema the schema
     * @param valueType {@code String}, {@code Integer} or {@code Long}
     * @param constants constant names to JSON values
     */
    record EnumDef(ClassName name, Schema schema, TypeRef valueType, Map<String, JsonValue> constants) implements ModelDef {
        public EnumDef {
            constants = dev.openapifn.fn.Immutable.map(constants);
        }
    }

    /**
     * A sealed interface for {@code oneOf}/{@code anyOf}.
     *
     * @param name interface name
     * @param schema the schema
     * @param members the member types (records, enums or interfaces implementing this one)
     * @param discriminator the discriminator property, if any
     * @param names discriminator values by member type
     * @param exactlyOne whether this is a {@code oneOf}
     */
    record UnionDef(ClassName name, Schema schema, List<ClassName> members, Optional<String> discriminator, Map<ClassName, List<String>> names,
                    boolean exactlyOne) implements ModelDef {
        public UnionDef {
            members = List.copyOf(members);
            names = dev.openapifn.fn.Immutable.map(names);
        }
    }

    /**
     * A wrapper record making a non-object member of a union a subtype ({@code StringValue}).
     *
     * @param name record name (nested in the union)
     * @param schema the member schema
     * @param valueType the wrapped type
     */
    record WrapperDef(ClassName name, Schema schema, TypeRef valueType) implements ModelDef {
    }

    /**
     * A sealed interface for an object schema with a discriminator that other schemas extend
     * with {@code allOf} (OpenAPI 3.0 style inheritance), with a nested {@code Default} record for
     * instances of the parent itself.
     *
     * @param name interface name
     * @param schema the schema
     * @param properties properties of the parent
     * @param discriminator the discriminator property
     * @param subtypes the extending types and the parent's {@code Default}, with their discriminator values
     */
    record ParentDef(ClassName name, Schema schema, List<Property> properties, String discriminator, Map<ClassName, List<String>> subtypes)
        implements ModelDef {
        public ParentDef {
            properties = List.copyOf(properties);
            subtypes = dev.openapifn.fn.Immutable.map(subtypes);
        }

        public ClassName defaultName() {
            return name.nested("Default");
        }
    }
}
