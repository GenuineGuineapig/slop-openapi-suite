package dev.openapifn.generator.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.generator.java.Imports;
import dev.openapifn.generator.java.TypeRef;
import dev.openapifn.generator.java.TypeRef.ClassName;
import dev.openapifn.generator.model.ModelDef.EnumDef;
import dev.openapifn.generator.model.ModelDef.ParentDef;
import dev.openapifn.generator.model.ModelDef.Property;
import dev.openapifn.generator.model.ModelDef.RecordDef;
import dev.openapifn.generator.model.ModelDef.UnionDef;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypeMapperTest {

    private static final String SCHEMAS = """
        Address: {type: object, required: [street], properties: {street: {type: string}}}
        NullableName: {oneOf: [{type: string}, {type: 'null'}]}
        NullableAddress: {anyOf: [{$ref: '#/components/schemas/Address'}, {type: 'null'}]}
        Shape: {oneOf: [{$ref: '#/components/schemas/Address'}, {type: string}]}
        Ext: {type: object, additionalProperties: {type: string}}
        WithExt: {allOf: [{$ref: '#/components/schemas/Ext'}, {properties: {inner: {type: object, properties: {x: {type: string}}}}}]}
        Animal: {type: object, discriminator: {propertyName: kind}, properties: {kind: {type: string}, name: {type: string}}}
        Plain: {allOf: [{$ref: '#/components/schemas/Animal'}]}
        Vehicle: {type: object, discriminator: {propertyName: kind}, properties: {kind: {type: string}, name: {type: string}}}
        Car: {allOf: [{$ref: '#/components/schemas/Vehicle'}]}
        Boat: {allOf: [{$ref: '#/components/schemas/Vehicle'}, {properties: {name: {type: integer}}}]}
        Choice: {allOf: [{$ref: '#/components/schemas/Animal'}], oneOf: [{$ref: '#/components/schemas/Address'}, {type: string}]}
        Loose: {discriminator: {propertyName: k}, anyOf: [{$ref: '#/components/schemas/Address'}], properties: {k: {type: string}}}
        NotChild: {allOf: [{$ref: '#/components/schemas/Loose'}, {properties: {z: {type: string}}}]}
        EmptyParent: {type: object, discriminator: {propertyName: kind}}
        EmptyChild: {allOf: [{$ref: '#/components/schemas/EmptyParent'}], additionalProperties: {type: string}}
        Holder:
          type: object
          required: [nullableName, nullableRef, shape, secret]
          properties:
            nullableName: {$ref: '#/components/schemas/NullableName'}
            nullableRef: {$ref: '#/components/schemas/NullableAddress'}
            shape: {$ref: '#/components/schemas/Shape'}
            secret: {type: string, writeOnly: true}
            patterned: {patternProperties: {'^x': {type: integer}}}
            constrained: {required: [a]}
            propsAndChoice: {properties: {a: {type: string}}, anyOf: [{$ref: '#/components/schemas/Address'}, {type: 'null'}]}
            allOfWithProps: {allOf: [{$ref: '#/components/schemas/Address'}], properties: {extra: {type: string}}}
            allOfWithType: {type: object, allOf: [{$ref: '#/components/schemas/Address'}]}
            allOfWithDiscriminator: {allOf: [{$ref: '#/components/schemas/Address'}], discriminator: {propertyName: street}}
            untypedMember: {oneOf: [{properties: {x: {type: string}}}, {type: integer}]}
            decimals: {type: number, enum: [1.5, 2.5]}
            big: {enum: [1, 5000000000]}
            negative: {enum: [-5000000000, 1]}
            loose: {additionalProperties: {type: integer}}
        """;

    private static TypeMapper mapper(String version, String schemas) {
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
            openapi: %s
            info: {title: t, version: v}
            paths: {}
            components:
              schemas:
            %s""".formatted(version, schemas.indent(4))).orElseThrow(diagnostics -> new AssertionError(diagnostics.toString()));
        var mapper = new TypeMapper(api, "m");
        mapper.defineComponents();
        return mapper;
    }

    private static ClassName model(String name) {
        return ClassName.of("m", name);
    }

    private static <T extends ModelDef> T definition(TypeMapper mapper, ClassName name, Class<T> type) {
        return type.cast(mapper.definition(name).orElseThrow(() -> new AssertionError("No " + name)));
    }

    private static String describe(Property property) {
        var imports = new Imports(model("Holder"), Set.of());
        return (property.nullable() ? "@Nullable " : "") + imports.ref(property.type());
    }

    private static Map<String, String> properties(TypeMapper mapper, String record) {
        var result = new LinkedHashMap<String, String>();
        definition(mapper, model(record), RecordDef.class).properties().forEach(property -> result.put(property.jsonName(), describe(property)));
        return result;
    }

    @Test
    void mapsPropertySchemas() {
        var mapper = mapper("3.1.0", SCHEMAS);
        assertThat(properties(mapper, "Holder")).containsExactly(
            Map.entry("nullableName", "@Nullable String"),
            Map.entry("nullableRef", "@Nullable Address"),
            Map.entry("shape", "Shape"),
            Map.entry("secret", "@Nullable String"),
            Map.entry("patterned", "@Nullable Map<String, Object>"),
            Map.entry("constrained", "@Nullable Map<String, Object>"),
            Map.entry("propsAndChoice", "@Nullable Holder.PropsAndChoice"),
            Map.entry("allOfWithProps", "@Nullable Holder.AllOfWithProps"),
            Map.entry("allOfWithType", "@Nullable Holder.AllOfWithType"),
            Map.entry("allOfWithDiscriminator", "@Nullable Holder.AllOfWithDiscriminator"),
            Map.entry("untypedMember", "@Nullable Holder.UntypedMember"),
            Map.entry("decimals", "@Nullable BigDecimal"),
            Map.entry("big", "@Nullable Holder.Big"),
            Map.entry("negative", "@Nullable Holder.Negative"),
            Map.entry("loose", "@Nullable Map<String, Integer>"));
        var union = model("Holder").nested("UntypedMember");
        assertThat(definition(mapper, union, UnionDef.class).members()).containsExactly(union.nested("Option1"), union.nested("IntegerValue"));
        assertThat(definition(mapper, model("Holder").nested("Big"), EnumDef.class).valueType()).isEqualTo(TypeRef.LONG);
        assertThat(definition(mapper, model("Holder").nested("Negative"), EnumDef.class).valueType()).isEqualTo(TypeRef.LONG);
    }

    @Test
    void composedSchemasOwnTheirInlineTypesUnlessTheyAreNoClass() {
        var mapper = mapper("3.1.0", SCHEMAS);
        assertThat(properties(mapper, "WithExt")).containsExactly(Map.entry("inner", "@Nullable WithExt.Inner"));
        assertThat(definition(mapper, model("WithExt"), RecordDef.class).additionalProperties()).contains(TypeRef.STRING);
    }

    @Test
    void buildsHierarchies() {
        var mapper = mapper("3.1.0", SCHEMAS);
        var animal = definition(mapper, model("Animal"), ParentDef.class);
        assertThat(animal.subtypes()).containsOnlyKeys(model("Plain"), model("Choice"), model("Animal").nested("Default"));
        assertThat(mapper.interfacesOf(model("Plain"))).containsExactly(model("Animal"));
        assertThat(mapper.interfacesOf(model("Choice"))).containsExactly(model("Animal"));
        assertThat(mapper.definition(model("Choice"))).containsInstanceOf(UnionDef.class);
        assertThat(new ModelRenderer(mapper).file(animal)).doesNotContain("kind();");
        var vehicle = definition(mapper, model("Vehicle"), ParentDef.class);
        assertThat(new ModelRenderer(mapper).file(vehicle)).contains("@Nullable String kind();").doesNotContain("name();");
        assertThat(mapper.definition(model("NotChild"))).containsInstanceOf(RecordDef.class);
        assertThat(mapper.interfacesOf(model("NotChild"))).isEmpty();
        var emptyChild = definition(mapper, model("EmptyChild"), RecordDef.class);
        assertThat(emptyChild.properties()).isEmpty();
        assertThat(mapper.interfacesOf(model("EmptyChild"))).containsExactly(model("EmptyParent"));
        assertThat(new ModelRenderer(mapper).file(emptyChild)).contains("public record EmptyChild(", "implements EmptyParent");
    }

    @Test
    void dynamicAnchorsBecomeTypeParameters() {
        var mapper = mapper("3.1.0", """
            Address: {type: object, properties: {street: {type: string}}}
            Page:
              $id: 'https://example.com/Page'
              $defs:
                item: {$dynamicAnchor: item, not: {}}
              type: object
              properties:
                items: {type: array, items: {$dynamicRef: '#item'}}
                first: {$dynamicRef: '#item'}
                meta: {type: object, properties: {inner: {$dynamicRef: '#item'}}}
            Pair:
              $id: 'https://example.com/Pair'
              $defs:
                left: {$dynamicAnchor: left, not: {}}
                right: {$dynamicAnchor: right, type: string}
                unrelated: {type: integer}
              type: object
              properties:
                l: {$dynamicRef: '#left'}
                r: {$dynamicRef: '#right'}
            GenericList:
              $id: 'https://example.com/GenericList'
              $defs: {item: {$dynamicAnchor: item, not: {}}}
              type: array
              items: {$dynamicRef: '#item'}
            Holder:
              type: object
              properties:
                bound:
                  $id: 'https://example.com/AddressPage'
                  $defs: {item: {$dynamicAnchor: item, $ref: 'mem:/api.yaml#/components/schemas/Address'}}
                  $ref: 'https://example.com/Page'
                unbound: {$ref: 'https://example.com/Page'}
                pair:
                  $id: 'https://example.com/IntegerPair'
                  $defs: {right: {$dynamicAnchor: right, type: integer}}
                  $ref: 'https://example.com/Pair'
                list: {$ref: 'https://example.com/GenericList'}
            """);
        var page = definition(mapper, model("Page"), RecordDef.class);
        assertThat(page.typeParameters()).containsExactly("T");
        assertThat(page.generics()).isEqualTo("<T>");
        assertThat(page.diamond()).isEqualTo("<>");
        assertThat(properties(mapper, "Page")).containsExactly(Map.entry("items", "@Nullable List<T>"), Map.entry("first", "@Nullable T"),
            Map.entry("meta", "@Nullable Page.Meta<T>"));
        // An inline record of the template that uses its type variable is generic too.
        var meta = definition(mapper, model("Page").nested("Meta"), RecordDef.class);
        assertThat(meta.typeParameters()).containsExactly("T");
        assertThat(meta.properties().getFirst().type()).isEqualTo(new TypeRef.Variable("T"));
        assertThat(definition(mapper, model("Pair"), RecordDef.class).typeParameters()).containsExactly("TLeft", "TRight");
        assertThat(properties(mapper, "Holder")).containsExactly(
            Map.entry("bound", "@Nullable Page<Address>"),
            Map.entry("unbound", "@Nullable Page<Object>"),
            Map.entry("pair", "@Nullable Pair<Object, Integer>"),
            Map.entry("list", "@Nullable List<Object>"));
        assertThat(new ModelRenderer(mapper).file(page)).contains("public record Page<T>(", "public static <T> Builder<T> builder()",
            "return new Builder<>();", "public Page<T> withFirst(@Nullable T first)", "return new Page<>(items, first, meta);",
            "public static final class Builder<T>", "public Builder<T> first(@Nullable T first)", "public Page<T> build()",
            "public record Meta<T>(", "@JsonProperty(\"inner\") @Nullable T inner)", "Page.@Nullable Meta<T> meta", "public Meta<T> build()");
    }

    @Test
    void discriminatorDefaultMappingsBecomeTheDefaultImplementation() {
        var mapper = mapper("3.2.0", """
            Cat: {type: object, required: [kind], properties: {kind: {type: string}}}
            Dog: {type: object, required: [kind], properties: {kind: {type: string}}}
            Elsewhere: {type: object}
            Pet:
              oneOf: [{$ref: '#/components/schemas/Cat'}, {$ref: '#/components/schemas/Dog'}]
              discriminator: {propertyName: kind, defaultMapping: Dog}
            Stray:
              oneOf: [{$ref: '#/components/schemas/Cat'}]
              discriminator: {propertyName: kind, defaultMapping: Elsewhere}
            Vehicle: {type: object, discriminator: {propertyName: kind, defaultMapping: '#/components/schemas/Car'}, properties: {kind: {type: string}}}
            Car: {allOf: [{$ref: '#/components/schemas/Vehicle'}]}
            Boat: {allOf: [{$ref: '#/components/schemas/Vehicle'}]}
            Plane: {type: object, discriminator: {propertyName: kind, defaultMapping: Nowhere}, properties: {kind: {type: string}}}
            Glider: {allOf: [{$ref: '#/components/schemas/Plane'}]}
            """);
        var renderer = new ModelRenderer(mapper);
        assertThat(renderer.file(definition(mapper, model("Pet"), UnionDef.class))).contains("defaultImpl = Dog.class");
        assertThat(renderer.file(definition(mapper, model("Stray"), UnionDef.class))).doesNotContain("defaultImpl");
        assertThat(renderer.file(definition(mapper, model("Vehicle"), ParentDef.class))).contains("defaultImpl = Car.class");
        assertThat(renderer.file(definition(mapper, model("Plane"), ParentDef.class))).contains("defaultImpl = Plane.Default.class");
    }

    @Test
    void honoursNullableNextToReferencesInOpenApi30() {
        var mapper = mapper("3.0.3", """
            Address: {type: object, properties: {street: {type: string}}}
            Holder:
              type: object
              required: [address]
              properties:
                address: {$ref: '#/components/schemas/Address', nullable: true}
            """);
        assertThat(properties(mapper, "Holder")).containsExactly(Map.entry("address", "@Nullable Address"));
    }
}
