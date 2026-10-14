package dev.openapifn.example;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.Json;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.schema.Direction;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExampleGeneratorTest {

    private static final OpenApiDocument API = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
        openapi: 3.1.0
        info: {title: t, version: v}
        components:
          schemas:
            Declared: {type: integer, examples: [7], default: 3}
            Const: {const: x}
            Default: {type: string, default: d}
            Enum: {enum: [null, b]}
            Pet:
              type: object
              required: [id, name, kind]
              properties:
                id: {type: integer, readOnly: true}
                name: {type: string, minLength: 10, maxLength: 12}
                kind: {$ref: '#/components/schemas/Kind'}
                secret: {type: string, writeOnly: true}
                tags: {type: array, items: {type: string}, minItems: 2, uniqueItems: true}
                nested: {type: object, required: [a], properties: {a: {type: string, format: uuid}, b: {type: string}}}
                parent: {$ref: '#/components/schemas/Pet'}
            Kind: {type: string, enum: [cat, dog]}
            Bounded: {type: integer, minimum: 5, maximum: 9, multipleOf: 4}
            Exclusive: {type: number, exclusiveMinimum: 1, exclusiveMaximum: 3}
            Below: {type: integer, maximum: -3}
            Numbers: {type: array, items: {type: integer}, minItems: 3, uniqueItems: true}
            Strings: {type: array, items: {}, minItems: 2, uniqueItems: true}
            Untyped: {}
            Bool: {type: boolean}
            Null: {type: 'null'}
            Formats: {type: string, format: date-time}
            Unknown: {type: string, format: custom}
            Pattern: {type: string, pattern: '^[0-9]{4}$'}
            Shape:
              oneOf: [{$ref: '#/components/schemas/Circle'}, {$ref: '#/components/schemas/Square'}]
              discriminator: {propertyName: type, mapping: {round: '#/components/schemas/Circle'}}
            Implicit:
              anyOf: [{$ref: '#/components/schemas/Square'}]
              discriminator: {propertyName: type}
            Inline:
              oneOf: [{type: object, properties: {v: {type: integer}}}]
              discriminator: {propertyName: type}
            NoDiscriminator: {oneOf: [{type: integer, minimum: 2}]}
            Circle: {type: object, required: [type, r], properties: {type: {type: string}, r: {type: number}}}
            Square: {type: object, required: [type], properties: {type: {type: string}}}
            Items: {type: array}
            Short: {type: string, maxLength: 3}
            Pair: {type: array, items: {type: boolean}, minItems: 2}
            ZeroMultiple: {type: integer, multipleOf: 0}
            Optional: {type: object, properties: {nothing: {type: 'null'}, something: {type: integer}}}
            PrimitiveUnion: {oneOf: [{type: string}], discriminator: {propertyName: kind}}
            Page:
              $id: 'https://example.com/schemas/Page'
              $defs:
                item: {$dynamicAnchor: item, not: {}}
                unused: {type: string}
              type: object
              required: [items]
              properties:
                items: {type: array, items: {$dynamicRef: '#item'}}
            CirclePage:
              $id: 'https://example.com/schemas/CirclePage'
              $defs:
                item: {$dynamicAnchor: item, $ref: 'mem:/api.yaml#/components/schemas/Circle'}
              $ref: 'https://example.com/schemas/Page'
            BadExample: {type: string, format: uuid, examples: [not-a-uuid, 3fa85f64-5717-4562-b3fc-2c963f66afa6]}
            WorseExample: {type: string, format: uuid, examples: [not-a-uuid]}
            Refined:
              allOf:
                - {type: object, required: [data], properties: {data: {type: array, items: {type: object}}, id: {type: integer}}}
                - {properties: {data: {type: array, items: {$ref: '#/components/schemas/Circle'}}}}
              properties:
                id: {type: integer, minimum: 5}
            Deep: {type: object, required: [a], properties: {a: {type: object, required: [a], properties: {a: {type: object, required: [a], properties: {a: {type: object, required: [a], properties: {a: {type: object, required: [a], properties: {a: {type: object, required: [a], properties: {a: {type: object, required: [a], properties: {a: {type: object, required: [a], properties: {a: {type: object, required: [a], properties: {a: {type: object, required: [a], properties: {a: {type: string}}}}}}}}}}}}}}}}}}}}}
        """).orElseThrow(d -> new AssertionError(d.toString()));
    private static final ExampleGenerator GENERATOR = new ExampleGenerator(API.schemas());

    private static Optional<JsonValue> example(String name, Direction direction) {
        return GENERATOR.example(API.model().components().schemas().get(name), direction);
    }

    private static JsonValue json(String text) {
        return Json.parse(text).orElseThrow(e -> new AssertionError(e.toString()));
    }

    @Test
    void dynamicReferencesUseTheOutermostBinding() {
        assertThat(example("CirclePage", Direction.RESPONSE)).contains(json("{\"items\":[{\"type\":\"example\",\"r\":1}]}"));
        assertThat(example("Page", Direction.RESPONSE)).isEmpty();
    }

    @Test
    void invalidDeclaredExamplesAreSkipped() {
        assertThat(example("BadExample", Direction.NONE)).contains(json("\"3fa85f64-5717-4562-b3fc-2c963f66afa6\""));
        assertThat(example("WorseExample", Direction.NONE)).contains(json("\"3fa85f64-5717-4562-b3fc-2c963f66afa6\""));
    }

    @Test
    void laterDeclarationsOfAPropertyWin() {
        assertThat(example("Refined", Direction.RESPONSE)).contains(json("{\"data\":[{\"type\":\"example\",\"r\":1}],\"id\":5}"));
    }

    @Test
    void prefersDeclaredValues() {
        assertThat(example("Declared", Direction.NONE)).contains(json("7"));
        assertThat(example("Const", Direction.NONE)).contains(json("\"x\""));
        assertThat(example("Default", Direction.NONE)).contains(json("\"d\""));
        assertThat(example("Enum", Direction.NONE)).contains(json("\"b\""));
    }

    @Test
    void synthesizesObjectsPerDirection() {
        assertThat(example("Pet", Direction.REQUEST)).contains(json("""
            {"name":"examplexxx","kind":"cat","secret":"example","tags":["example0","example1"],"nested":{"a":"3fa85f64-5717-4562-b3fc-2c963f66afa6"}}"""));
        assertThat(example("Pet", Direction.RESPONSE).orElseThrow().asObject().orElseThrow().keys())
            .containsExactly("id", "name", "kind", "tags", "nested");
    }

    @Test
    void respectsNumericAndArrayConstraints() {
        assertThat(example("Bounded", Direction.NONE)).contains(json("8"));
        assertThat(example("Exclusive", Direction.NONE)).contains(json("1.5"));
        assertThat(example("Below", Direction.NONE)).contains(json("-3"));
        assertThat(example("Numbers", Direction.NONE)).contains(json("[1,2,3]"));
        assertThat(example("Strings", Direction.NONE)).contains(json("[\"example0\",\"example1\"]"));
        assertThat(example("Items", Direction.NONE)).contains(json("[\"item\"]"));
    }

    @Test
    void primitivesAndFormats() {
        assertThat(example("Untyped", Direction.NONE)).contains(json("\"example\""));
        assertThat(example("Bool", Direction.NONE)).contains(json("true"));
        assertThat(example("Null", Direction.NONE)).contains(json("null"));
        assertThat(example("Formats", Direction.NONE)).contains(json("\"2024-01-15T10:30:00Z\""));
        assertThat(example("Unknown", Direction.NONE)).contains(json("\"example\""));
        assertThat(example("Pattern", Direction.NONE)).isEmpty();
    }

    @Test
    void choosesUnionMembersAndDiscriminatorValues() {
        assertThat(example("Shape", Direction.NONE)).contains(json("{\"type\":\"round\",\"r\":1}"));
        assertThat(example("Implicit", Direction.NONE)).contains(json("{\"type\":\"Square\"}"));
        assertThat(example("Inline", Direction.NONE)).contains(json("{\"v\":1,\"type\":\"0\"}"));
        assertThat(example("NoDiscriminator", Direction.NONE)).contains(json("2"));
    }

    @Test
    void edgeCases() {
        assertThat(example("Short", Direction.NONE)).contains(json("\"exa\""));
        assertThat(example("Pair", Direction.NONE)).contains(json("[true,true]"));
        assertThat(example("ZeroMultiple", Direction.NONE)).contains(json("1"));
        assertThat(example("Optional", Direction.NONE)).contains(json("{\"something\":1}"));
        assertThat(example("PrimitiveUnion", Direction.NONE)).contains(json("\"example\""));
        assertThat(example("Deep", Direction.NONE)).isEmpty();
    }

    @Test
    void openApi30ExclusiveBounds() {
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
            openapi: 3.0.3
            info: {title: t, version: v}
            paths: {}
            components:
              schemas:
                Range: {type: integer, minimum: 1, exclusiveMinimum: true, maximum: 5, exclusiveMaximum: true}
                Top: {type: number, maximum: 5, exclusiveMaximum: true}
            """).orElseThrow(d -> new AssertionError(d.toString()));
        var generator = new ExampleGenerator(api.schemas());
        assertThat(generator.example(api.model().components().schemas().get("Range"), Direction.NONE)).contains(json("2"));
        assertThat(generator.example(api.model().components().schemas().get("Top"), Direction.NONE)).contains(json("1"));
    }
}
